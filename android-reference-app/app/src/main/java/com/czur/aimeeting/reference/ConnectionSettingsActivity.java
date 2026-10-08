package com.czur.aimeeting.reference;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;

/**
 * 会前独立连接配置页：仅保存当前进程的部署地址，不写入密码、token或磁盘配置。
 * 主线程使用；保存后宿主关闭旧会前客户端并按原身份／能力链刷新，会议Service不重配置。
 */
public final class ConnectionSettingsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reference_connection_settings);
        ReferenceUiScale.bind(findViewById(android.R.id.content));
        EditText backend = findViewById(R.id.backend_url);
        EditText web = findViewById(R.id.web_url);
        backend.setText(MeetingAppAssembly.serverUrl());
        web.setText(MeetingAppAssembly.resultWebUrl());
        findViewById(R.id.connection_back).setOnClickListener(view -> finish());
        findViewById(R.id.connection_save).setOnClickListener(view -> {
            String backendUrl = backend.getText().toString().trim();
            String webUrl = web.getText().toString().trim();
            if (!WebResultActionPlugin.validUrl(backendUrl) || !WebResultActionPlugin.validUrl(webUrl)) {
                ((TextView)findViewById(R.id.connection_message)).setText("请填写有效的后端与Web结果页地址");
                return;
            }
            MeetingAppAssembly.configureBackend(backendUrl);
            MeetingAppAssembly.configureResultWeb(webUrl);
            setResult(RESULT_OK);
            finish();
        });
    }
}
