package com.czur.aimeeting.reference.tingwu;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.model.TranslationEvent;
import com.czur.aimeeting.model.TranslationListener;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 听悟实时转写的公开协议 Adapter。只负责启动/停止消息及字幕、译文映射，不持有网络或 MIC。
 *
 * <p>一个实例只用于一场会议；听悟的 index 是该场字幕片段标识，partial 与 SentenceEnd
 * 按同一片段递增修订号。模型原始消息和会话材料不进入日志或标准结果。
 */
public final class TingwuModelAdapter implements ModelAdapter {
    public static final String ID = "tingwu-realtime";

    private final Map<String, SegmentState> segments = new HashMap<>();
    private final Map<String, SegmentState> translations = new HashMap<>();
    private final Map<Integer, Long> sentenceStarts = new HashMap<>();
    private final Map<Integer, String> sentenceSpeakers = new HashMap<>();
    private int fallbackIndex;
    private volatile boolean translationEnabled;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String startMessage(ModelSessionGrant grant) {
        try {
            // 本场开关由后端按已保存的能力决策签发，不能从目标语言或模型全局配置推断。
            translationEnabled = "true".equals(grant.initParams().get("translationEnabled"));
            return command("StartTranscription", taskId(grant))
                    .put("payload", new JSONObject()
                            .put("enable_intermediate_result", true)
                            .put("enable_translate_result", translationEnabled))
                    .toString();
        } catch (JSONException invalid) {
            throw new IllegalStateException("无法构造听悟启动消息", invalid);
        }
    }

    @Override
    public String stopMessage(ModelSessionGrant grant) {
        try {
            return command("StopTranscription", taskId(grant)).toString();
        } catch (JSONException invalid) {
            throw new IllegalStateException("无法构造听悟停止消息", invalid);
        }
    }

    @Override
    public ModelSignal onServerMessage(String message, TranscriptListener listener) {
        return onServerMessage(message, listener, event -> {});
    }

    @Override
    public ModelSignal onServerMessage(String message, TranscriptListener transcriptListener,
            TranslationListener translationListener) {
        try {
            JSONObject root = new JSONObject(message);
            JSONObject header = root.optJSONObject("header");
            String name = header == null ? "" : header.optString("name",
                    header.optString("event", ""));
            if ("TranscriptionStarted".equals(name)) return ModelSignal.AUDIO_READY;
            if ("TaskFailed".equals(name) || "TranscriptionFailed".equals(name)) {
                throw new IllegalStateException("听悟会话失败：" + name);
            }

            JSONObject payload = root.optJSONObject("payload");
            if (payload != null) {
                if (translationEnabled) emitTranslations(payload, translationListener);
                int index = payload.optInt("index", -1);
                String speaker = speakerId(payload);
                if (index >= 0 && speaker != null) sentenceSpeakers.put(index, speaker);
                if ("SentenceBegin".equals(name) && payload.has("index")) {
                    sentenceStarts.put(payload.optInt("index"),
                            Math.max(0, payload.optLong("time", 0)));
                }
                String text = payload.optString("result", "").trim();
                if (!text.isEmpty() && ("TranscriptionResultChanged".equals(name)
                        || "SentenceEnd".equals(name))) {
                    boolean isFinal = "SentenceEnd".equals(name);
                    emit(payload, text, isFinal, transcriptListener);
                }
            }
            if ("TranscriptionCompleted".equals(name) || "Completed".equals(name)) {
                return ModelSignal.FINISHED;
            }
            return ModelSignal.NONE;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("听悟消息格式错误", invalid);
        }
    }

    /**
     * 听悟译文是独立的 translate_result 列表；其 index 对应原文片段的 index。
     * 固定样例字段来自现有国内版 TingwuProvider 的解析路径，真实会话还需设备验证。
     */
    private void emitTranslations(JSONObject payload, TranslationListener listener) {
        JSONArray results = payload.optJSONArray("translate_result");
        if (results == null) return;
        String declaredSource = payload.optString("source_lang", "");
        String declaredTarget = payload.optString("target_lang", "");
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            int index = item.optInt("index", -1);
            String text = item.optString("text", "").trim();
            String source = translationLanguage(item.optString("detected_source_lang", ""));
            if (source.isEmpty()) source = translationLanguage(declaredSource);
            String target = translationLanguage(declaredTarget);
            if (index < 0 || text.isEmpty() || source.isEmpty() || target.isEmpty()
                    || source.equalsIgnoreCase(target)) continue;
            boolean isFinal = !item.optBoolean("partial", false);
            String id = "tingwu-translation-" + index + "-" + target;
            SegmentState previous = translations.get(id);
            if (previous != null && (previous.isFinal || (!isFinal && previous.text.equals(text)))) {
                continue;
            }
            int revision = previous == null ? 1 : previous.revision + 1;
            listener.onTranslation(new TranslationEvent(id, "tingwu-" + index, revision,
                    source, target, text, isFinal));
            translations.put(id, new SegmentState(revision, text, isFinal));
        }
    }

    private void emit(JSONObject payload, String text, boolean isFinal,
            TranscriptListener listener) {
        int index = payload.optInt("index", -1);
        long knownStart = index < 0 ? 0 : sentenceStarts.getOrDefault(index, 0L);
        long startMs = Math.max(0, payload.optLong("begin_time",
                payload.optLong("start_time", knownStart)));
        long endMs = Math.max(startMs, payload.optLong("time",
                payload.optLong("end_time", startMs)));
        String segmentId;
        if (index >= 0) {
            segmentId = "tingwu-" + index;
        } else if (startMs > 0) {
            segmentId = "tingwu-at-" + startMs;
        } else {
            // 无 index/时间的模型消息沿用当前片段；final 后再开始下一片段。
            segmentId = "tingwu-fallback-" + fallbackIndex;
            if (isFinal) fallbackIndex++;
        }
        SegmentState previous = segments.get(segmentId);
        String language = language(payload.optString("detected_source_lang",
                payload.optString("source_lang", payload.optString("source_language", ""))));
        String speaker = speakerId(payload);
        if (speaker == null && index >= 0) speaker = sentenceSpeakers.get(index);
        if (previous != null) {
            if (previous.isFinal) return;
            if (!isFinal && previous.text.equals(text) && previous.language.equals(language)
                    && java.util.Objects.equals(previous.speaker, speaker)) return;
        }
        int revision = previous == null ? 1 : previous.revision + 1;
        listener.onTranscript(new TranscriptEvent(segmentId, revision, startMs, endMs,
                language, text, isFinal, speaker));
        segments.put(segmentId, new SegmentState(revision, text, isFinal, language, speaker));
    }

    private static String speakerId(JSONObject payload) {
        String value = speakerIdIn(payload);
        if (value != null) return value;
        value = speakerIdIn(payload.optJSONObject("transcription"));
        if (value != null) return value;
        JSONArray words = payload.optJSONArray("words");
        if (words != null) {
            for (int index = 0; index < words.length(); index++) {
                value = speakerIdIn(words.optJSONObject(index));
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String speakerIdIn(JSONObject source) {
        if (source == null) return null;
        for (String key : new String[]{"SpeakerId", "speaker_id", "speakerId", "speaker"}) {
            String value = source.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static String taskId(ModelSessionGrant grant) {
        String taskId = grant.initParams().get("taskId");
        if (taskId == null || taskId.trim().isEmpty()) {
            throw new IllegalArgumentException("听悟会话缺少 taskId");
        }
        return taskId;
    }

    private static JSONObject command(String name, String taskId) throws JSONException {
        JSONObject header = new JSONObject()
                .put("message_id", UUID.randomUUID().toString().replace("-", ""))
                .put("task_id", taskId)
                .put("namespace", "SpeechTranscriber")
                .put("name", name)
                .put("appkey", "default");
        return new JSONObject().put("header", header);
    }

    private static String language(String raw) {
        return switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "CN", "ZH", "ZH-CN" -> "zh-CN";
            case "EN", "EN-US" -> "en-US";
            // 听悟源语种可能晚于第一条 partial；保留未知，不能伪装成中文让分栏误锁 A。
            default -> raw.trim().isEmpty() || "auto".equalsIgnoreCase(raw)
                    || "multilingual".equalsIgnoreCase(raw) ? "und" : raw;
        };
    }

    /** 译文不能把未检测到的语种默认成中文，否则会错误地进入中英分栏。 */
    private static String translationLanguage(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "auto".equalsIgnoreCase(trimmed)
                || "multilingual".equalsIgnoreCase(trimmed)) return "";
        return language(trimmed);
    }

    private static final class SegmentState {
        private final int revision;
        private final String text;
        private final boolean isFinal;
        private final String language;
        private final String speaker;

        private SegmentState(int revision, String text, boolean isFinal) {
            this(revision, text, isFinal, "", null);
        }

        private SegmentState(int revision, String text, boolean isFinal, String language,
                String speaker) {
            this.revision = revision;
            this.text = text;
            this.isFinal = isFinal;
            this.language = language;
            this.speaker = speaker;
        }
    }
}
