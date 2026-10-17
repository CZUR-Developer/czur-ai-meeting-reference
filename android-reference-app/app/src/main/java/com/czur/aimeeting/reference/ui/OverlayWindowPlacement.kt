package com.czur.aimeeting.reference.ui

import kotlin.math.roundToInt

/**
 * App 的窗口位置偏好，不参与 SDK 的窗口生命周期或会议状态。
 *
 * 每种形态只在用户实际拖动后记录位置。保存的是剩余可移动区域中的比例，
 * 因此切换分辨率、简洁高度或 Mini 恢复后，窗口仍留在相近位置且完整可见；
 * 没有拖动过的形态继续使用原来的居中靠底布局。Mini 不调用此工具。
 */
internal class OverlayWindowPlacement {
    data class Position(val x: Int, val y: Int)
    private data class RelativePosition(val x: Float, val y: Float)
    private val positions = mutableMapOf<String, RelativePosition>()

    fun resolve(form: String, width: Int, height: Int, screenWidth: Int, screenHeight: Int,
        defaultX: Int, defaultY: Int): Position {
        val horizontalRange = (screenWidth - width).coerceAtLeast(0)
        val verticalRange = (screenHeight - height).coerceAtLeast(0)
        val saved = positions[form] ?: return Position(defaultX.coerceIn(0, horizontalRange),
            defaultY.coerceIn(0, verticalRange))
        return Position((horizontalRange * saved.x).roundToInt(), (verticalRange * saved.y).roundToInt())
    }

    /** 只由正常拖动调用；边缘约束保证停止、展开等按钮仍可操作。 */
    fun move(form: String, requestedX: Int, requestedY: Int, width: Int, height: Int,
        screenWidth: Int, screenHeight: Int): Position {
        val horizontalRange = (screenWidth - width).coerceAtLeast(0)
        val verticalRange = (screenHeight - height).coerceAtLeast(0)
        val x = requestedX.coerceIn(0, horizontalRange)
        val y = requestedY.coerceIn(0, verticalRange)
        positions[form] = RelativePosition(if (horizontalRange == 0) 0f else x.toFloat() / horizontalRange,
            if (verticalRange == 0) 0f else y.toFloat() / verticalRange)
        return Position(x, y)
    }
}
