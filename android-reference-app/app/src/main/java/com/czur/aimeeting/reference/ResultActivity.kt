package com.czur.aimeeting.reference

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly

/** 无外部浏览器时使用系统 WebView 打开静态结果页。 */
class ResultActivity : Activity() {
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reference_result_page)
        ReferenceUiScale.bind(findViewById(android.R.id.content))
        webView = findViewById(R.id.result_web)
        val configured = MeetingAppAssembly.resultWebUrl()
        val url = intent.getStringExtra(EXTRA_RESULT_URL).orEmpty()
        if (!WebResultActionPlugin.isAllowedResultUrl(url, configured)) {
            Toast.makeText(this, "结果页地址不在允许范围", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !WebResultActionPlugin.isAllowedResultUrl(request.url.toString(), configured)
            }
        }
        findViewById<View>(R.id.result_back).setOnClickListener { finish() }
        webView.loadUrl(url)
    }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
    companion object { const val EXTRA_RESULT_URL = "resultUrl" }
}
