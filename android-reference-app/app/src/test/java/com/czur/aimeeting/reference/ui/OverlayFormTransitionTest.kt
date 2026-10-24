package com.czur.aimeeting.reference.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只测形态切换的缩放几何：缩放比与 pivot，不涉及窗口生命周期。 */
class OverlayFormTransitionTest {
    /** 缩放后某个全尺寸坐标实际落在屏幕上的位置。 */
    private fun mapped(x: Int, pivot: Float, scale: Float): Float = pivot + (x - pivot) * scale

    @Test fun collapseLeavesWindowAtMiniPosition() {
        // 1920 屏：普通窗口 [60, 1860]，Mini 目标 [1734, 1860]。
        val geometry = OverlayFormTransition.collapse(60, 1800, 1734, 126)
        assertEquals(0.07f, geometry.scale, 0.0001f)
        assertEquals(1734f, mapped(60, geometry.pivotX, geometry.scale), 0.01f)
        assertEquals(1860f, mapped(1860, geometry.pivotX, geometry.scale), 0.01f)
    }

    @Test fun expandStartsFromMiniPosition() {
        // 展开时窗口已按 1800 宽摆在 x=60，起点视觉上应为 Mini 的 [1734, 1860]。
        val geometry = OverlayFormTransition.expand(1734, 126, 60, 1800)
        assertEquals(0.07f, geometry.scale, 0.0001f)
        assertEquals(1734f, mapped(60, geometry.pivotX, geometry.scale), 0.01f)
        assertEquals(1860f, mapped(1860, geometry.pivotX, geometry.scale), 0.01f)
    }

    @Test fun sameWidthKeepsPivotAtWindowOrigin() {
        val geometry = OverlayFormTransition.collapse(60, 1800, 60, 1800)
        assertEquals(1f, geometry.scale, 0.0001f)
        assertEquals(60f, geometry.pivotX, 0.01f)
    }

    @Test fun degenerateWidthFallsBackToNoScaling() {
        val geometry = OverlayFormTransition.collapse(60, 0, 1734, 126)
        assertEquals(1f, geometry.scale, 0.0001f)
        assertTrue(geometry.pivotX.isFinite())
    }
}
