package com.czur.aimeeting.client.display;

import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.SegmentStartedEvent;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.logging.Logger;

/**
 * 一场会议的会中显示快照。应用内页面和跨应用悬浮窗读取同一个实例，避免两套控件
 * 分别处理模型修订消息而显示不同文字。计时使用单调时钟，避免设备时间校准影响时长。
 */
public final class MeetingDisplayState {
    private static final Logger LOG = Logger.getLogger(MeetingDisplayState.class.getName());
    public static final class Segment {
        private final String id;
        private final int revision;
        private final String text;
        private final String language;
        private final boolean isFinal;
        private final TranscriptEvent event;

        private Segment(TranscriptEvent event) {
            this.event = event;
            id = event.segmentId();
            revision = event.revision();
            text = event.text();
            language = event.language();
            isFinal = event.isFinal();
        }

        public String id() { return id; }
        public String text() { return text; }
        public String language() { return language; }
        public int revision() { return revision; }
        public boolean isFinal() { return isFinal; }
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
        private final TranslationEvent event;

        private TranslationSegment(TranslationEvent event) {
            this.event = event;
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
    /** 每段只保留等待期内的最新全量快照；起始时间不随 partial 修订延后。 */
    private final LinkedHashMap<String, PendingLanguage> pendingLanguages = new LinkedHashMap<>();
    private static final long LANGUAGE_WAIT_MS = 1500L;
    private DisplayRouter router;
    private boolean bilingual;
    private boolean swapped;
    private String languageA = "zh-CN";
    private String languageB = "en-US";
    private String transcript = "等待字幕…";
    private String latestTranscriptSegmentId;
    private boolean finalTranscript;
    private long startedAtElapsedMs;
    private boolean waitForModelLanguage;
    private boolean finalizing;

    public void start(long elapsedRealtimeMs) {
        start(elapsedRealtimeMs, false, "zh-CN", "en-US");
    }

    /** 会前模式与两种语言在本场开始时冻结；互译 UI 从第一帧起保持双栏。 */
    public void start(long elapsedRealtimeMs, boolean bilingual, String languageA,
            String languageB) {
        segments.clear();
        translations.clear();
        pendingLanguages.clear();
        this.bilingual = bilingual;
        this.languageA = languageA;
        this.languageB = languageB;
        swapped = false;
        router = new DisplayRouter(languageA, languageB);
        transcript = "等待字幕…";
        latestTranscriptSegmentId = null;
        finalTranscript = false;
        startedAtElapsedMs = elapsedRealtimeMs;
        waitForModelLanguage = false;
        finalizing = false;
    }

    public void apply(TranscriptEvent event) {
        apply(event, System.nanoTime() / 1_000_000L);
    }

    /**
     * 只有已确认需要迟到语种处理的默认听悟启用窗口；其他模型复用相同路由但不强加等待。
     * 必须在本场第一条字幕前设置。App/Coordinator 根据工厂标识设置，不在 UI 解析 JSON。
     */
    public void setWaitForModelLanguage(boolean enabled) { waitForModelLanguage = enabled; }

    /** 模型句开始绑定方向快照；没有此事件的模型在第一条字幕到达时兜底绑定。 */
    public void onSegmentStarted(SegmentStartedEvent event) { router.onSegmentStarted(event.segmentId()); }

    /**
     * 接收一次原文修订。nowMs 必须来自同一单调时钟；等待仅影响显示，原始事件可立即保存。
     * 显示控制器只等待有效模糊方向区，最多1.5秒，明确方向或不支持DOA时正常直接显示。
     */
    public void apply(TranscriptEvent event, long nowMs) {
        releaseExpiredLanguages(nowMs);
        Segment previous = segments.get(event.segmentId());
        if (previous != null && (event.revision() < previous.revision
                || previous.isFinal && event.revision() <= previous.revision)) return;
        PendingLanguage pending = pendingLanguages.get(event.segmentId());
        if (pending != null && event.revision() < pending.event.revision()) return;
        router.onSegmentStarted(event.segmentId());
        if (bilingual && waitForModelLanguage && router.isLanguageBased(event.segmentId())
                && router.existingRoute(event.segmentId()) == null
                && !router.hasConfiguredLanguage(event.language())) {
            if (finalizing) {
                router.forceLocalFallback(event.segmentId(), event.text());
            } else {
                pendingLanguages.put(event.segmentId(), new PendingLanguage(event,
                        pending == null ? nowMs : pending.startedAtMs));
                if (pending == null) LOG.fine("启动待语种显示窗口 segmentId=" + event.segmentId()
                        + " deadlineMs=" + (nowMs + LANGUAGE_WAIT_MS));
                return;
            }
        }
        pendingLanguages.remove(event.segmentId());
        applyVisible(event);
    }

    private void applyVisible(TranscriptEvent event) {
        if (bilingual) router.route(event.segmentId(), event.language(), event.text());
        segments.put(event.segmentId(), new Segment(event));
        transcript = event.text();
        latestTranscriptSegmentId = event.segmentId();
        finalTranscript = event.isFinal();
    }

    /** 同一 translationId 原位修订；旧 revision 不得覆盖更新的显示内容。 */
    public void apply(TranslationEvent event) {
        apply(event, System.nanoTime() / 1_000_000L);
    }

    /** 独立译文与原文按ID配对；可信源语种可立即释放同段等待快照。 */
    public void apply(TranslationEvent event, long nowMs) {
        releaseExpiredLanguages(nowMs);
        TranslationSegment previous = translations.get(event.translationId());
        if (previous != null && (event.revision() < previous.revision
                || previous.isFinal && event.revision() <= previous.revision)) return;
        if (bilingual && router.hasConfiguredLanguage(event.sourceLanguage())) {
            router.route(event.sourceSegmentId(), event.sourceLanguage(), "");
            PendingLanguage pending = pendingLanguages.remove(event.sourceSegmentId());
            if (pending != null) applyVisible(pending.event);
        } else if (bilingual) {
            router.onSegmentStarted(event.sourceSegmentId());
        }
        translations.put(event.translationId(), new TranslationSegment(event));
    }

    /** 最早等待截止时间；使用方只安排一个定时回调，不依赖网络队列或秒级计时tick。 */
    public OptionalLong nextLanguageDeadlineMs() {
        return pendingLanguages.values().stream().mapToLong(p -> p.startedAtMs + LANGUAGE_WAIT_MS).min();
    }

    /** 到期时冲刷最新快照并锁侧；返回true表示UI应刷新。迟到语种可以改事实但不搬栏。 */
    public boolean releaseExpiredLanguages(long nowMs) {
        boolean changed = false;
        var iterator = pendingLanguages.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingLanguage pending = entry.getValue();
            if (nowMs - pending.startedAtMs < LANGUAGE_WAIT_MS) continue;
            router.forceLocalFallback(entry.getKey(), pending.event.text());
            applyVisible(pending.event);
            LOG.fine("待语种显示窗口到期 segmentId=" + entry.getKey()
                    + " revision=" + pending.event.revision() + " waitMs=" + (nowMs - pending.startedAtMs));
            iterator.remove();
            changed = true;
        }
        return changed;
    }

    /** 正常收口时立即显示待语种快照；不再等待完整窗口，不修改raw事件中的未知语种。 */
    public boolean flushPendingLanguages() {
        finalizing = true;
        boolean changed = !pendingLanguages.isEmpty();
        if (changed) LOG.fine("停止时冲刷待语种显示段 count=" + pendingLanguages.size());
        for (var entry : pendingLanguages.entrySet()) {
            router.forceLocalFallback(entry.getKey(), entry.getValue().event.text());
            applyVisible(entry.getValue().event);
        }
        pendingLanguages.clear();
        return changed;
    }

    private record PendingLanguage(TranscriptEvent event, long startedAtMs) { }

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

    /** 显式更新/清除稳定角；正常SDK样本应使用updateAudioDirection区分无读数与不支持。 */
    public void updateStableDirection(float angle) { router.updateStableAngle(angle); }

    /**
     * SDK 正式方向输入。技术样本的状态只决定本次是否有角度；方向区间、语种与片段
     * 修订的组合规则由本公共core唯一处理。NO_DIRECTION保留上次有效稳定角，与国内
     * DoaPoller一致；UNSUPPORTED清除角度，后续新段按语种降级。已拍快照/锁侧的段不搬栏。
     */
    public void updateAudioDirection(AudioDirectionSample sample) {
        if (sample.status() == AudioDirectionSample.Status.AVAILABLE) {
            updateStableDirection(sample.stableAngleDegrees());
        } else if (sample.status() == AudioDirectionSample.Status.UNSUPPORTED) {
            updateStableDirection(Float.NaN);
        }
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

    /** 供组合层一次回调两处UI；嵌套事件和行均不可变，后续修订不会改变旧快照。 */
    public MeetingDisplaySnapshot snapshot() {
        LinkedHashMap<String, TranslationSegment> bySource = new LinkedHashMap<>();
        for (TranslationSegment translation : translations.values()) bySource.put(translation.sourceId, translation);
        List<MeetingDisplaySnapshot.Row> rows = new ArrayList<>();
        for (Segment source : segments.values()) rows.add(snapshotRow(source.id, source, bySource.remove(source.id)));
        for (String id : bySource.keySet()) rows.add(snapshotRow(id, null, bySource.get(id)));
        return new MeetingDisplaySnapshot(bilingual, languageA, languageB, swapped, rows,
                latestTranscriptSegmentId);
    }

    private MeetingDisplaySnapshot.Row snapshotRow(String id, Segment source, TranslationSegment translation) {
        DisplayRouter.Route route = bilingual ? router.existingRoute(id) : null;
        int side = route == null ? 0 : route.side();
        return new MeetingDisplaySnapshot.Row(id, source == null ? null : source.event,
                translation == null ? null : translation.event,
                side == 1 ? MeetingDisplaySnapshot.Side.A : side == 2 ? MeetingDisplaySnapshot.Side.B
                        : MeetingDisplaySnapshot.Side.UNRESOLVED,
                route == null ? DisplayRouter.Basis.LANGUAGE_UNRESOLVED : route.basis());
    }

    public String transcript() { return transcript; }

    public float transcriptAlpha() { return finalTranscript ? 1.0f : 0.8f; }

    public String elapsed(long elapsedRealtimeMs) {
        long seconds = Math.max(0L, (elapsedRealtimeMs - startedAtElapsedMs) / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600L,
                (seconds / 60L) % 60L, seconds % 60L);
    }
}
