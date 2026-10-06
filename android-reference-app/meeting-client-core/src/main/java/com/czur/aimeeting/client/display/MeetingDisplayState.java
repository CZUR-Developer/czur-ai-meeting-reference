package com.czur.aimeeting.client.display;

import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 一场会议的会中显示快照。应用内页面和跨应用悬浮窗读取同一个实例，避免两套控件
 * 分别处理模型修订消息而显示不同文字。计时使用单调时钟，避免设备时间校准影响时长。
 */
public final class MeetingDisplayState {
    public static final class Segment {
        private final String id;
        private final int revision;
        private final String text;
        private final String language;
        private final boolean isFinal;

        private Segment(String id, int revision, String text, String language, boolean isFinal) {
            this.id = id;
            this.revision = revision;
            this.text = text;
            this.language = language;
            this.isFinal = isFinal;
        }

        public String id() { return id; }
        public String text() { return text; }
        public String language() { return language; }
        public float alpha() { return isFinal ? 1.0f : 0.8f; }
    }

    /** 一行双语互译；左/右取决于本场路由，空栏表示原文或译文尚未到达。 */
    public static final class BilingualRow {
        private final String id;
        private final String left;
        private final String right;
        private final float leftAlpha;
        private final float rightAlpha;
        private final DisplayRouter.Basis basis;

        private BilingualRow(String id, String left, String right,
                float leftAlpha, float rightAlpha, DisplayRouter.Basis basis) {
            this.id = id;
            this.left = left;
            this.right = right;
            this.leftAlpha = leftAlpha;
            this.rightAlpha = rightAlpha;
            this.basis = basis;
        }

        public String id() { return id; }
        public String left() { return left; }
        public String right() { return right; }
        public float leftAlpha() { return leftAlpha; }
        public float rightAlpha() { return rightAlpha; }
        public DisplayRouter.Basis basis() { return basis; }
        public boolean languageFallback() {
            return basis == DisplayRouter.Basis.MODEL_LANGUAGE
                    || basis == DisplayRouter.Basis.TEXT_LANGUAGE
                    || basis == DisplayRouter.Basis.LANGUAGE_UNRESOLVED;
        }
    }

    private static final class TranslationSegment {
        private final String sourceId;
        private final int revision;
        private final String sourceLanguage;
        private final String text;
        private final boolean isFinal;

        private TranslationSegment(TranslationEvent event) {
            sourceId = event.sourceSegmentId();
            revision = event.revision();
            sourceLanguage = event.sourceLanguage();
            text = event.text();
            isFinal = event.isFinal();
        }

        private float alpha() { return isFinal ? 1.0f : 0.8f; }
    }

    private final LinkedHashMap<String, Segment> segments = new LinkedHashMap<>();
    private final LinkedHashMap<String, TranslationSegment> translations = new LinkedHashMap<>();
    private DisplayRouter router;
    private boolean bilingual;
    private boolean swapped;
    private String languageA = "zh-CN";
    private String languageB = "en-US";
    private String transcript = "等待字幕…";
    private boolean finalTranscript;
    private long startedAtElapsedMs;

    public void start(long elapsedRealtimeMs) {
        start(elapsedRealtimeMs, false, "zh-CN", "en-US");
    }

    /** 会前模式与两种语言在本场开始时冻结；互译 UI 从第一帧起保持双栏。 */
    public void start(long elapsedRealtimeMs, boolean bilingual, String languageA,
            String languageB) {
        segments.clear();
        translations.clear();
        this.bilingual = bilingual;
        this.languageA = languageA;
        this.languageB = languageB;
        swapped = false;
        router = new DisplayRouter(languageA, languageB);
        transcript = "等待字幕…";
        finalTranscript = false;
        startedAtElapsedMs = elapsedRealtimeMs;
    }

    public void apply(TranscriptEvent event) {
        Segment previous = segments.get(event.segmentId());
        if (previous != null && (event.revision() < previous.revision
                || previous.isFinal && event.revision() <= previous.revision)) return;
        if (bilingual) router.route(event.segmentId(), event.language(), event.text());
        segments.put(event.segmentId(), new Segment(event.segmentId(), event.revision(),
                event.text(), event.language(), event.isFinal()));
        transcript = event.text();
        finalTranscript = event.isFinal();
    }

    /** 同一 translationId 原位修订；旧 revision 不得覆盖更新的显示内容。 */
    public void apply(TranslationEvent event) {
        TranslationSegment previous = translations.get(event.translationId());
        if (previous != null && (event.revision() < previous.revision
                || previous.isFinal && event.revision() <= previous.revision)) return;
        if (bilingual) router.route(event.sourceSegmentId(), event.sourceLanguage(), "");
        translations.put(event.translationId(), new TranslationSegment(event));
    }

    public boolean hasTranslation() { return !translations.isEmpty(); }

    public boolean isBilingual() { return bilingual; }

    public String leftLanguage() { return swapped ? languageB : languageA; }

    public String rightLanguage() { return swapped ? languageA : languageB; }

    /** 两处 UI 使用相同的会前语言标题；未内置名称的语言显示原代码。 */
    public String leftLabel() { return languageLabel(leftLanguage()); }

    public String rightLabel() { return languageLabel(rightLanguage()); }

    private static String languageLabel(String language) {
        if (language.startsWith("zh")) return "中文";
        if (language.startsWith("en")) return "English";
        return language;
    }

    /** 用户交换显示位置时，既有片段及后续片段一同换边，语义分栏锁不变。 */
    public void swapColumns() { swapped = !swapped; }

    /** P6 SDK 可提供最新稳定角度；无效值将让新片段改走语种降级，已锁片段不搬栏。 */
    public void updateStableDirection(float angle) { router.updateStableAngle(angle); }

    /**
     * SDK 正式方向输入。技术样本的状态只决定本次是否有角度；方向区间、语种与片段
     * 修订的组合规则继续由 App 路由决定。无声源／不支持时清掉旧全局角度，下一新片段
     * 可按语种降级；已经显示并锁侧的片段保持原栏位，不因后续采样反复搬动。
     */
    public void updateAudioDirection(AudioDirectionSample sample) {
        updateStableDirection(sample.status() == AudioDirectionSample.Status.AVAILABLE
                ? sample.stableAngleDegrees() : Float.NaN);
    }

    /** P6 SDK 若能把方向绑定到 ASR 片段，应在该片段首次显示前调用此入口。 */
    public void setSegmentDirection(String segmentId, int side) {
        router.setSegmentDirection(segmentId, side);
    }

    public DisplayRouter.Route route(String segmentId) {
        return router.existingRoute(segmentId);
    }

    /** 以片段 ID 配对原文和译文；会议页和悬浮窗读取同一份已路由状态。 */
    public List<BilingualRow> bilingualRows() {
        LinkedHashMap<String, TranslationSegment> bySource = new LinkedHashMap<>();
        for (TranslationSegment translation : translations.values()) {
            bySource.put(translation.sourceId, translation);
        }
        List<BilingualRow> rows = new ArrayList<>();
        for (Segment source : segments.values()) {
            rows.add(bilingualRow(source.id, source, bySource.remove(source.id)));
        }
        for (String sourceId : bySource.keySet()) {
            rows.add(bilingualRow(sourceId, null, bySource.get(sourceId)));
        }
        return rows;
    }

    public BilingualRow latestBilingualRow() {
        List<BilingualRow> rows = bilingualRows();
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private BilingualRow bilingualRow(String id, Segment source,
            TranslationSegment translation) {
        String sourceText = source == null ? "" : source.text;
        float sourceAlpha = source == null ? 1.0f : source.alpha();
        String translatedText = translation == null ? "" : translation.text;
        float translatedAlpha = translation == null ? 1.0f : translation.alpha();
        String sourceLanguage = translation == null
                ? (source == null ? "" : source.language) : translation.sourceLanguage;
        DisplayRouter.Route route = router.route(id, sourceLanguage, sourceText);
        if (route.side() == 0) {
            // 尚无可信语种/方向时显示占位，保留原文与译文快照；下一次修订可真正定栏。
            return new BilingualRow(id, "语言识别中：" + sourceText, "", sourceAlpha,
                    1.0f, route.basis());
        }
        boolean sourceOnLeft = (route.side() == 1) != swapped;
        return sourceOnLeft
                ? new BilingualRow(id, sourceText, translatedText, sourceAlpha,
                        translatedAlpha, route.basis())
                : new BilingualRow(id, translatedText, sourceText, translatedAlpha,
                        sourceAlpha, route.basis());
    }

    /** 按首次收到片段的顺序排列；修订更新原行，不另起一行。 */
    public List<Segment> segments() { return new ArrayList<>(segments.values()); }

    public Segment segment(String id) { return segments.get(id); }

    public String transcript() { return transcript; }

    public float transcriptAlpha() { return finalTranscript ? 1.0f : 0.8f; }

    public String elapsed(long elapsedRealtimeMs) {
        long seconds = Math.max(0L, (elapsedRealtimeMs - startedAtElapsedMs) / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L,
                (seconds / 60L) % 60L, seconds % 60L);
    }
}
