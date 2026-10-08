package com.czur.aimeeting.reference;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.MeetingResultContent;
import com.czur.aimeeting.client.result.ResultActionOutcome;
import java.io.IOException;
import java.util.Set;

/** 显式可选PDF插件：READY后才读正文，平台文件工作复用原单线程队列与writer／预览页。 */
public final class LocalPdfResultActionPlugin implements AppResultActionPlugin {
    private final Context app;
    public LocalPdfResultActionPlugin() { this(null); }
    private LocalPdfResultActionPlugin(Context app) { this.app = app; }
    @Override public String id() { return "local.pdf"; }
    @Override public String displayName() { return "本地保存PDF"; }
    @Override public Set<String> requiredArtifactTypes() { return Set.of("transcript.final"); }
    @Override public Settings createSettings(Context pageContext) {
        Context context = pageContext.getApplicationContext();
        return new Settings(null, () -> new LocalPdfResultActionPlugin(context));
    }
    @Override public Preparation prepare(MeetingOptions options) {
        return Preparation.allow((context, callback) -> context.access().readContent(new MeetingClient.Callback<>() {
            @Override public void onSuccess(MeetingResultContent content) {
                context.workExecutor().execute(() -> {
                    try {
                        MeetingResult ready = context.result();
                        MeetingResult full = new MeetingResult(ready.meetingId(), ready.title(), ready.genTime(),
                                ready.status(), ready.artifactTypes(), content);
                        LocalPdfWriter.SavedPdf pdf = new LocalPdfWriter(app).write(ready.meetingId(), full);
                        LocalResultActivity.remember(app, pdf, pdf.uri() != null);
                        context.uiExecutor().execute(new UiOperation(() -> {
                            try {
                                Intent intent = new Intent(app, LocalResultActivity.class);
                                if (pdf.uri() != null) intent.putExtra(LocalResultActivity.EXTRA_URI, pdf.uri().toString());
                                intent.putExtra(LocalResultActivity.EXTRA_CACHE_PATH, pdf.cachePath());
                                intent.putExtra(LocalResultActivity.EXTRA_FILE_NAME, pdf.displayName());
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                app.startActivity(intent);
                                callback.onSuccess(ResultActionOutcome.success(id(), pdf.uri() == null
                                        ? "PDF已生成，请选择公共下载位置" : "PDF已保存到下载/AI会议纪要"));
                            } catch (RuntimeException failure) { callback.onError("无法打开PDF，请再次执行"); }
                        }));
                    } catch (IOException | RuntimeException failure) {
                        Log.w("AiMeetingReference", "PDF动作失败 type=" + failure.getClass().getSimpleName());
                        callback.onError("本地PDF保存失败，请再次执行");
                    }
                });
            }
            @Override public void onError(String ignored) { callback.onError("读取本场正文失败，请再次执行"); }
        }));
    }
}
