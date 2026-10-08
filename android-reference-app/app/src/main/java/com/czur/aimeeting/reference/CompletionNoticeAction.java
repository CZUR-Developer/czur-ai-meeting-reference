package com.czur.aimeeting.reference;

import android.content.Context;
import android.widget.Toast;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import java.util.function.Consumer;

/**
 * 最小替换例：只注册本类与Assembly即可提示同场READY，不需要网络、正文或模型能力。
 * 默认不注册；设置无输入，闭包只捕获应用Context与不可变参数，不保留会前View。
 */
public final class CompletionNoticeAction implements AppResultActionPlugin {
    private final Consumer<String> showNotice;
    public CompletionNoticeAction() { this(null); }
    public CompletionNoticeAction(Consumer<String> showNotice) { this.showNotice = showNotice; }
    @Override public String id() { return "example.notice"; }
    @Override public String displayName() { return "完成提示（替换示例）"; }
    @Override public Settings createSettings(Context pageContext) {
        Context app = pageContext.getApplicationContext();
        return new Settings(null, () -> new CompletionNoticeAction(
                message -> Toast.makeText(app, message, Toast.LENGTH_LONG).show()));
    }
    @Override public Preparation prepare(MeetingOptions options) {
        return Preparation.allow((context, callback) -> context.uiExecutor().execute(new UiOperation(() -> {
            try {
                showNotice.accept("本场结果已就绪：" + context.result().meetingId());
                callback.onSuccess(ResultActionOutcome.success(id(), "本场结果已就绪，完成提示已显示"));
            } catch (RuntimeException failure) { callback.onError("完成提示失败，请再次执行"); }
        })));
    }
}
