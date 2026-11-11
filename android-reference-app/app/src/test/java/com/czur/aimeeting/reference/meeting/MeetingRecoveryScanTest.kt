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
        // 旧记录没有会前语言快照：等价于“不需要补报纪要”，按原行为只清本地残留。
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

    /**
     * 评审场景：服务端已 READY，但停止记录里的纪要语言还没上报（上一次网络失败）。
     * 扫描必须保留现场并继续续办补报，不能按“只清本地残留”删掉唯一的重试入口；
     * 上报落盘后，下一轮扫描读到“已上报”才清理。
     */
    @Test fun readyWithUnreportedSummaryIsResumedAndKeptUntilTheNextScan() {
        saveRecovery()
        saveStopCheckpoint(ready = true, summaryLanguages = listOf("zh-CN"), summaryReported = false)
        // 模拟协调器补报成功并把“已上报”写回停止记录。
        val client = FakeClient(onResume = { markSummaryReported() })
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val first = scan.run()

        assertEquals("READY 但欠上报时必须续办", listOf(meetingId), first.resumed)
        assertTrue("本轮不得清理还没确认上报的记录", first.cleaned.isEmpty())
        assertEquals(listOf(meetingId), client.resumed)
        assertTrue(stoppedStore.read(meetingId).isPresent)
        assertTrue(recoveryStore.read(meetingId).isPresent)
        assertTrue("字幕日志也要留到清理那一轮", journalFile.exists())
        assertTrue(failed.isEmpty())

        val second = scan.run()

        assertEquals("已上报的下一轮才清理", listOf(meetingId), second.cleaned)
        assertTrue(second.resumed.isEmpty())
        assertTrue(second.failed.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isEmpty)
        assertTrue(recoveryStore.read(meetingId).isEmpty)
        assertFalse(journalFile.exists())
        assertEquals("补报只请求一次", 1, client.resumed.size)
    }

    /** 补报仍失败：记录保留，下一轮扫描仍有续办入口，不会一次网络失败就永久丢纪要。 */
    @Test fun failedSummaryResumeKeepsReadyRecordForTheNextScannerRound() {
        saveRecovery()
        saveStopCheckpoint(ready = true, summaryLanguages = listOf("zh-CN", "en-US"), summaryReported = false)
        // 回调成功但不落盘“已上报”，等同服务端请求仍失败、记录停在未上报。
        val client = FakeClient()
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val first = scan.run()
        assertEquals(listOf(meetingId), first.resumed)
        assertTrue(first.cleaned.isEmpty())
        assertFalse(stoppedStore.read(meetingId).orElseThrow().summaryReported())
        assertEquals(1, client.created.get())

        val second = scan.run()
        assertEquals("仍欠上报时每一轮都要有续办入口", listOf(meetingId), second.resumed)
        assertTrue(second.cleaned.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isPresent)
        assertTrue(recoveryStore.read(meetingId).isPresent)
        assertTrue(journalFile.exists())
        assertEquals(2, client.resumed.size)
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

    /**
     * 评审场景：会议只有采音现场，走 recoverInterrupted 收口；服务端已 READY，但纪要
     * 上报失败，协调器留下的停止记录是“READY + 未上报”。恢复成功回调后同样要复核，
     * 不能无条件清理，否则唯一可补报的记录被删掉，这场永久缺纪要。
     */
    @Test fun recoveredSceneWithUnreportedSummaryIsKeptAndResumedOnNextScan() {
        saveRecovery()
        val client = FakeClient(
            onResume = { markSummaryReported() },
            onRecover = {
                // 现场恢复成功后的停止记录：服务端 READY，纪要仍欠一次上报。
                saveStopCheckpoint(ready = true, summaryLanguages = listOf("zh-CN"),
                    summaryReported = false)
            })
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val recovered = scan.run()

        assertEquals("只有现场时走恢复入口", listOf(meetingId), recovered.recovered)
        assertTrue("恢复后仍欠上报时不得清理", recovered.cleaned.isEmpty())
        assertTrue(recovered.resumed.isEmpty())
        assertTrue("停止记录是补报的唯一入口，必须保留", stoppedStore.read(meetingId).isPresent)
        assertTrue(recoveryStore.read(meetingId).isPresent)
        assertTrue(journalFile.exists())
        assertTrue(failed.isEmpty())

        // 下一轮扫描读到 READY + 未上报：补报成功并落盘，本轮继续保留记录。
        val resumed = scan.run()

        assertEquals("下一轮由 READY 分支补报", listOf(meetingId), resumed.resumed)
        assertTrue("补报成功也不在本轮清理", resumed.cleaned.isEmpty())
        assertTrue(resumed.recovered.isEmpty())
        assertTrue(stoppedStore.read(meetingId).orElseThrow().summaryReported())
        assertTrue(recoveryStore.read(meetingId).isPresent)
        assertTrue(journalFile.exists())
        assertTrue(failed.isEmpty())

        // 观察到“已上报”的下一轮才清理全部本地残留。
        val cleaned = scan.run()

        assertEquals("确认已上报后再下一轮才清理", listOf(meetingId), cleaned.cleaned)
        assertTrue(cleaned.resumed.isEmpty() && cleaned.recovered.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isEmpty)
        assertTrue(recoveryStore.read(meetingId).isEmpty)
        assertFalse(journalFile.exists())
        assertTrue(failed.isEmpty())
        assertEquals("恢复只请求一次、补报只请求一次", 1, client.recovered.size)
        assertEquals(1, client.resumed.size)
        assertEquals("清理轮不创建客户端", 2, client.created.get())
    }

    /** 现场恢复完成时纪要已上报：不需要补报，本轮直接清理，不多留一轮。 */
    @Test fun recoveredSceneWithReportedSummaryIsCleanedInTheSameRound() {
        saveRecovery()
        val client = FakeClient(onRecover = {
            saveStopCheckpoint(ready = true, summaryLanguages = listOf("zh-CN"),
                summaryReported = true)
        })
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val report = scan.run()

        assertEquals(listOf(meetingId), report.recovered)
        assertTrue(report.resumed.isEmpty())
        assertTrue(stoppedStore.read(meetingId).isEmpty)
        assertTrue(recoveryStore.read(meetingId).isEmpty)
        assertFalse(journalFile.exists())
        assertTrue(failed.isEmpty())
        assertTrue(client.resumed.isEmpty())
    }

    /**
     * 恢复成功后停止记录读不出来：无法证明已经上报，按“仍欠上报”保守保留现场，
     * 宁可让下一次扫描继续尝试，也不删掉可能唯一的重试入口。
     */
    @Test fun unreadableStopRecordAfterRecoveryKeepsScene() {
        saveRecovery()
        val corrupt = File(context.filesDir, "meeting-stopped/$meetingId.json")
        val client = FakeClient(onRecover = {
            corrupt.parentFile!!.mkdirs()
            corrupt.writeText("{ 不是合法停止记录")
        })
        val scan = MeetingRecoveryScan(recoveryStore, stoppedStore,
            { emptySet() }, { client.also { client.created.incrementAndGet() } }, { failed += it })

        val report = scan.run()

        assertEquals(listOf(meetingId), report.recovered)
        assertTrue("读不出上报状态时不得清理", report.cleaned.isEmpty())
        assertTrue(recoveryStore.read(meetingId).isPresent)
        assertTrue(journalFile.exists())
        assertTrue("读取失败不是恢复失败，本轮不报失败", report.failed.isEmpty())
        assertTrue(failed.isEmpty())
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

    private fun saveStopCheckpoint(ready: Boolean, summaryLanguages: List<String>? = null,
        summaryReported: Boolean = false) {
        val audio = SealedAudioArtifact(meetingId, audioFile, SealedAudioArtifact.Format.PCM16,
            audioFile.length(), audioFile.length())
        val context = MeetingArtifactPublisher.Context(meetingId, audio, audioFile.length() / 32,
            MeetingClientConfig.reference("https://service.example.test", "fixture-token"))
        var checkpoint = StoppedMeetingCheckpoint.pending(context).withSummaryLanguages(summaryLanguages)
        if (ready) checkpoint = checkpoint.stopAccepted(1_234L).ready()
        if (summaryReported) checkpoint = checkpoint.withSummaryReported()
        stoppedStore.save(checkpoint)
    }

    /** 模拟协调器把“纪要语言已上报”写回停止记录，供下一轮扫描读到。 */
    private fun markSummaryReported() {
        stoppedStore.save(stoppedStore.read(meetingId).orElseThrow().withSummaryReported())
    }

    /** 只记录扫描真正发起的续办入口；两个入口都同步成功，避免测试等待。 */
    private class FakeClient(
        private val onResume: (String) -> Unit = {},
        private val onRecover: (String) -> Unit = {},
    ) : MeetingClient {
        val created = java.util.concurrent.atomic.AtomicInteger()
        val resumed = mutableListOf<String>()
        val recovered = mutableListOf<String>()
        override fun resumeFinalization(checkpointId: String,
            callback: MeetingClient.Callback<FinalizationResult>) {
            resumed.add(checkpointId)
            onResume(checkpointId)
            callback.onSuccess(FinalizationResult(checkpointId, null))
        }
        override fun recoverInterrupted(meetingId: String, callback: MeetingClient.Callback<RecoveryResult>) {
            recovered.add(meetingId)
            onRecover(meetingId)
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
