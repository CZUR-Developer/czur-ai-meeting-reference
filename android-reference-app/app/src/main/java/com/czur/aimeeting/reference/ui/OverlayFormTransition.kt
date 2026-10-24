package com.czur.aimeeting.reference.ui

/**
 * 形态切换的缩放几何，只做数学，不碰 View 和 WindowManager，便于单测。
 *
 * <p>窗口围绕 pivotX 做水平缩放：坐标 x 会被映射成 {@code pivot + (x - pivot) * scale}。
 * 收起时窗口当前占据 {@code [currentX, currentX + 当前宽]}，动画结束时应该落在
 * {@code [targetX, targetX + Mini 宽]}；展开时窗口已经按目标尺寸摆在 {@code targetX}，
 * 动画开始时应该看起来还在 Mini 的位置。两种情形解同一个方程，只是已知量不同。</p>
 */
internal object OverlayFormTransition {
    /** 与 CZUR 悬浮窗保持一致的过渡时长。 */
    const val DURATION_MS = 400L

    /** 缩放比小于该值时 pivot 会趋于无穷，直接按不缩放处理。 */
    private const val MIN_SCALE_GAP = 0.001f

    data class Geometry(val pivotX: Float, val scale: Float)

    /** 收起：当前窗口位于 currentX、宽 currentWidth，缩到 targetX、宽 targetWidth。 */
    fun collapse(currentX: Int, currentWidth: Int, targetX: Int, targetWidth: Int): Geometry {
        val scale = scaleOf(targetWidth, currentWidth)
        return Geometry(pivot(scaledX = targetX, xAtFullScale = currentX, scale = scale), scale)
    }

    /** 展开：窗口按目标尺寸摆在 targetX，从 Mini 的 miniX、宽 miniWidth 放大过来。 */
    fun expand(miniX: Int, miniWidth: Int, targetX: Int, targetWidth: Int): Geometry {
        val scale = scaleOf(miniWidth, targetWidth)
        return Geometry(pivot(scaledX = miniX, xAtFullScale = targetX, scale = scale), scale)
    }

    private fun scaleOf(scaledWidth: Int, fullWidth: Int): Float {
        if (fullWidth <= 0 || scaledWidth <= 0) return 1f
        return (scaledWidth.toFloat() / fullWidth).coerceIn(MIN_SCALE_GAP, 1f)
    }

    /**
     * 解 {@code pivot + (xAtFullScale - pivot) * scale = scaledX}，
     * 即缩放后 xAtFullScale 处的点正好落在 scaledX。
     */
    private fun pivot(scaledX: Int, xAtFullScale: Int, scale: Float): Float {
        val gap = 1f - scale
        if (gap < MIN_SCALE_GAP) return xAtFullScale.toFloat()
        return (scaledX - scale * xAtFullScale) / gap
    }
}
