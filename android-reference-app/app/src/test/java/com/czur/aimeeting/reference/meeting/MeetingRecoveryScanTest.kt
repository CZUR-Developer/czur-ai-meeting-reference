package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.CapturingMeeting
import com.czur.aimeeting.client.ClientIdentity
import com.czur.aimeeting.client.FinalizationResult
import com.czur.aimeeting.client.MeetingArtifactPublisher
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.MeetingListener
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingSession
import com.czur.aimeeting.client.RecoveryResult
import com.czur.aimeeting.client.StoppedMeetingCheckpoint
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.server.ReferenceLoginSession
import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 启动扫描的两条恢复路径：READY 残留只清本地，待收尾场次在 Service 释放所有权前不重复续办。
 * 使用真实参考 Store 和应用私有目录，重新创建 Store 即等同进程重启后重新装配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeetingRecoveryScanTest {
    private val meetingId = "mtg_scan"
    private lateinit var context: Context
    private lateinit var recoveryStore: ReferenceMeetingRecoveryStore
    private lateinit var stoppedStore: ReferenceStoppedMeetingStore
    private lateinit var audioFile: File
    private lateinit var journalFile: File
    private val failed = mutableListOf<String>()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "meeting-active").deleteRecursively()
        File(context.filesDir, "meeting-stopped").deleteRecursively()
        recoveryStore = ReferenceMeetingRecoveryStore(context)
        stoppedStore = ReferenceStoppedMeetingStore(context)
        audioFile = File(context.filesDir, "meeting-audio/$meetingId.pcm").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(32_000))
        }
        journalFile = File(context.filesDir, "meeting-active/$meetingId.jsonl").apply {
            parentFile!!.mkdirs()
            writeText("{\"segmentId\":\"a\",\"revision\":1,\"startMs\":0,\"language\":\"zh\",\"text\":\"第一段\"}\n")
        }
        failed.clear()
    }

    @Test fun readyResidueIsCleanedAfterRestartWithoutAnotherFinalization() {
        saveRecovery()
        saveStopCheckpoint(ready = true)
        // 重新创建 Store 只读磁盘，等价于进程退出后重新装配。
        val restartedRecovery = ReferenceMeetingRecoveryStore(context)
        val restartedStopped = ReferenceStoppedMeetingStore(context)
        val client = FakeClient()
        val scan = MeetingRecoveryScan(restartedRecovery, restartedStopped,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val report = scan.run()

        assertEquals(listOf(meetingId), report.cleaned)
        assertTrue(report.resumed.isEmpty())
        assertTrue(report.recovered.isEmpty())
        assertTrue("清理失败应上报单场", report.failed.isEmpty())
        assertEquals("READY 残留清理不应创建客户端", 0, client.created.get())
        assertTrue(client.resumed.isEmpty())
        assertTrue(client.recovered.isEmpty())
        assertTrue(restartedRecovery.read(meetingId).isEmpty)
        assertTrue(restartedStopped.read(meetingId).isEmpty)
        assertFalse("字幕日志也应删除", journalFile.exists())
        // 收敛后再次扫描没有现场可处理，也不报错。
        val again = scan.run()
        assertTrue(again.cleaned.isEmpty() && again.resumed.isEmpty() &&
            again.recovered.isEmpty() && again.failed.isEmpty())
    }

    @Test fun cleanupFailureKeepsReadyRecordAndConvergesOnNextScan() {
        saveRecovery()
        saveStopCheckpoint(ready = true)
        // 用一个非空目录占住日志路径，让字幕日志删除失败。
        assertTrue(journalFile.delete())
        assertTrue(journalFile.mkdir())
        File(journalFile, "keep").writeText("x")
        val client = FakeClient()
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val blocked = scan.run()

        assertEquals(listOf(meetingId), blocked.failed)
        assertEquals("本地清理失败不是续办失败，不回调调用方", 0, failed.size)
        assertEquals(0, client.created.get())
        assertTrue("失败时保留 READY 停止记录", stoppedStore.read(meetingId).isPresent)
        assertTrue("失败时保留恢复现场", recoveryStore.read(meetingId).isPresent)

        // 障碍解除后下一次扫描重试并收敛。
        File(journalFile, "keep").delete()
        assertTrue(journalFile.delete())
        val retried = scan.run()
        assertEquals(listOf(meetingId), retried.cleaned)
        assertTrue(retried.failed.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isEmpty)
        assertTrue(recoveryStore.read(meetingId).isEmpty)
    }

    @Test fun serviceOwnedMeetingIsSkippedUntilFinalizationEnds() {
        saveRecovery()
        saveStopCheckpoint(ready = false)
        val client = FakeClient()
        var owned = setOf(meetingId)
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { owned }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val skipped = scan.run()

        assertTrue(skipped.cleaned.isEmpty() && skipped.resumed.isEmpty() &&
            skipped.recovered.isEmpty() && skipped.failed.isEmpty())
        assertEquals("收尾中的同一场不得另建客户端", 0, client.created.get())
        assertTrue(client.resumed.isEmpty())
        assertTrue(client.recovered.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isPresent)
        assertTrue(recoveryStore.read(meetingId).isPresent)

        // Service 收尾结束、释放所有权后，扫描才允许续办同一场。
        owned = emptySet()
        val resumed = scan.run()

        assertEquals(listOf(meetingId), resumed.resumed)
        assertEquals(1, client.created.get())
        assertEquals(listOf(meetingId), client.resumed)
        assertTrue("已有停止记录时不得再走现场恢复", client.recovered.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isEmpty)
        assertTrue(recoveryStore.read(meetingId).isEmpty)
        assertFalse(journalFile.exists())
        assertTrue(failed.isEmpty())

        // 现场已清空，再扫不会生成第二条续办链。
        scan.run()
        assertEquals(1, client.resumed.size)
        assertEquals(1, client.created.get())
    }

    private fun saveRecovery() {
        recoveryStore.save(CapturingMeeting(meetingId, "https://service.example.test",
            ClientIdentity.reference(), audioFile, journalFile, 1L, "zh-CN", true, true, true))
    }

    private fun saveStopCheckpoint(ready: Boolean) {
        val audio = SealedAudioArtifact(meetingId, audioFile, SealedAudioArtifact.Format.PCM16,
            audioFile.length(), audioFile.length())
        val context = MeetingArtifactPublisher.Context(meetingId, audio, audioFile.length() / 32,
            MeetingClientConfig.reference("https://service.example.test", "fixture-token"))
        var checkpoint = StoppedMeetingCheckpoint.pending(context)
        if (ready) checkpoint = checkpoint.stopAccepted(1_234L).ready()
        stoppedStore.save(checkpoint)
    }

    /** 只记录扫描真正发起的续办入口；两个入口都同步成功，避免测试等待。 */
    private class FakeClient : MeetingClient {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val resumed = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        override fun resumeFinalization(checkpointId: String,
            callback: MeetingClient.Callback<FinalizationResult>) {
            resumed.add(checkpointId)
            callback.onSuccess(FinalizationResult(checkpointId, null))
        }
        override fun recoverInterrupted(meetingId: String, callback: MeetingClient.Callback<RecoveryResult>) {
            recovered.add(meetingId)
            callback.onSuccess(RecoveryResult(meetingId, null))
        }
        override fun discover(callback: MeetingClient.Callback<com.czur.aimeeting.client.MeetingDiscovery>) =
            throw AssertionError("扫描不应发现部署")
        override fun refreshIdentity(callback: MeetingClient.Callback<IdentityResult>) =
            throw AssertionError("扫描不应刷新身份")
        override fun login(username: String, password: String,
            callback: MeetingClient.Callback<ReferenceLoginSession>) =
            throw AssertionError("扫描不应登录")
        override fun start(options: MeetingOptions, listener: MeetingListener): MeetingSession =
            throw AssertionError("扫描不应新建会议")
        override fun close() = Unit
    }
}
