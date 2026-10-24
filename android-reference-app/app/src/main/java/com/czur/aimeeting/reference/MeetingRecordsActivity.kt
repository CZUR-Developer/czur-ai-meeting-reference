package com.czur.aimeeting.reference

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.czur.aimeeting.client.MeetingClient
import com.czur.aimeeting.client.MeetingDiscovery
import com.czur.aimeeting.client.MeetingError
import com.czur.aimeeting.client.MeetingErrorCode
import com.czur.aimeeting.client.MeetingHistoryClient
import com.czur.aimeeting.client.MeetingHistoryItem
import com.czur.aimeeting.client.MeetingHistoryPage
import com.czur.aimeeting.client.MeetingOptions
import com.czur.aimeeting.client.MeetingResult
import com.czur.aimeeting.client.MeetingResultContent
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.client.result.ResultActionAccess
import com.czur.aimeeting.client.result.ResultActionContext
import com.czur.aimeeting.client.result.ResultActionOutcome
import com.czur.aimeeting.client.result.ResultActionRegistry
import com.czur.aimeeting.client.server.DeploymentOptions
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import com.czur.aimeeting.reference.meeting.MeetingRuntimeFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 只读的历史会议列表。列表、结果清单和结果动作全部走统一客户端／插件装配：
 * 页面不创建 HTTP 客户端、不拼协议 JSON、不直接打开结果 Activity。
 *
 * <p>结果链接的 genTime 只是链接生成时间，服务端按 genTime + TTL 判过期，
 * 因此历史会议也能用“现在”生成一个有效链接。凭据只在本进程的 history client
 * 内使用，不写入 Intent、不落盘、不打印。</p>
 */
class MeetingRecordsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: View
    private lateinit var loadMore: Button
    private var nextPageToken: String? = null
    private var loading = false
    private var historyClient: MeetingHistoryClient? = null
    private var workExecutor: Executor? = null
    private var discovery: DeploymentOptions? = null
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reference_records_page)
        list = findViewById(R.id.records_list)
        status = findViewById(R.id.records_status)
        empty = findViewById(R.id.records_empty)
        loadMore = findViewById(R.id.records_load_more)
        findViewById<Button>(R.id.records_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.records_refresh).setOnClickListener {
            nextPageToken = null
            list.removeAllViews()
            load(pageToken = null)
        }
        loadMore.setOnClickListener { load(pageToken = nextPageToken) }

        MeetingAppAssembly.initialize(applicationContext)
        if (!MeetingAppAssembly.isBackendConfigured()) {
            renderMessage("后端地址尚未配置")
            empty.visibility = View.GONE
            return
        }
        val identity = MeetingAppAssembly.currentIdentity()
        val session = if (identity.status() == IdentityResult.Status.LOGGED_IN) identity.session() else null
        if (session == null) {
            renderMessage("请先登录后查看会议记录")
            empty.visibility = View.GONE
            return
        }
        val config = MeetingAppAssembly.clientConfig { runOnUiThread(it) }.forSession(session)
        historyClient = MeetingRuntimeFactory.createHistory(config)
        workExecutor = Executors.newSingleThreadExecutor()
        // 只读发现用于结果动作的服务能力；失败不阻塞历史列表浏览。
        historyClient?.discover(object : MeetingClient.Callback<DeploymentOptions> {
            override fun onSuccess(result: DeploymentOptions) { discovery = result }
            override fun onError(error: MeetingError) { discovery = null }
        })
        load(pageToken = null)
    }

    override fun onDestroy() {
        historyClient?.close()
        historyClient = null
        (workExecutor as? ExecutorService)?.shutdownNow()
        workExecutor = null
        super.onDestroy()
    }

    private fun load(pageToken: String?) {
        if (loading) return
        val client = historyClient ?: return
        loading = true
        loadMore.isEnabled = false
        if (list.childCount == 0) renderMessage("正在加载…")
        client.list(pageToken, 20, object : MeetingClient.Callback<MeetingHistoryPage> {
            override fun onSuccess(result: MeetingHistoryPage) {
                loading = false
                renderPage(result)
            }

            override fun onError(error: MeetingError) {
                renderFailure("加载失败，请稍后重试")
            }
        })
    }

    private fun renderPage(page: MeetingHistoryPage) {
        for (item in page.items) list.addView(recordRow(item))
        nextPageToken = page.nextPageToken
        loadMore.visibility = if (nextPageToken == null) View.GONE else View.VISIBLE
        loadMore.isEnabled = true
        empty.visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
        renderMessage(if (list.childCount == 0) "" else "共 " + list.childCount + " 场会议")
    }

    private fun renderFailure(message: String) {
        loading = false
        loadMore.isEnabled = true
        empty.visibility = View.GONE
        renderMessage(message)
    }

    private fun renderMessage(message: String) {
        status.text = message
    }

    private fun recordRow(item: MeetingHistoryItem): View {
        val row = LayoutInflater.from(this).inflate(R.layout.reference_record_item, list, false)
        row.findViewById<TextView>(R.id.record_title).text = item.title ?: "未命名会议"
        row.findViewById<TextView>(R.id.record_meta).text = listOfNotNull(
            stateText(item.state, item.manifestState.orEmpty()),
            formatTime(item.createdAt.orEmpty()),
            formatDuration(item.createdAt, item.updatedAt)
        ).joinToString(" · ")
        row.setOnClickListener { openResult(item) }
        return row
    }

    private fun openResult(item: MeetingHistoryItem) {
        if (item.meetingId.isBlank()) return
        if (discovery == null) {
            renderMessage("结果服务尚未就绪，请稍后重试")
            return
        }
        val client = historyClient ?: return
        renderMessage("正在读取本场结果…")
        client.readResult(item.meetingId, System.currentTimeMillis(), false,
            object : MeetingClient.Callback<MeetingResult> {
                override fun onSuccess(result: MeetingResult) { showResultActions(result) }
                override fun onError(error: MeetingError) {
                    renderMessage("本场结果尚未就绪，请稍后重试")
                }
            })
    }

    /** 结果动作由注册表按产物和服务能力筛选，页面不写死 Web／PDF／邮件。 */
    private fun showResultActions(result: MeetingResult) {
        val current = discovery ?: return
        val snapshot = MeetingDiscovery(current, emptySet())
        val appActionsById = MeetingAppAssembly.resultActions().associateBy { it.id() }
        val available = MeetingAppAssembly.resultRegistry().available(snapshot)
            .filter { plugin ->
                plugin.requiredArtifactTypes().isEmpty()
                    || result.artifactTypes().containsAll(plugin.requiredArtifactTypes())
            }
            .filter { plugin ->
                appActionsById[plugin.id()]?.requiresResultWebUrl() != true
                    || MeetingAppAssembly.isResultWebConfigured()
            }
        when {
            available.isEmpty() -> renderMessage("本场没有可用的结果动作")
            available.size == 1 -> executeResult(available.single(), result, snapshot)
            else -> AlertDialog.Builder(this)
                .setTitle("查看结果")
                .setItems(available.map { it.displayName() }.toTypedArray()) { _, which ->
                    executeResult(available[which], result, snapshot)
                }
                .show()
        }
    }

    private fun executeResult(plugin: com.czur.aimeeting.client.result.ResultActionPlugin,
                              result: MeetingResult, current: MeetingDiscovery) {
        val options = MeetingOptions(
            MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US", false,
            MeetingOptions.ResultDelivery.WEB, MeetingAppAssembly.resultWebUrl(), emptyList(), true
        )
        val preparation = MeetingAppAssembly.resultRegistry().prepare(plugin.id(), options, current)
        val action = preparation.action() ?: run {
            renderMessage(preparation.message())
            return
        }
        val work = workExecutor ?: return
        val context = ResultActionContext(
            result,
            HistoryResultAccess(result.meetingId(), result.genTime()),
            ResultActionRegistry.serviceCapabilities(current),
            work,
            Executor { runOnUiThread(it) }
        )
        MeetingAppAssembly.resultRegistry().execute(action, context,
            object : MeetingClient.Callback<ResultActionOutcome> {
                override fun onSuccess(outcome: ResultActionOutcome) { renderMessage(outcome.message()) }
                override fun onError(error: MeetingError) { renderMessage("结果动作失败，请稍后重试") }
            })
    }

    /** 历史结果只读窄访问：正文和邮件仍走统一协议，不把 token 交给插件。 */
    private inner class HistoryResultAccess(
        private val meetingId: String,
        private val genTime: Long
    ) : ResultActionAccess {
        override fun readContent(callback: MeetingClient.Callback<MeetingResultContent?>) {
            val client = historyClient
            if (client == null) {
                callback.onError(MeetingError(MeetingErrorCode.UNKNOWN))
                return
            }
            client.readResult(meetingId, genTime, true, object : MeetingClient.Callback<MeetingResult> {
                override fun onSuccess(result: MeetingResult) { callback.onSuccess(result.content()) }
                override fun onError(error: MeetingError) { callback.onError(error) }
            })
        }

        override fun sendEmail(recipients: List<String>, resultUrl: String,
                               callback: MeetingClient.Callback<Void?>) {
            val client = historyClient
            if (client == null) {
                callback.onError(MeetingError(MeetingErrorCode.UNKNOWN))
                return
            }
            client.sendEmail(meetingId, recipients, resultUrl, callback)
        }
    }

    /** 只把服务端状态翻成人话；未识别的状态原样显示，方便排查。 */
    private fun stateText(state: String, manifestState: String): String = when (state) {
        "READY" -> if (manifestState == "READY") "已完成" else "已完成（产物待确认）"
        "READY_WITH_WARNINGS" -> "已完成（有告警）"
        "RUNNING", "STARTING", "AUTHORIZED" -> "进行中"
        "STOPPING", "FINALIZING" -> "收尾中"
        "FAILED" -> "失败"
        "CANCELLED" -> "已取消"
        "DELETION_PENDING" -> "删除中"
        "CREATED" -> "已创建"
        else -> state.ifBlank { "未知状态" }
    }

    private fun formatTime(raw: String): String = runCatching {
        timeFormat.format(Instant.parse(raw))
    }.getOrElse { raw }

    /** 标题在参考后端可以为空，时长用来区分同一时段的重复记录。 */
    private fun formatDuration(createdAt: String?, updatedAt: String?): String? {
        if (createdAt.isNullOrBlank() || updatedAt.isNullOrBlank()) return null
        return runCatching {
            val seconds = Duration.between(Instant.parse(createdAt), Instant.parse(updatedAt)).seconds
            if (seconds <= 0) return@runCatching null
            val minutes = seconds / 60
            if (minutes <= 0) "时长 " + seconds + " 秒"
            else "时长 " + minutes + " 分 " + (seconds % 60) + " 秒"
        }.getOrNull()
    }
}
