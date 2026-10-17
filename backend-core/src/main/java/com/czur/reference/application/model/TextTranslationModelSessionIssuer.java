package com.czur.reference.application.model;

import java.util.Objects;

/**
 * 将一个实时 ASR 与一个独立文本翻译发行器组合成现有模型会话发行器。
 *
 * <p>由宿主构建期注册；核心不识别供应商，也不启动第二个会议。未获翻译授权时，
 * 完全沿用原 ASR 请求和响应。选择独立翻译后，ASR 只负责原文，避免同时产生两套译文；
 * SDK 根据响应中的 translationSession 调用已注册的翻译 Adapter。</p>
 */
public final class TextTranslationModelSessionIssuer implements ModelSessionIssuer {
    private final ModelSessionIssuer asr;
    private final TranslationSessionIssuer translation;

    public TextTranslationModelSessionIssuer(ModelSessionIssuer asr,
            TranslationSessionIssuer translation) {
        this.asr = Objects.requireNonNull(asr, "asr");
        this.translation = Objects.requireNonNull(translation, "translation");
    }

    @Override public Session issue(Request request) {
        if (!request.translationEnabled()) return asr.issue(request);
        // 先领取翻译材料，避免翻译配置不可用时额外创建一个 ASR 任务。
        var text = translation.issue(request);
        var realtime = asr.issue(new Request(request.sourceLanguage(), request.targetLanguages(),
                false, request.meetingId(), request.multilingualInput()));
        return new Session(realtime.adapterId(), realtime.endpoint(), realtime.expiresAt(),
                realtime.initParams(), text);
    }

    /** 逐段翻译前临近到期才调用，不启动定时续领，也不访问 ASR 签发器。 */
    @Override public TranslationSessionIssuer.Session issueTranslation(Request request) {
        return request.translationEnabled() ? translation.issue(request) : null;
    }
}
