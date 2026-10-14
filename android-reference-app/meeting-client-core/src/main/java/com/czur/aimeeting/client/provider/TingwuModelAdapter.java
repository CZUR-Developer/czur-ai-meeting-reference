package com.czur.aimeeting.client.provider;

import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSignal;
import com.czur.aimeeting.model.SegmentStartedEvent;
import com.czur.aimeeting.model.SegmentStartedListener;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranscriptListener;
import com.czur.aimeeting.model.TranslationEvent;
import com.czur.aimeeting.model.TranslationListener;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 听悟实时转写的公开协议 Adapter。只负责启动/停止消息及字幕、译文映射，不持有网络或 MIC。
 *
 * <p>一个实例只用于一场会议；听悟的 index 是该场字幕片段标识，partial 与 SentenceEnd
 * 按同一片段递增修订号。partial是完整快照替换；普通final立即收段，启用翻译时按原文
 * final顺序等待同index的译文final，结束时保留缺译文的原文。语种/发言人迟到可更新已final
 * 原文的事实，但旧partial不重新打开final或替换确认文字。模型原始消息和材料不进日志。
 */
public final class TingwuModelAdapter implements ModelAdapter {
    public static final String ID = "tingwu-realtime";
    private static final Logger LOG = Logger.getLogger(TingwuModelAdapter.class.getName());

    private final Map<String, SegmentState> segments = new HashMap<>();
    private final Map<String, SegmentState> translations = new HashMap<>();
    private final Map<Integer, Long> sentenceStarts = new HashMap<>();
    private final Map<Integer, String> sentenceSpeakers = new HashMap<>();
    private final Map<Integer, SourceState> sources = new LinkedHashMap<>();
    private final Map<Integer, TranslationEvent> latestTranslations = new HashMap<>();
    /** 按原文 final 到达顺序配对，译文乱序不得抢先收口后面的原文。 */
    private final Set<Integer> pendingFinals = new LinkedHashSet<>();
    private final Set<Integer> emptyTranslationFinals = new LinkedHashSet<>();
    private final Set<Integer> finalizedIndexes = new LinkedHashSet<>();
    private int fallbackIndex;
    private volatile boolean translationEnabled;

    @Override
    public String id() {
        return ID;
    }

    /** 源语种可能随独立译文迟到；通过公共声明复用原语种窗口，不依赖听悟注册名称。 */
    @Override
    public boolean mayEmitDeferredSourceLanguage() { return true; }

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
        return onServerMessage(message, transcriptListener, translationListener, event -> { });
    }

    @Override
    public ModelSignal onServerMessage(String message, TranscriptListener transcriptListener,
            TranslationListener translationListener, SegmentStartedListener segmentStartedListener) {
        try {
            JSONObject root = new JSONObject(message);
            JSONObject header = root.optJSONObject("header");
            String name = header == null ? "" : header.optString("name",
                    header.optString("event", ""));
            if ("TranscriptionStarted".equals(name)) return ModelSignal.AUDIO_READY;
            if ("TaskFailed".equals(name) || "TranscriptionFailed".equals(name)) {
                // 仅在已确认的失败事件处记录数字状态；-1 表示头部未提供有效数字。
                // 不输出 status_text、原始 JSON 或 Throwable，便于区分供应商终止与宿主回调异常。
                LOG.warning("听悟失败事件 name=" + name + " status=" + header.optLong("status", -1));
                throw new IllegalStateException("听悟会话失败：" + name);
            }

            JSONObject payload = root.optJSONObject("payload");
            if (payload != null) {
                int index = payload.optInt("index", -1);
                String speaker = speakerId(payload);
                if (index >= 0 && speaker != null) sentenceSpeakers.put(index, speaker);
                if ("SentenceBegin".equals(name) && payload.has("index")) {
                    long startMs = Math.max(0, payload.optLong("time", 0));
                    if (!sentenceStarts.containsKey(index)) {
                        sentenceStarts.put(index, startMs);
                        segmentStartedListener.onSegmentStarted(
                                new SegmentStartedEvent("tingwu-" + index, startMs));
                    }
                }
                String text = payload.optString("result", "").trim();
                if (!text.isEmpty() && ("TranscriptionResultChanged".equals(name)
                        || "SentenceEnd".equals(name))) {
                    boolean isFinal = "SentenceEnd".equals(name);
                    if (index < 0) {
                        emit(payload, text, isFinal, transcriptListener);
                    } else if (finalizedIndexes.contains(index)) {
                        // 已final的原文可以迟到语种/发言人事实，但不能被旧partial重新打开。
                        SourceState finalized = sources.get(index);
                        if (isFinal) rememberSource(index, payload, text, true);
                        else updateSourceFacts(finalized, payload);
                        emitSource(index, true, transcriptListener);
                    } else {
                        rememberSource(index, payload, text, isFinal);
                        rememberStash(payload, segmentStartedListener);
                        if (isFinal) pendingFinals.add(index);
                        if (!isFinal || translationEnabled) {
                            emitSource(index, false, transcriptListener);
                        }
                        drainSourceFinals(false, transcriptListener);
                    }
                }
                if (translationEnabled && payload.has("translate_result")) {
                    emitTranslations(payload, transcriptListener, translationListener);
                }
            }
            if ("TranscriptionCompleted".equals(name) || "Completed".equals(name)) {
                // 结束时保留模型已经确认的原文 final；缺少译文不应使原文丢失。
                drainSourceFinals(true, transcriptListener);
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
    private void emitTranslations(JSONObject payload, TranscriptListener transcriptListener,
            TranslationListener listener) {
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
            if (index < 0 || source.isEmpty() || target.isEmpty()
                    || source.equalsIgnoreCase(target)) continue;
            boolean isFinal = !item.optBoolean("partial", false);
            SourceState sourceState = sources.get(index);
            if (sourceState != null) {
                sourceState.language = source;
                String speaker = speakerId(item);
                if (speaker != null) sourceState.speaker = speaker;
                if (finalizedIndexes.contains(index)) emitSource(index, true, transcriptListener);
            }
            if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
                if (isFinal) {
                    emptyTranslationFinals.add(index);
                    drainSourceFinals(false, transcriptListener);
                }
                continue;
            }
            String id = "tingwu-translation-" + index + "-" + target;
            SegmentState previous = translations.get(id);
            if (previous != null && (previous.isFinal && !isFinal
                    || previous.isFinal == isFinal && previous.text.equals(text)
                        && previous.language.equals(source))) {
                continue;
            }
            int revision = previous == null ? 1 : previous.revision + 1;
            TranslationEvent event = new TranslationEvent(id, "tingwu-" + index, revision,
                    source, target, text, isFinal);
            latestTranslations.put(index, event);
            translations.put(id, new SegmentState(revision, text, isFinal, source, null));
            // detected_source_lang 常只在译文消息内出现；先回传原文的语种修订，再配对译文。
            if (sourceState != null && !finalizedIndexes.contains(index)) {
                emitSource(index, false, transcriptListener);
            }
            listener.onTranslation(event);
            drainSourceFinals(false, transcriptListener);
            if (isFinal) emitLatestStash(transcriptListener);
        }
    }

    private static void updateSourceFacts(SourceState state, JSONObject payload) {
        if (state == null) return;
        String detected = language(payload.optString("detected_source_lang",
                payload.optString("source_lang", payload.optString("source_language", ""))));
        if (!"und".equals(detected)) state.language = detected;
        String speaker = speakerId(payload);
        if (speaker != null) state.speaker = speaker;
    }

    private void rememberSource(int index, JSONObject payload, String text, boolean sourceFinal) {
        SourceState previous = sources.get(index);
        long knownStart = previous == null ? sentenceStarts.getOrDefault(index, 0L) : previous.startMs;
        long startMs = Math.max(0, payload.optLong("begin_time", payload.optLong("start_time", knownStart)));
        long endMs = Math.max(startMs, payload.optLong("time", payload.optLong("end_time", startMs)));
        String detected = language(payload.optString("detected_source_lang",
                payload.optString("source_lang", payload.optString("source_language", ""))));
        if ("und".equals(detected) && previous != null) detected = previous.language;
        if ("und".equals(detected) && latestTranslations.containsKey(index)) {
            detected = latestTranslations.get(index).sourceLanguage();
        }
        String speaker = speakerId(payload);
        if (speaker == null) speaker = previous == null ? sentenceSpeakers.get(index) : previous.speaker;
        sources.put(index, new SourceState(text, detected, startMs, endMs, speaker,
                sourceFinal || previous != null && previous.sourceFinal));
    }

    /** stash 是下一段的提前快照，保留它的 index/起点，不拼接或复制进上一段全文。 */
    private void rememberStash(JSONObject payload, SegmentStartedListener beginListener) {
        JSONObject stash = payload.optJSONObject("stash_result");
        if (stash == null) return;
        int index = stash.optInt("index", -1);
        String text = stash.optString("text", "").trim();
        if (index < 0 || text.isEmpty() || finalizedIndexes.contains(index)) return;
        long startMs = Math.max(0, stash.optLong("beginTime", payload.optLong("time", 0)));
        if (!sentenceStarts.containsKey(index)) {
            sentenceStarts.put(index, startMs);
            beginListener.onSegmentStarted(new SegmentStartedEvent("tingwu-" + index, startMs));
        }
        SourceState previous = sources.get(index);
        String detected = language(stash.optString("detected_source_lang", ""));
        String speaker = speakerId(stash);
        sources.put(index, new SourceState(text,
                "und".equals(detected) && previous != null ? previous.language : detected,
                previous == null ? startMs : previous.startMs,
                previous == null ? startMs : previous.endMs,
                speaker == null && previous != null ? previous.speaker : speaker,
                previous != null && previous.sourceFinal));
    }

    private void emitSource(int index, boolean isFinal, TranscriptListener listener) {
        SourceState state = sources.get(index);
        if (state == null) return;
        String id = "tingwu-" + index;
        SegmentState previous = segments.get(id);
        if (previous != null && previous.text.equals(state.text)
                && previous.language.equals(state.language) && previous.isFinal == isFinal
                && java.util.Objects.equals(previous.speaker, state.speaker)) return;
        int revision = previous == null ? 1 : previous.revision + 1;
        listener.onTranscript(new TranscriptEvent(id, revision, state.startMs, state.endMs,
                state.language, state.text, isFinal, state.speaker));
        segments.put(id, new SegmentState(revision, state.text, isFinal, state.language, state.speaker));
    }

    /** 正常听悟 final 直接收段；开启翻译时等待本 index 的译文 final，不累计三句话。 */
    private void drainSourceFinals(boolean forceWithoutTranslation, TranscriptListener listener) {
        var iterator = pendingFinals.iterator();
        while (iterator.hasNext()) {
            int index = iterator.next();
            TranslationEvent translation = latestTranslations.get(index);
            boolean translated = translation != null && translation.isFinal()
                    || emptyTranslationFinals.contains(index);
            if (translationEnabled && !translated && !forceWithoutTranslation) break;
            emitSource(index, true, listener);
            finalizedIndexes.add(index);
            iterator.remove();
        }
    }

    private void emitLatestStash(TranscriptListener listener) {
        Integer latest = null;
        for (var entry : sources.entrySet()) {
            if (!entry.getValue().sourceFinal && !finalizedIndexes.contains(entry.getKey())) latest = entry.getKey();
        }
        if (latest != null) emitSource(latest, false, listener);
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

    private static final class SourceState {
        final String text;
        String language;
        final long startMs;
        final long endMs;
        String speaker;
        final boolean sourceFinal;
        SourceState(String text, String language, long startMs, long endMs, String speaker,
                boolean sourceFinal) {
            this.text = text;
            this.language = language;
            this.startMs = startMs;
            this.endMs = endMs;
            this.speaker = speaker;
            this.sourceFinal = sourceFinal;
        }
    }
}
