package com.poseguard.monitor

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * 监控风格的悬浮层：框 + 骨架 + 关节点 + 扫描线。
 * 不接收触摸，不抢焦点，全屏覆盖。
 */
class MonitorOverlay(ctx: Context) : View(ctx) {

    companion object {
        val GREEN = 0xFF00FF66.toInt()
        val RED = 0xFFFF3366.toInt()
        val AMBER = 0xFFFFC107.toInt()
    }

    /** 待绘制的目标（已换算到屏幕坐标） */
    @Volatile
    var persons: List<Person> = emptyList()

    /** 显示模式 */
    var showSkeleton = true
    var showJoints = true
    var showScan = true
    var showGrid = true
    var showCornerBox = true

    private var scanY = 0f
    private var fps = 0
    private var count = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = GREEN
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = GREEN
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply {
        style = Paint.Style.FILL
        color = RED
        isAntiAlias = true
    }
    private val haloPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x88FFFFFF.toInt()
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = GREEN
        textSize = 26f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val smallPaint = Paint().apply {
        color = 0xAA00FF66.toInt()
        textSize = 20f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }
    private val gridPaint = Paint().apply {
        color = 0x3300FF66
        strokeWidth = 1f
    }
    private val scanPaint = Paint().apply {
        color = 0x6600FF66.toInt()
        strokeWidth = 3f
    }

    fun setFps(v: Int, n: Int) { fps = v; count = n }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val now = System.currentTimeMillis()

        if (showGrid) drawGrid(c)

        val list = persons
        for (p in list) {
            val r = RectF(p.left, p.top, p.right, p.bottom)

            // 外框：四角括号 或 全矩形
            if (showCornerBox) drawCorners(c, r, boxPaint) else c.drawRect(r, boxPaint)

            // 标签
            val label = "PERSON ${(p.conf * 100).toInt()}%"
            val bgRect = RectF(r.left, r.top - 32f, r.left + textPaint.measureText(label) + 12f, r.top)
            c.drawRect(bgRect, Paint().apply { color = 0x88000000.toInt() })
            c.drawText(label, r.left + 6f, r.top - 10f, textPaint)

            // 骨架
            if (showSkeleton) {
                for (pair in PosePost.SKELETON) {
                    val ka = p.kps[pair[0]]; val kb = p.kps[pair[1]]
                    if (ka.conf < PoseDetector.KP_CONF || kb.conf < PoseDetector.KP_CONF) continue
                    c.drawLine(ka.x, ka.y, kb.x, kb.y, linePaint)
                }
            }

            // 关节点
            if (showJoints) {
                for (k in p.kps) {
                    if (k.conf < PoseDetector.KP_CONF) continue
                    c.drawCircle(k.x, k.y, 5f, dotPaint)
                    c.drawCircle(k.x, k.y, 9f, haloPaint)
                }
            }
        }

        if (showScan) {
            scanY = (scanY + 10f) % height.coerceAtLeast(1).toFloat()
            c.drawLine(0f, scanY, width.toFloat(), scanY, scanPaint)
            postInvalidateOnAnimation()
        }

        drawHud(c)
    }

    private fun drawGrid(c: Canvas) {
        val step = 120f
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }
        // 十字准星
        c.drawLine(width / 2f - 40f, height / 2f, width / 2f + 40f, height / 2f, gridPaint)
        c.drawLine(width / 2f, height / 2f - 40f, width / 2f, height / 2f + 40f, gridPaint)
    }

    private fun drawCorners(c: Canvas, r: RectF, paint: Paint, len: Float = 30f) {
        // 左上
        c.drawLine(r.left, r.top, r.left + len, r.top, paint)
        c.drawLine(r.left, r.top, r.left, r.top + len, paint)
        // 右上
        c.drawLine(r.right, r.top, r.right - len, r.top, paint)
        c.drawLine(r.right, r.top, r.right, r.top + len, paint)
        // 左下
        c.drawLine(r.left, r.bottom, r.left + len, r.bottom, paint)
        c.drawLine(r.left, r.bottom, r.left, r.bottom - len, paint)
        // 右下
        c.drawLine(r.right, r.bottom, r.right - len, r.bottom, paint)
        c.drawLine(r.right, r.bottom, r.right, r.bottom - len, paint)
    }

    private fun drawHud(c: Canvas) {
        val pad = 24f
        val y0 = pad + 24f
        c.drawText("POSE-GUARD  ONLINE", pad, y0, textPaint)
        c.drawText("TARGETS: ${persons.size}", pad, y0 + 30f, smallPaint)
        c.drawText("FPS: $fps", pad, y0 + 56f, smallPaint)
        c.drawText(
            "TIME: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}",
            pad, y0 + 82f, smallPaint
        )
    }
}

/** 把悬浮层挂到 WindowManager 上 */
class OverlayController(private val ctx: Context) {
    private var view: MonitorOverlay? = null

    fun attach(): MonitorOverlay {
        view?.let { if (it.isAttachedToWindow) return it }
        val v = MonitorOverlay(ctx)
        val dm = ctx.resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
        view = v
        return v
    }

    fun detach() {
        view?.let {
            runCatching {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        view = null
    }
}
