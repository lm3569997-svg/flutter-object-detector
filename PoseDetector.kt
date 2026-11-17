package com.poseguard.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.tencent.ncnn.Net
import com.tencent.ncnn.Mat

/**
 * YOLOv8n-pose 的 NCNN 推理封装。
 * 模型放在 app/src/main/assets/：
 *   yolov8n_pose.ncnn.param
 *   yolov8n_pose.ncnn.bin
 */
class PoseDetector(private val ctx: Context) {

    companion object {
        const val IN_W = 640
        const val IN_H = 640
        const val NUM = 8400          // 输出网格数
        const val CONF = 0.45f
        const val KP_CONF = 0.30f
    }

    private var net: Net? = null
    private var ready = false
    private var loadError: String? = null

    fun init(): Boolean {
        if (ready) return true
        return try {
            val n = Net()
            val param = ctx.assets.open("yolov8n_pose.ncnn.param")
                .readBytes().toString(Charsets.UTF_8)
            val bin = ctx.assets.open("yolov8n_pose.ncnn.bin").readBytes()
            n.loadParam(param.toByteArray())
            n.loadModel(bin)
            n.opt.use_vulkan_compute = true     // GPU 加速
            n.opt.num_threads = 4
            net = n
            ready = true
            true
        } catch (e: Exception) {
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            ready = false
            false
        }
    }

    fun error(): String? = loadError

    /** 输入一张 Bitmap，返回识别出的人（坐标已换算回 bitmap 尺寸） */
    fun detect(src: Bitmap): List<Person> {
        val n = net ?: return emptyList()
        if (!ready) return emptyList()

        // 1) letterbox 缩放到 640x640
        val scaled = Bitmap.createScaledBitmap(src, IN_W, IN_H, true)

        // 2) Bitmap -> Mat
        val inMat = Mat()
        Mat.fromBitmap(inMat, scaled)

        // 3) 推理
        val outMat = Mat()
        val ex = n.createExtractor()
        ex.input("images", inMat)
        ex.extract("output0", outMat)

        // 4) 取数据：输出形状 [1, 56, 8400] -> w=8400, h=56
        val w = outMat.w
        val h = outMat.h
        val flat = outMat.toFloatArray()
        if (flat.size < w * h) {
            outMat.release(); inMat.release(); ex.input("images", Mat())
            return emptyList()
        }

        // 5) 后处理：num 是每行的锚点数（列数）
        val num = if (h == NUM) NUM else w
        val lst = PosePost.parse(flat, num, IN_W, IN_H, CONF)
        outMat.release(); inMat.release()

        // 6) 坐标换算回源图尺寸
        val sx = src.width.toFloat() / IN_W
        val sy = src.height.toFloat() / IN_H
        return lst.map { person ->
            Person(
                person.left * sx, person.top * sy, person.right * sx, person.bottom * sy, person.conf,
                person.kps.map { kp -> Keypoint(kp.x * sx, kp.y * sy, kp.conf) }
            )
        }
    }

    fun release() {
        net?.clear()
        net = null
        ready = false
    }
}
