package com.czur.aimeeting.reference.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 只测正常窗口几何：默认位置、拖动后缩放和独立形态，不涉及会议状态。 */
class OverlayWindowPlacementTest {
    @Test fun untouchedFormUsesDefaultAndDraggedFormKeepsRelativePositionAfterResize() {
        val placement = OverlayWindowPlacement()
        assertEquals(OverlayWindowPlacement.Position(60, 848),
            placement.resolve("subtitle", 1800, 222, 1920, 1080, 60, 848))
        assertEquals(OverlayWindowPlacement.Position(90, 429),
            placement.move("subtitle", 90, 429, 1800, 222, 1920, 1080))
        assertEquals(OverlayWindowPlacement.Position(60, 286),
            placement.resolve("subtitle", 1200, 148, 1280, 720, 40, 565))
        assertEquals(OverlayWindowPlacement.Position(60, 777),
            placement.resolve("translation-normal", 1800, 293, 1920, 1080, 60, 777))
    }

    @Test fun draggingKeepsCompleteWindowInVisibleScreen() {
        val placement = OverlayWindowPlacement()
        assertEquals(OverlayWindowPlacement.Position(0, 858),
            placement.move("subtitle", -40, 1400, 1800, 222, 1920, 1080))
        assertEquals(OverlayWindowPlacement.Position(80, 0),
            placement.move("subtitle", 300, -10, 1200, 148, 1280, 720))
    }
}
