package com.czur.aimeeting.reference;

import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import com.czur.aimeeting.client.result.ResultActionRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 显式可选邮件动作；默认不注册。邮件失败只产生动作失败，原READY与genTime保持。 */
public final class EmailResultActionPlugin implements AppResultActionPlugin {
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    private final String configuredUrl;
    private final List<String> configuredEmails;
    public EmailResultActionPlugin() { this(null, null); }
    public EmailResultActionPlugin(String url, List<String> emails) {
        configuredUrl = url;
        configuredEmails = emails == null ? null : List.copyOf(emails);
    }
    @Override public String id() { return "email"; }
    @Override public String displayName() { return "邮箱通知"; }
    @Override public Set<String> requiredServiceCapabilities() {
        return Set.of(ResultActionRegistry.RESULT_LINK, ResultActionRegistry.RESULT_EMAIL);
    }
    @Override public Settings createSettings(Context pageContext) {
        LinearLayout layout = new LinearLayout(pageContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText url = new EditText(pageContext);
        url.setHint("网页结果地址");
        EditText recipients = new EditText(pageContext);
        recipients.setHint("收件邮箱，多个用逗号分隔（最多10个）");
        layout.addView(url);
        layout.addView(recipients);
        return new Settings(layout, () -> new EmailResultActionPlugin(url.getText().toString().trim(),
                Arrays.stream(recipients.getText().toString().split("[,，;；\\s]+"))
                        .filter(entry -> !entry.isBlank()).map(String::trim).toList()));
    }
    @Override public Preparation prepare(MeetingOptions options) {
        String url = configuredUrl == null ? options.resultWebUrl() : configuredUrl;
        List<String> emails = configuredEmails == null ? options.resultEmails() : configuredEmails;
        if (!WebResultActionPlugin.validUrl(url)) return Preparation.block("请填写有效的网页结果地址");
        if (emails.isEmpty() || emails.size() > 10 || emails.stream().distinct().count() != emails.size()
                || emails.stream().anyMatch(email -> !EMAIL.matcher(email).matches()))
            return Preparation.block("请填写1～10个不重复的有效收件邮箱后开始会议");
        List<String> frozen = List.copyOf(emails);
        return Preparation.allow((context, callback) -> context.access().sendEmail(frozen,
                WebResultActionPlugin.resultUrl(url, context.result().meetingId(), context.result().genTime()),
                new MeetingClient.Callback<>() {
                    @Override public void onSuccess(Void ignored) {
                        callback.onSuccess(ResultActionOutcome.success(id(), "结果链接已发送到邮箱"));
                    }
                    @Override public void onError(String ignored) {
                        callback.onError("邮件发送失败，请再次执行");
                    }
                }));
    }
}
