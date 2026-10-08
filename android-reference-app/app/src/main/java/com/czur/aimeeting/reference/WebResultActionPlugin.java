package com.czur.aimeeting.reference;

import android.content.Context;
import android.content.Intent;
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import com.czur.aimeeting.client.result.ResultActionRegistry;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

/** 默认Web动作：会前冻结部署地址，READY后只组合同场链接，不读取正文或发送邮件。 */
public final class WebResultActionPlugin implements AppResultActionPlugin {
    private final String configuredUrl;
    private final Consumer<String> openPage;

    /** 构建期声明实例；平台页面动作由createSettings使用Application Context装配。 */
    public WebResultActionPlugin() { this(null, null); }

    /** 平台适配或纯Java fixture入口；openPage通过宿主的通用UI Executor调用。 */
    public WebResultActionPlugin(String url, Consumer<String> openPage) {
        this.configuredUrl = url;
        this.openPage = openPage;
    }

    @Override public String id() { return "web"; }
    @Override public String displayName() { return "网页查看"; }
    @Override public Set<String> requiredServiceCapabilities() {
        return Set.of(ResultActionRegistry.RESULT_LINK);
    }

    @Override public Settings createSettings(Context pageContext) {
        Context app = pageContext.getApplicationContext();
        // 默认Web无会前输入View；freeze在点击开始时读取地址，不在打开页面时提前固定。
        return new Settings(null, () -> new WebResultActionPlugin(
                MeetingAppAssembly.resultWebUrl(), url -> {
                    Intent intent = new Intent(app, ResultActivity.class);
                    intent.putExtra(ResultActivity.EXTRA_RESULT_URL, url);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(intent);
                }));
    }

    @Override public Preparation prepare(MeetingOptions options) {
        String url = configuredUrl == null ? options.resultWebUrl() : configuredUrl;
        if (!validUrl(url)) return Preparation.block("请填写有效的网页结果地址");
        return Preparation.allow((context, callback) -> {
            String link = resultUrl(url, context.result().meetingId(), context.result().genTime());
            context.uiExecutor().execute(new UiOperation(() -> {
                try {
                    openPage.accept(link);
                    callback.onSuccess(ResultActionOutcome.success(id(), "已打开本场网页结果"));
                } catch (RuntimeException failure) {
                    callback.onError("无法打开网页结果，请再次执行");
                }
            }));
        });
    }

    static boolean validUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException | NullPointerException invalid) { return false; }
    }

    /** 保留已有查询参数／fragment，替换同名会议参数，避免旧地址指向另一场。 */
    public static String resultUrl(String base, String meetingId, long genTime) {
        URI uri = URI.create(base);
        ArrayList<String> query = new ArrayList<>();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            for (String part : uri.getRawQuery().split("&")) {
                String key = part.split("=", 2)[0];
                if (!key.equals("meetingId") && !key.equals("genTime")) query.add(part);
            }
        }
        query.add("meetingId=" + URLEncoder.encode(meetingId, StandardCharsets.UTF_8));
        query.add("genTime=" + genTime);
        String path = base.split("[?#]", 2)[0];
        return path + "?" + String.join("&", query)
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }
}
