package com.poseguard.monitor

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 关键点：COCO 17 点 */
data class Keypoint(val x: Float, val y: Float, val conf: Float)

/** 一个人：外框 + 17 个关键点 */
data class Person(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val conf: Float,
    val kps: List<Keypoint>
) {
    val w get() = right - left
    val h get() = bottom - top
}

object PosePost {

    /** COCO 骨架连线：15 条 */
    val SKELETON = arrayOf(
        intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2, 4),
        intArrayOf(5, 6), intArrayOf(5, 11), intArrayOf(6, 12), intArrayOf(11, 12),
        intArrayOf(5, 7), intArrayOf(7, 9),
        intArrayOf(6, 8), intArrayOf(8, 10),
        intArrayOf(11, 13), intArrayOf(13, 15),
        intArrayOf(12, 14), intArrayOf(14, 16)
    )

    /**
     * 解析 YOLOv8-pose NCNN 输出。
     * 输出张量形状 [1, 56, 8400]：
     *   0..3    cx, cy, w, h        (归一化 0..1)
     *   4       objectness / score
     *   5..55   17 个点 × (x, y, conf)   (归一化 0..1)
     */
    fun parse(
        data: FloatArray, num: Int, inW: Int, inH: Int,
        confTh: Float = 0.45f, nmsTh: Float = 0.45f
    ): List<Person> {
        val raw = ArrayList<Person>(32)
        for (i in 0 until num) {
            val score = data[4 * num + i]
            if (score < confTh) continue

            val cx = data[0 * num + i] * inW
            val cy = data[1 * num + i] * inH
            val bw = data[2 * num + i] * inW
            val bh = data[3 * num + i] * inH

            val kps = ArrayList<Keypoint>(17)
            for (k in 0 until 17) {
                val base = (5 + k * 3) * num + i
                kps.add(
                    Keypoint(
                        data[base] * inW,
                        data[base + num] * inH,
                        data[base + 2 * num]
                    )
                )
            }
            raw.add(
                Person(
                    cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f,
                    score, kps
                )
            )
        }
        return nms(raw, nmsTh)
    }

    private fun iou(a: Person, b: Person): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val uni = a.w * a.h + b.w * b.h - inter
        return if (uni <= 0f) 0f else inter / uni
    }

    private fun nms(list: List<Person>, th: Float): List<Person> {
        val sorted = list.sortedByDescending { it.conf }.toMutableList()
        val keep = ArrayList<Person>()
        while (sorted.isNotEmpty()) {
            val head = sorted.removeAt(0)
            keep.add(head)
            sorted.removeAll { iou(head, it) > th }
        }
        return keep
    }

    /** 简单的 sigmoid，模型输出若是 logits 时用 */
    fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
}
