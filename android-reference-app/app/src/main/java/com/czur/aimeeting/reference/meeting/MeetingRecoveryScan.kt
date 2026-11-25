package com.czur.aimeeting.reference.meeting

import android.util.Log
import com.czur.aimeeting.client.FinalizationResult
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.RecoveryResult
import com.czur.aimeeting.client.StoppedMeetingCheckpoint
import java.util.concurrent.CountDownLatch

/**
 * 页面启动时的原场扫描：按停止记录区分待收尾、已 READY 和只有恢复现场三类。
 *
 * <p>同一场被本进程 Service 持有时（会议进行中或正在收尾）整场跳过，扫描不另建客户端，
 * 避免同一场生成两条 genTime；Service 释放所有权后的下一次扫描才允许续办。已 READY 且
 * 纪要语言已上报（或是没有语言快照的旧记录）的停止记录只做本地清理：先删字幕日志与
 * 现场记录，最后删停止记录；任何一步失败都保留停止记录，下一次扫描重试，让本地状态
 * 最终收敛。READY 清理不联网，未登录也能完成。</p>
 *
 * <p>“READY 但纪要未上报”（{@code summaryReported=false} 且语言快照非空）的记录是上一个
 * 版本会直接删掉的漏洞：服务端已 READY、宿主却还欠一次纪要语言上报，一次网络失败就会让
 * 该场永久缺纪要。这类记录必须走 {@code resumeFinalization} 补报，且本轮不清理：删除只由
 * 观察到“已上报”的下一轮扫描执行，避免同一轮里既改状态又删证据。</p>
 *
 * <p>同一复核也适用于只有采音现场、走 {@code recoverInterrupted} 的场次：回调成功后停止
 * 记录可能正是“READY 但纪要未上报”，无条件清理会把唯一可补报的记录一起删掉。只有确认
 * 不需要补报（或旧记录没有语言快照）才在本轮清理。</p>
 */
internal class MeetingRecoveryScan(
    private val recoveryStore: ReferenceMeetingRecoveryStore,
    private val stoppedStore: ReferenceStoppedMeetingStore,
    private val ownedMeetingIds: () -> Set<String>,
    private val newClient: () -> MeetingClient,
    private val onFailure: (String) -> Unit = {},
) {
    /** 扫描结果只用于日志和测试，不保存会议内容、凭据或用户文案。 */
    class Report(
        val cleaned: List<String>,
        val resumed: List<String>,
        val recovered: List<String>,
        val failed: List<String>,
    )

    fun run(): Report {
        val stoppedIds = try { stoppedStore.storedIds() }
        catch (failure: Exception) {
            Log.w(TAG, "列举停止记录失败 class=${failure.javaClass.simpleName}")
            emptyList()
        }
        val recoveryIds = try { recoveryStore.pendingIds() }
        catch (failure: Exception) {
            Log.w(TAG, "列举原场现场失败 class=${failure.javaClass.simpleName}")
            emptyList()
        }
        val owned = ownedMeetingIds()
        // 恢复现场也可能已有停止记录，统一按会议编号去重后逐场判断。
        val candidates = (stoppedIds + recoveryIds).filter { it !in owned }.distinct().sorted()
        Log.i(TAG, "原场扫描 candidates=${candidates.size} stopped=${stoppedIds.size} " +
            "recovery=${recoveryIds.size} owned=${owned.size}")
        val cleaned = ArrayList<String>()
        val resumed = ArrayList<String>()
        val recovered = ArrayList<String>()
        val failed = ArrayList<String>()
        var client: MeetingClient? = null
        try {
            for (meetingId in candidates) {
                // Service 可能在扫描期间开始或结束收尾；逐场重新核对所有权。
                if (meetingId in ownedMeetingIds()) {
                    Log.i(TAG, "跳过本进程正在收尾的会议 meetingId=$meetingId")
                    continue
                }
                val checkpoint = try { stoppedStore.read(meetingId).orElse(null) }
                catch (failure: Exception) {
                    // 停止记录读不出来时无法判断是否已经收口，不贸然再走恢复。
                    Log.w(TAG, "读取停止记录失败，保留现场待下次扫描 meetingId=$meetingId " +
                        "class=${failure.javaClass.simpleName}")
                    failed.add(meetingId); onFailure(meetingId); continue
                }
                when {
                    checkpoint == null -> {
                        val activeClient = client ?: newClient().also { client = it }
                        if (recoverInterrupted(meetingId, activeClient)) recovered.add(meetingId)
                        else { failed.add(meetingId); onFailure(meetingId) }
                    }
                    checkpoint.phase() == StoppedMeetingCheckpoint.Phase.READY -> {
                        if (needsSummaryResume(checkpoint)) {
                            // 服务端已 READY，但本场纪要语言还没上报：续办补报，本轮保留记录。
                            Log.i(TAG, "已READY但纪要未上报，继续续办 meetingId=$meetingId")
                            val activeClient = client ?: newClient().also { client = it }
                            if (resumeSummary(meetingId, activeClient)) resumed.add(meetingId)
                            else { failed.add(meetingId); onFailure(meetingId) }
                        } else {
                            // 已上报或旧记录没有语言快照：只剩本地残留；清理不需要客户端也不要求登录。
                            if (cleanupReady(meetingId)) cleaned.add(meetingId) else failed.add(meetingId)
                        }
                    }
                    else -> {
                        val activeClient = client ?: newClient().also { client = it }
                        if (resumeStop(meetingId, activeClient)) resumed.add(meetingId)
                        else { failed.add(meetingId); onFailure(meetingId) }
                    }
                }
            }
        } finally {
            client?.close()
        }
        Log.i(TAG, "原场扫描完成 cleaned=${cleaned.size} resumed=${resumed.size} " +
            "recovered=${recovered.size} failed=${failed.size}")
        return Report(java.util.List.copyOf(cleaned), java.util.List.copyOf(resumed),
            java.util.List.copyOf(recovered), java.util.List.copyOf(failed))
    }

    /** 只推进已保存的发布／Stop／READY，不新建场次。成功后再清本地记录。 */
    private fun resumeStop(meetingId: String, client: MeetingClient): Boolean {
        if (!requestResumeFinalization(meetingId, client)) return false
        // 停止记录此刻已是 READY；纪要若仍未上报，保留记录让下一次扫描走 READY 分支重试，
        // 否则本轮清理会连同补报现场一起删掉，一次网络失败就再也补不回来。
        if (needsSummaryResume(meetingId)) {
            Log.i(TAG, "停止收尾完成但纪要仍未上报，保留记录等待下次扫描 meetingId=$meetingId")
            return true
        }
        if (!cleanupReady(meetingId)) Log.w(TAG, "停止收尾完成但本地记录未清完 meetingId=$meetingId")
        return true
    }

    /**
     * READY 但纪要语言未上报的场次：继续用同一停止记录补报，不再发布／Stop／读清单。
     * 本轮不删任何本地记录：上报成功已落盘，下一轮扫描读到“已上报”才按原规则清理；
     * 上报仍失败则下一轮继续续办，重试入口不会消失。
     */
    private fun resumeSummary(meetingId: String, client: MeetingClient): Boolean {
        if (!requestResumeFinalization(meetingId, client)) return false
        Log.i(TAG, "纪要补报续办完成，保留记录等待下次扫描确认后清理 meetingId=$meetingId")
        return true
    }

    /** 调一次已保存的续办入口并等它回调；不负责清理本地记录。 */
    private fun requestResumeFinalization(meetingId: String, client: MeetingClient): Boolean {
        val finished = CountDownLatch(1)
        var resumed = false
        Log.i(TAG, "原场停止收尾请求 meetingId=$meetingId")
        try {
            client.resumeFinalization(meetingId, object : MeetingClient.Callback<FinalizationResult> {
                override fun onSuccess(result: FinalizationResult) {
                    resumed = true
                    Log.i(TAG, "原场停止收尾续办完成 meetingId=$meetingId delivered=${result.delivered()}")
                    finished.countDown()
                }
                override fun onError(error: MeetingError) {
                    Log.w(TAG, "原场停止收尾续办未完成 meetingId=$meetingId code=${error.code}")
                    finished.countDown()
                }
            })
            finished.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (failure: RuntimeException) {
            Log.w(TAG, "原场停止收尾请求异常 meetingId=$meetingId class=${failure.javaClass.simpleName}")
            return false
        }
        return resumed
    }

    /**
     * 停止记录还欠着纪要语言上报：带会前语言快照且尚未上报。
     * 旧记录没有快照（null）等价于“本场不要纪要或不需要补发”，按原行为直接清理；
     * 读不出记录时保守按“仍需上报”处理，宁可留到下一轮也不删掉重试入口。
     */
    private fun needsSummaryResume(checkpoint: StoppedMeetingCheckpoint): Boolean =
        checkpoint.summaryLanguages() != null && !checkpoint.summaryReported()

    private fun needsSummaryResume(meetingId: String): Boolean = try {
        stoppedStore.read(meetingId).orElse(null)?.let(::needsSummaryResume) ?: false
    } catch (failure: Exception) {
        Log.w(TAG, "复核纪要上报状态失败，保留记录待下次扫描 meetingId=$meetingId " +
            "class=${failure.javaClass.simpleName}")
        true
    }

    /**
     * 只有恢复现场的场次按现场事实续办；成功后停止记录已是 READY，同样只清本地残留。
     * 与停止续办一样，回调成功后必须复核纪要是否仍欠上报：现场恢复会新建停止记录，
     * 纪要上报失败时记录停在“READY 但未上报”，此时清理会把唯一的重试入口连同现场删掉，
     * 下一次扫描再也补不上纪要；仍欠上报时保留记录（读取记录失败同样保守保留）。
     */
    private fun recoverInterrupted(meetingId: String, client: MeetingClient): Boolean {
        val finished = CountDownLatch(1)
        var recovered = false
        try {
            client.recoverInterrupted(meetingId, object : MeetingClient.Callback<RecoveryResult> {
                override fun onSuccess(result: RecoveryResult) {
                    recovered = true
                    finished.countDown()
                }
                override fun onError(error: MeetingError) {
                    Log.w(TAG, "原场现场续办未完成 meetingId=$meetingId code=${error.code}")
                    finished.countDown()
                }
            })
            finished.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (failure: RuntimeException) {
            Log.w(TAG, "原场现场续办请求异常 meetingId=$meetingId class=${failure.javaClass.simpleName}")
            return false
        }
        if (!recovered) return false
        if (needsSummaryResume(meetingId)) {
            Log.i(TAG, "原场续办完成但纪要仍未上报，保留记录等待下次扫描 meetingId=$meetingId")
            return true
        }
        if (!cleanupReady(meetingId)) Log.w(TAG, "原场续办完成但本地记录未清完 meetingId=$meetingId")
        return true
    }

    /**
     * READY 后的本地清理：字幕日志、恢复现场、停止记录依次删除。
     * 删除顺序保证任何一步失败时停止记录仍在，下一次扫描还能重试；READY 记录重复清理幂等。
     */
    private fun cleanupReady(meetingId: String): Boolean {
        val record = try { recoveryStore.read(meetingId).orElse(null) }
        catch (failure: Exception) {
            Log.w(TAG, "读取待清理现场失败 meetingId=$meetingId class=${failure.javaClass.simpleName}")
            return false
        }
        if (record != null) {
            val journal = record.journalFile()
            if (journal != null && journal.exists() && !journal.delete()) {
                Log.w(TAG, "已READY场次字幕日志删除失败 meetingId=$meetingId")
                return false
            }
            try { recoveryStore.delete(meetingId) }
            catch (failure: Exception) {
                Log.w(TAG, "已READY场次现场记录删除失败 meetingId=$meetingId " +
                    "class=${failure.javaClass.simpleName}")
                return false
            }
        }
        try { stoppedStore.delete(meetingId) }
        catch (failure: Exception) {
            Log.w(TAG, "已READY场次停止记录删除失败 meetingId=$meetingId " +
                "class=${failure.javaClass.simpleName}")
            return false
        }
        Log.i(TAG, "已READY场次残留现场清理完成 meetingId=$meetingId")
        return true
    }

    private companion object {
        private const val TAG = "MeetingRecoveryScan"
    }
}
