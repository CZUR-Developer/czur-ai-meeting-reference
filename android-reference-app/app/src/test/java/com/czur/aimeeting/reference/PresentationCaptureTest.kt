package com.czur.aimeeting.reference

import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test

class PresentationCaptureTest {
    @Test fun busyTicksAreSkippedAndStopDoesNotCaptureTheLaterScreenAfterAck() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val periodic = Result()
        capture.capture(2_000, emptyArray(), periodic)
        source.deliver(0)
        val skipped = Result()
        repeat(100) { capture.capture(3_000L + it, emptyArray(), skipped) }
        var completed = 0
        capture.stop(5_000, emptyArray(), Result(), Runnable { completed++ })
        source.screen = byteArrayOf(9)
        assertEquals(100, skipped.empty)
        assertEquals(1, source.callbacks.size)
        assertEquals(0, completed)
        val frame = periodic.frames.single()
        assertEquals(1_000L, frame.offsetMs())
        assertArrayEquals(byteArrayOf(1), frame.jpeg())
        frame.confirmAccepted()
        assertEquals(0, completed)
        frame.close()
        capture.capture(10_000, emptyArray(), skipped)
        assertEquals(1, completed)
        assertEquals(1, source.accepted)
        assertEquals("ACK 后不能再发起延迟末帧", 1, source.callbacks.size)
    }

    @Test fun idleStopCapturesOnceAtItsOwnOffsetAndWaitsForBusinessRelease() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var completed = 0
        capture.stop(4_250, emptyArray(), result, Runnable { completed++; capture.close() })
        source.screen = byteArrayOf(9)
        source.deliver(0)
        val frame = result.frames.single()
        assertEquals(3_250L, frame.offsetMs())
        assertArrayEquals(byteArrayOf(1), frame.jpeg())
        assertEquals(0, completed)
        frame.confirmAccepted()
        frame.close()
        frame.close()
        assertEquals(1, completed)
        assertEquals(1, source.accepted)
        assertEquals(1, source.closed)
    }

    @Test fun stopDuringCaptureWaitsForThatRequestWithoutAddingAnother() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val periodic = Result()
        capture.capture(2_000, emptyArray(), periodic)
        var completed = 0
        capture.stop(3_000, emptyArray(), Result(), Runnable { completed++ })
        source.deliver(0)
        assertEquals(0, completed)
        assertEquals(1, source.callbacks.size)
        periodic.frames.single().close()
        assertEquals(1, completed)
        assertEquals("失败释放不能冒充业务接受", 0, source.accepted)
    }

    @Test fun noNewImageContinuesOnlyOnceEvenWithLateDuplicateCallbacks() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var completed = 0
        capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
        source.callbacks[0].onSuccess(null)
        source.callbacks[0].onSuccess(null)
        source.callbacks[0].onError("迟到错误")
        assertEquals(1, result.empty)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, completed)
    }

    @Test fun captureFailureContinuesWithoutConfirmingAnImage() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var completed = 0
        capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
        source.callbacks[0].onError("投影已撤销")
        source.deliver(0)
        assertEquals(listOf("投影已撤销"), result.errors)
        assertTrue(result.frames.isEmpty())
        assertEquals(0, source.accepted)
        assertEquals(1, completed)
    }

    @Test fun synchronousSourceFailureAlsoReleasesTheStopBarrier() {
        val source = Source().apply { throwOnCapture = true }
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var completed = 0
        capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
        assertEquals(listOf("SDK截图请求无法执行"), result.errors)
        assertEquals(1, completed)
    }

    @Test fun closeReleasesPendingCaptureAndOldCallbacksCannotAffectANewScene() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var completed = 0
        capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
        capture.close()
        capture.close()
        val freshSource = Source()
        val fresh = PresentationCapture(freshSource, 5_000)
        val freshResult = Result()
        fresh.capture(6_000, emptyArray(), freshResult)
        source.deliver(0)
        source.callbacks[0].onError("迟到错误")
        assertEquals(1, result.errors.size)
        assertTrue(result.frames.isEmpty())
        assertEquals(1, completed)
        assertEquals(1, source.closed)
        freshSource.deliver(0)
        assertEquals(1_000L, freshResult.frames.single().offsetMs())
        freshResult.frames.single().close()
        fresh.close()
    }

    @Test fun closeFailureStillReleasesDeliveredFrameBarrierAndLateAckIsHarmless() {
        val source = Source().apply { throwOnClose = true }
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        capture.capture(2_000, emptyArray(), result)
        source.deliver(0)
        var completed = 0
        capture.stop(3_000, emptyArray(), Result(), Runnable { completed++ })
        assertThrows(IllegalStateException::class.java) { capture.close() }
        result.frames.single().close()
        capture.close()
        assertEquals(1, completed)
        assertEquals(1, source.closed)
        assertEquals(0, source.accepted)
    }

    @Test fun repeatedStopDoesNotReplaceTheOriginalBarrierOrRequestMoreImages() {
        val source = Source()
        val capture = PresentationCapture(source, 1_000)
        val result = Result()
        var first = 0
        var duplicate = 0
        capture.stop(2_000, emptyArray(), result, Runnable { first++ })
        capture.stop(3_000, emptyArray(), Result(), Runnable { duplicate++ })
        source.callbacks[0].onSuccess(null)
        capture.stop(4_000, emptyArray(), Result(), Runnable { duplicate++ })
        assertEquals(1, source.callbacks.size)
        assertEquals(1, first)
        assertEquals(0, duplicate)
    }

    @Test fun terminalCallbacksCanCloseWithoutReceivingAnotherErrorOrRepeatingStop() {
        for (failure in listOf(false, true)) {
            val source = Source()
            val capture = PresentationCapture(source, 1_000)
            var success = 0
            var errors = 0
            var completed = 0
            val result = object : PresentationCapture.Callback {
                override fun onSuccess(frame: PresentationCapture.Frame?) {
                    assertNull(frame)
                    success++
                    capture.close()
                }
                override fun onError(message: String) {
                    errors++
                    capture.close()
                }
            }
            capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
            if (failure) source.callbacks[0].onError("original failure")
            else source.callbacks[0].onSuccess(null)
            assertEquals(if (failure) 0 else 1, success)
            assertEquals(if (failure) 1 else 0, errors)
            assertEquals(1, completed)
            assertEquals(1, source.closed)
        }
    }

    @Test fun synchronousFailureCallbackCanCloseWithoutASecondError() {
        val source = Source().apply { throwOnCapture = true }
        val capture = PresentationCapture(source, 1_000)
        var errors = 0
        var completed = 0
        val result = object : PresentationCapture.Callback {
            override fun onSuccess(frame: PresentationCapture.Frame?) = fail("source failed")
            override fun onError(message: String) {
                errors++
                capture.close()
            }
        }
        capture.stop(2_000, emptyArray(), result, Runnable { completed++ })
        assertEquals(1, errors)
        assertEquals(1, completed)
        assertEquals(1, source.closed)
    }

    private class Result : PresentationCapture.Callback {
        val frames = mutableListOf<PresentationCapture.Frame>()
        val errors = mutableListOf<String>()
        var empty = 0
        override fun onSuccess(frame: PresentationCapture.Frame?) {
            if (frame == null) empty++ else frames.add(frame)
        }
        override fun onError(message: String) { errors.add(message) }
    }

    private class Source : ScreenCaptureSource {
        val callbacks = mutableListOf<ScreenCaptureSource.Callback>()
        val snapshots = mutableListOf<ByteArray>()
        var screen = byteArrayOf(1)
        var accepted = 0
        var closed = 0
        var throwOnCapture = false
        var throwOnClose = false
        override fun mode() = "TEST"
        override fun supportsWindowExclusion() = false
        override fun capture(excludedWindowTokens: Array<IBinder?>, callback: ScreenCaptureSource.Callback) {
            if (throwOnCapture) error("capture failed")
            snapshots.add(screen.copyOf())
            callbacks.add(callback)
        }
        fun deliver(index: Int) {
            callbacks[index].onSuccess(ScreenCaptureSource.Image(snapshots[index]) { accepted++ })
        }
        override fun close() {
            closed++
            if (throwOnClose) error("close failed")
        }
    }
}
