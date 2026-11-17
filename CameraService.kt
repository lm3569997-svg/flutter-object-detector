package com.poseguard.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台相机 + 推理 + 悬浮窗服务。
 * 只在前台运行（foregroundServiceType=camera），系统要求。
 */
class CameraService : LifecycleService() {

    companion object {
        const val CH_ID = "poseguard"
        const val NOTI_ID = 1001
        @Volatile var running = false
        @Volatile var lastFps = 0
        @Volatile var lastCount = 0
        @Volatile var lastError: String? = null
        @Volatile var options = Options()
    }

    data class Options(
        val conf: Float = 0.45f,
        val kpConf: Float = 0.30f,
        val showSkeleton: Boolean = true,
        val showJoints: Boolean = true,
        val showScan: Boolean = true,
        val showGrid: Boolean = true,
        val showCorner: Boolean = true,
        val skip: Int = 1
    )

    private val inferExec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var detector: PoseDetector? = null
    private var overlayCtl: OverlayController? = null
    private var frameCount = 0
    private var fpsMark = 0L

    override fun onBind(intent: Intent?): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        options = Options(
            conf = intent?.getFloatExtra("conf", 0.45f) ?: 0.45f,
            skip = intent?.getIntExtra("skip", 1) ?: 1
        )

        startForeground(NOTI_ID, buildNotification())

        val od = OverlayController(this)
        val ov = od.attach()
        ov.showSkeleton = options.showSkeleton
        ov.showJoints = options.showJoints
        ov.showScan = options.showScan
        ov.showGrid = options.showGrid
        ov.showCornerBox = options.showCorner
        overlayCtl = od

        val det = PoseDetector(this)
        if (!det.init()) {
            lastError = det.error() ?: "模型加载失败"
            running = false
        } else {
            lastError = null
            detector = det
        }

        bindCamera()
        running = true
        return START_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                val resSel = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(640, 640),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    ).build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resSel)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(inferExec) { img ->
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsMark >= 1000) {
                        lastFps = (frameCount * 1000 / (now - fpsMark)).toInt()
                        frameCount = 0
                        fpsMark = now
                    }

                    if (options.skip > 1 && (frameCount % options.skip) != 0) {
                        img.close(); return@setAnalyzer
                    }
                    if (!busy.compareAndSet(false, true)) {
                        img.close(); return@setAnalyzer
                    }
                    try {
                        val bmp = img.toBitmap()
                        val det = detector
                        if (det != null) {
                            val persons = det.detect(bmp)
                            lastCount = persons.size
                            val sc = scaleToScreen(img.width, img.height)
                            postToOverlay(persons, sc.first, sc.second)
                        }
                    } catch (t: Throwable) {
                        lastError = "${t.javaClass.simpleName}: ${t.message}"
                    } finally {
                        img.close()
                        busy.set(false)
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, analysis
                )
            } catch (t: Throwable) {
                lastError = "相机启动失败: ${t.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 推理分辨率 -> 屏幕分辨率 */
    private fun scaleToScreen(w: Int, h: Int): Pair<Float, Float> {
        val dm = resources.displayMetrics
        return (dm.widthPixels.toFloat() / w) to (dm.heightPixels.toFloat() / h)
    }

    private fun postToOverlay(persons: List<Person>, sx: Float, sy: Float) {
        val view = runCatching { overlayCtl?.attach() }.getOrNull() ?: return
        view.post {
            view.persons = persons.map { p ->
                Person(
                    p.left * sx, p.top * sy, p.right * sx, p.bottom * sy, p.conf,
                    p.kps.map { Keypoint(it.x * sx, it.y * sy, it.conf) }
                )
            }
            view.setFps(lastFps, persons.size)
            view.invalidate()
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CH_ID, "PoseGuard", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "相机识别运行中" }
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("PoseGuard 运行中")
            .setContentText("相机识别 + 骨架标注")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        overlayCtl?.detach()
        detector?.release()
        inferExec.shutdownNow()
        super.onDestroy()
    }
}

/** ImageProxy(YUV_420_888) -> Bitmap */
fun ImageProxy.toBitmap(): Bitmap {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val ySize = yPlane.buffer.remaining()
    val uSize = uPlane.buffer.remaining()
    val vSize = vPlane.buffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yPlane.buffer.get(nv21, 0, ySize)
    vPlane.buffer.get(nv21, ySize, vSize)
    uPlane.buffer.get(nv21, ySize + vSize, uSize)

    val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, width, height), 82, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
