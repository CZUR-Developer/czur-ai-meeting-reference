package com.czur.aimeeting.reference

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly

/** 参考连接配置页；只保存后端／结果页地址，不保存账号密码，保存后重新刷新身份和能力。 */
class ConnectionSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.reference_connection_settings)
        ReferenceUiScale.bind(findViewById(android.R.id.content))
        val backend = findViewById<EditText>(R.id.backend_url)
        val web = findViewById<EditText>(R.id.web_url)
        backend.setText(MeetingAppAssembly.serverUrl())
        web.setText(MeetingAppAssembly.resultWebUrl())
        findViewById<View>(R.id.connection_back).setOnClickListener { finish() }
        findViewById<View>(R.id.connection_save).setOnClickListener {
            val backendUrl = backend.text.toString().trim()
            val webUrl = web.text.toString().trim()
            if (!WebResultActionPlugin.validUrl(backendUrl) || !WebResultActionPlugin.validUrl(webUrl)) {
                findViewById<TextView>(R.id.connection_message).text = "请填写有效的后端与Web结果页地址"
                return@setOnClickListener
            }
            MeetingAppAssembly.configureBackend(backendUrl)
            MeetingAppAssembly.configureResultWeb(webUrl)
            setResult(RESULT_OK)
            finish()
        }
    }
}
