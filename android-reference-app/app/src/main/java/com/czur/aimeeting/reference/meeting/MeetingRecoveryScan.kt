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
 * 避免同一场生成两条 genTime；Service 释放所有权后的下一次扫描才允许续办。已 READY 的
 * 停止记录只做本地清理：先删字幕日志与现场记录，最后删停止记录；任何一步失败都保留
 * 停止记录，下一次扫描重试，让本地状态最终收敛。READY 清理不联网，未登录也能完成。</p>
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
                        // 服务端早已收口，只剩本地残留；清理不需要客户端也不要求登录。
                        if (cleanupReady(meetingId)) cleaned.add(meetingId) else failed.add(meetingId)
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
        if (!resumed) return false
        // 停止记录此刻已是 READY；清理失败时保留记录，下一次扫描走 READY 分支重试。
        if (!cleanupReady(meetingId)) Log.w(TAG, "停止收尾完成但本地记录未清完 meetingId=$meetingId")
        return true
    }

    /** 只有恢复现场的场次按现场事实续办；成功后停止记录已是 READY，同样只清本地残留。 */
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
