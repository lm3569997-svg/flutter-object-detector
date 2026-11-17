package com.poseguard.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var conf = 0.45f
    private var skip = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val btnOverlay = findViewById<Button>(R.id.btnOverlay)
        val btnPerm = findViewById<Button>(R.id.btnPerm)

        val swSkeleton = findViewById<Switch>(R.id.swSkeleton)
        val swJoints = findViewById<Switch>(R.id.swJoints)
        val swScan = findViewById<Switch>(R.id.swScan)
        val swGrid = findViewById<Switch>(R.id.swGrid)
        val swCorner = findViewById<Switch>(R.id.swCorner)

        val seekConf = findViewById<SeekBar>(R.id.seekConf)
        val seekSkip = findViewById<SeekBar>(R.id.seekSkip)

        seekConf.progress = 45
        seekConf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                conf = (p.coerceIn(20, 90)) / 100f
                findViewById<TextView>(R.id.tvConf).text = "置信度 ${p}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekSkip.progress = 0
        seekSkip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                skip = p.coerceIn(0, 3) + 1
                findViewById<TextView>(R.id.tvSkip).text = "每 ${skip} 帧推理一次"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnPerm.setOnClickListener {
            requestPerms()
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                toast("悬浮窗权限已授予")
            }
        }

        btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPerms()
                return@setOnClickListener
            }
            CameraService.options = CameraService.Options(
                conf = conf, skip = skip,
                showSkeleton = swSkeleton.isChecked,
                showJoints = swJoints.isChecked,
                showScan = swScan.isChecked,
                showGrid = swGrid.isChecked,
                showCorner = swCorner.isChecked
            )
            val i = Intent(this, CameraService::class.java)
            i.putExtra("conf", conf)
            i.putExtra("skip", skip)
            ContextCompat.startForegroundService(this, i)
            refresh()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, CameraService::class.java))
            refresh()
        }

        refresh()
    }

    private fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, list.toTypedArray(), 100)
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val b = StringBuilder()
        b.append("运行: ").append(if (CameraService.running) "是" else "否").append('\n')
        b.append("悬浮窗: ").append(if (ov) "已授权" else "未授权").append('\n')
        b.append("相机: ").append(if (cam) "已授权" else "未授权").append('\n')
        b.append("FPS: ").append(CameraService.lastFps).append('\n')
        b.append("目标: ").append(CameraService.lastCount).append('\n')
        CameraService.lastError?.let { b.append("错误: ").append(it) }
        status.text = b.toString()
    }
}
