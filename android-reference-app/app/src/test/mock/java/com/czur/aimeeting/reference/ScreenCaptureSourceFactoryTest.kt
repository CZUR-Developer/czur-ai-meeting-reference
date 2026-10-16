package com.czur.aimeeting.reference

import org.junit.Assert.*
import org.junit.Test

/** 干净 Mock 不含 AAR，实际创建能力与声明一致。 */
class ScreenCaptureSourceFactoryTest {
    @Test fun mockDoesNotClaimOrCreateScreenCapture() {
        assertFalse(ScreenCaptureSourceFactory.isSupported())
        try {
            ScreenCaptureSourceFactory.create(null, 0, null, Runnable::run, Runnable {})
            fail("Mock不能创建截图引擎")
        } catch (expected: UnsupportedOperationException) { assertTrue(expected.message!!.contains("Mock")) }
    }
}
