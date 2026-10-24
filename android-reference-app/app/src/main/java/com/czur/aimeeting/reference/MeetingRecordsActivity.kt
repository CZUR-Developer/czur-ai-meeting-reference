package com.czur.aimeeting.reference

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.czur.aimeeting.client.identity.IdentityResult
import com.czur.aimeeting.reference.identity.PersistentReferenceIdentityProvider
import com.czur.aimeeting.reference.meeting.ReferenceDeployment
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * 只读的历史会议列表：用当前登录身份分页查询 `/v1/meetings`，点一条用当前时间
 * 现生成结果链接打开既有结果页。不创建、不删除、不改变任何会议状态。
 *
 * <p>结果链接的 genTime 只是链接生成时间，服务端按 genTime + TTL 判过期，
 * 因此历史会议也能用“现在”生成一个有效链接。凭据只在进程内用于本次请求，
 * 不写入 Intent、不落盘、不打印。</p>
 */
class MeetingRecordsActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: View
    private lateinit var loadMore: Button
    private var nextPageToken: String? = null
    private var loading = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
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
        load(pageToken = null)
    }

    private fun load(pageToken: String?) {
        if (loading) return
        val identity = PersistentReferenceIdentityProvider(this).currentIdentity()
        val session = if (identity.status() == IdentityResult.Status.LOGGED_IN) identity.session() else null
        if (session == null) {
            renderMessage("请先登录后查看会议记录")
            empty.visibility = View.GONE
            return
        }
        loading = true
        loadMore.isEnabled = false
        if (list.childCount == 0) renderMessage("正在加载…")
        val url = buildString {
            append(ReferenceDeployment.BACKEND_URL).append("/v1/meetings?pageSize=20")
            if (!pageToken.isNullOrBlank()) {
                append("&pageToken=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + session.accessToken())
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                runOnUiThread { renderFailure("网络不可用：" + (failure.message ?: "请稍后重试")) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body.string()
                runOnUiThread {
                    if (!response.isSuccessful) {
                        renderFailure("加载失败（HTTP " + response.code + "）")
                    } else {
                        runCatching { renderPage(JSONObject(body)) }
                            .getOrElse { renderFailure("返回内容无法解析") }
                    }
                }
            }
        })
    }

    private fun renderPage(page: JSONObject) {
        loading = false
        val items = page.optJSONArray("items")
        val count = items?.length() ?: 0
        for (index in 0 until count) {
            val item = items!!.optJSONObject(index) ?: continue
            list.addView(recordRow(item))
        }
        // org.json 把 JSON null 读成字符串 "null"，必须先用 isNull 区分。
        nextPageToken = if (page.isNull("nextPageToken")) null
        else page.optString("nextPageToken", "").takeIf { it.isNotBlank() }
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

    private fun recordRow(item: JSONObject): View {
        val row = LayoutInflater.from(this).inflate(R.layout.reference_record_item, list, false)
        val meetingId = item.optString("meetingId", "")
        val title = (if (item.isNull("title")) "" else item.optString("title", "")).ifBlank { "未命名会议" }
        row.findViewById<TextView>(R.id.record_title).text = title
        row.findViewById<TextView>(R.id.record_meta).text =
            stateText(item.optString("state", ""), item.optString("manifestState", "")) +
                " · " + formatTime(item.optString("createdAt", ""))
        row.setOnClickListener { openResult(meetingId) }
        return row
    }

    private fun openResult(meetingId: String) {
        if (meetingId.isBlank()) return
        val url = WebResultActionPlugin.resultUrl(
            ReferenceDeployment.RESULT_WEB_URL, meetingId, System.currentTimeMillis())
        startActivity(Intent(this, ResultActivity::class.java)
            .putExtra(ResultActivity.EXTRA_RESULT_URL, url))
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
}
