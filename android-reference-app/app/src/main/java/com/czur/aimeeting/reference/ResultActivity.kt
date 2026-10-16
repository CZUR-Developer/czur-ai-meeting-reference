package com.czur.aimeeting.reference

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.webkit.WebView

/** 无外部浏览器时使用系统 WebView 打开静态结果页。 */
class ResultActivity : Activity() {
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reference_result_page)
        ReferenceUiScale.bind(findViewById(android.R.id.content))
        webView = findViewById(R.id.result_web)
        webView.settings.javaScriptEnabled = true
        findViewById<View>(R.id.result_back).setOnClickListener { finish() }
        webView.loadUrl(intent.getStringExtra(EXTRA_RESULT_URL).orEmpty())
    }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
    companion object { const val EXTRA_RESULT_URL = "resultUrl" }
}
