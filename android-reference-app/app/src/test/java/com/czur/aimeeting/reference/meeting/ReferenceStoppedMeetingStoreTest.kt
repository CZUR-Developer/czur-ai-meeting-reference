package com.czur.aimeeting.reference.meeting

import android.content.Context
import com.czur.aimeeting.client.MeetingArtifactPublisher
import com.czur.aimeeting.client.MeetingClientConfig
import com.czur.aimeeting.client.StoppedMeetingCheckpoint
import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 参考停止记录磁盘格式：交付策略必须持久化，旧记录保持主动交付。 */
@RunWith(RobolectricTestRunner::class)
class ReferenceStoppedMeetingStoreTest {

    @Test fun deliveryPolicySurvivesDiskRoundTripAndLegacyRecordDefaultsToDelivery() {
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "meeting-stopped")
        directory.deleteRecursively()
        val pcm = File(context.cacheDir, "mtg_stopped.pcm").apply { writeBytes(ByteArray(3200)) }
        val audio = SealedAudioArtifact("mtg_stopped", pcm,
            SealedAudioArtifact.Format.PCM16, pcm.length(), pcm.length())
        val store = ReferenceStoppedMeetingStore(context)
        val saved = store.save(StoppedMeetingCheckpoint.pending(
            MeetingArtifactPublisher.Context("mtg_stopped", audio, 100L,
                MeetingClientConfig.reference("https://service.example.test", "fixture-token")),
            deliversResult = false))
        assertFalse(saved.deliversResult())
        assertFalse(store.read("mtg_stopped").orElseThrow().deliversResult())

        // 旧版本记录没有该字段：按主动读取结果续办，不能默认成不交付。
        val legacyFile = File(directory, "mtg_stopped.json")
        val legacy = JSONObject(legacyFile.readText())
        assertEquals(false, legacy.getBoolean("deliversResult"))
        legacy.remove("deliversResult")
        legacyFile.writeText(legacy.toString())
        assertTrue(store.read("mtg_stopped").orElseThrow().deliversResult())
    }

    /** 发布成功、上报纪要语言前退出：语言快照与上报标记必须能跨进程读回。 */
    @Test fun summarySnapshotAndReportedFlagSurviveDiskAndLegacyRecordDefaultsToNull() {
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "meeting-stopped")
        directory.deleteRecursively()
        val pcm = File(context.cacheDir, "mtg_summary.pcm").apply { writeBytes(ByteArray(3200)) }
        val audio = SealedAudioArtifact("mtg_summary", pcm,
            SealedAudioArtifact.Format.PCM16, pcm.length(), pcm.length())
        val store = ReferenceStoppedMeetingStore(context)
        val pending = StoppedMeetingCheckpoint.pending(
            MeetingArtifactPublisher.Context("mtg_summary", audio, 100L,
                MeetingClientConfig.reference("https://service.example.test", "fixture-token")))
            .withSummaryLanguages(listOf("zh-CN", "en-US"))
        store.save(pending)
        val saved = store.read("mtg_summary").orElseThrow()
        assertEquals(listOf("zh-CN", "en-US"), saved.summaryLanguages())
        assertFalse(saved.summaryReported())
        store.save(saved.withSummaryReported())
        assertTrue(store.read("mtg_summary").orElseThrow().summaryReported())

        // 旧版本记录没有这两个字段：按 null／false 读回，续办既不补发也不报错。
        val legacyFile = File(directory, "mtg_summary.json")
        val legacy = JSONObject(legacyFile.readText())
        legacy.remove("summaryLanguages")
        legacy.remove("summaryReported")
        legacyFile.writeText(legacy.toString())
        val legacyRead = store.read("mtg_summary").orElseThrow()
        assertNull(legacyRead.summaryLanguages())
        assertFalse(legacyRead.summaryReported())
    }
}
