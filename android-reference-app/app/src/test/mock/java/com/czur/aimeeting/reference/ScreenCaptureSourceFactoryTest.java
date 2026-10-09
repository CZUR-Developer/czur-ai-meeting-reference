package com.czur.aimeeting.reference;

import static org.junit.Assert.*;
import org.junit.Test;

/** 干净Mock没有AAR；能力与实际创建一致，不宣称可截图。 */
public class ScreenCaptureSourceFactoryTest {
    @Test public void mockDoesNotClaimOrCreateScreenCapture() {
        assertFalse(ScreenCaptureSourceFactory.isSupported());
        try {
            ScreenCaptureSourceFactory.create(null, 0, null, Runnable::run, () -> {});
            fail("Mock不能创建截图引擎");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("Mock"));
        }
    }
}
