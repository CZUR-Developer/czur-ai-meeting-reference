package com.czur.aimeeting.reference;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

/** 在设备没有外部浏览器时，使用系统 WebView 打开静态会后结果页。 */
public final class ResultActivity extends Activity {
    public static final String EXTRA_RESULT_URL = "resultUrl";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reference_result_page);
        ReferenceUiScale.bind(findViewById(android.R.id.content));
        webView = findViewById(R.id.result_web);
        webView.getSettings().setJavaScriptEnabled(true);
        findViewById(R.id.result_back).setOnClickListener(view -> finish());
        webView.loadUrl(getIntent().getStringExtra(EXTRA_RESULT_URL));
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
