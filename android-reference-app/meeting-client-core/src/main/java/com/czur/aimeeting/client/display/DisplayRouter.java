package com.czur.aimeeting.client.display;

import java.util.HashMap;
import java.util.Map;

/**
 * 一场双语会议的左右分栏决策。栏位 1/2 对应会前语言 A/B；方向明确区优先，方向
 * 模糊或不可用时才按模型语种、原文字形降级。首次显示后按 segmentId 锁定，后续
 * partial/final 不搬栏。模型语种与文本都无法判断时返回未定侧但不锁定，允许同一
 * 片段后续修订或方向样本再定栏；交换按钮只改变呈现，不改变已锁定的语义栏位。
 *
 * <p>P5 使用可控输入验证本规则；设备真实方向角由 P6 SDK 在字幕事件到达前调用
 * {@link #updateStableAngle(float)} 或 {@link #setSegmentDirection(String, int)} 提供。
 * 本类不会把无效角度 -1/361 当成有效方向，也不声称已完成实体 DOA 验收。</p>
 */
public final class DisplayRouter {
    public enum Basis { SEGMENT_DIRECTION, STABLE_DIRECTION, MODEL_LANGUAGE,
        TEXT_LANGUAGE, LANGUAGE_UNRESOLVED }

    /** side 0=暂未定栏，1=语言 A 栏，2=语言 B 栏；只有 1/2 会被锁定。 */
    public record Route(int side, Basis basis) {
        /** 无有效方向区参与本次分栏，属于语种降级或语种未知兜底。 */
        public boolean languageFallback() {
            return basis == Basis.MODEL_LANGUAGE || basis == Basis.TEXT_LANGUAGE
                    || basis == Basis.LANGUAGE_UNRESOLVED;
        }
    }

    private final String languageA;
    private final String languageB;
    private final Map<String, Route> locked = new HashMap<>();
    private final Map<String, Integer> segmentDirections = new HashMap<>();
    private final Map<String, Float> segmentAngles = new HashMap<>();
    private float stableAngle = Float.NaN;

    public DisplayRouter(String languageA, String languageB) {
        this.languageA = normalize(languageA);
        this.languageB = normalize(languageB);
    }

    /** 稳定角度由音频 SDK 生产，0～360° 有效；-1/361/NaN 表示当前不可用。 */
    public void updateStableAngle(float angle) {
        stableAngle = angle >= 0f && angle <= 360f ? angle : Float.NaN;
    }

    /** 可选的片段级方向优先于全局稳定角度；必须在该片段首次显示前提供。 */
    public void setSegmentDirection(String segmentId, int side) {
        if ((side == 1 || side == 2) && !locked.containsKey(segmentId)) {
            segmentDirections.put(segmentId, side);
        }
    }

    /** 句开始时保存角度，包括“无方向”；之后不能被下一人或下一句的方向覆盖。 */
    public void onSegmentStarted(String segmentId) {
        segmentAngles.putIfAbsent(segmentId, stableAngle);
    }

    /** 只针对句开始快照中的有效模糊区等待模型语种，与国内听悟门规则一致。 */
    public boolean isLanguageBased(String segmentId) {
        float angle = segmentAngles.getOrDefault(segmentId, stableAngle);
        return angle >= 0f && angle < 180f || angle > 260f && angle < 280f;
    }

    public boolean hasConfiguredLanguage(String language) { return languageSide(language) != 0; }

    /** 等待期结束或 Stop 时本地二选一；即使数字同分也选择 A，但不改真实模型语种。 */
    public Route forceLocalFallback(String segmentId, String text) {
        Route previous = locked.get(segmentId);
        if (previous != null) return previous;
        BilingualLanguageResolver.Resolution resolution =
                BilingualLanguageResolver.resolve(text, languageA, languageB);
        Route route = new Route(resolution.language().equals(languageA) ? 1 : 2, Basis.TEXT_LANGUAGE);
        locked.put(segmentId, route);
        return route;
    }

    public Route route(String segmentId, String modelLanguage, String sourceText) {
        Route previous = locked.get(segmentId);
        if (previous != null) return previous;
        Integer segmentSide = segmentDirections.remove(segmentId);
        onSegmentStarted(segmentId);
        float angle = segmentAngles.get(segmentId);
        Route decided;
        if (segmentSide != null) {
            decided = new Route(segmentSide, Basis.SEGMENT_DIRECTION);
        } else if (angle >= 180f && angle <= 260f) {
            decided = new Route(1, Basis.STABLE_DIRECTION);
        } else if (angle >= 280f && angle <= 360f) {
            decided = new Route(2, Basis.STABLE_DIRECTION);
        } else {
            int byModel = languageSide(modelLanguage);
            int byText = textSide(sourceText);
            decided = byModel != 0 ? new Route(byModel, Basis.MODEL_LANGUAGE)
                    : byText != 0 ? new Route(byText, Basis.TEXT_LANGUAGE)
                    : new Route(0, Basis.LANGUAGE_UNRESOLVED);
        }
        if (decided.side() != 0) locked.put(segmentId, decided);
        return decided;
    }

    public Route existingRoute(String segmentId) { return locked.get(segmentId); }

    private int languageSide(String language) {
        String normalized = normalize(language);
        if (normalized.isEmpty() || normalized.equals("und") || normalized.equals("mul")) return 0;
        if (normalized.equals(languageA)) return 1;
        if (normalized.equals(languageB)) return 2;
        return 0;
    }

    /** 复用国内多语种二选一；完全无文字线索时继续等后续事实，不提前锁数字 partial。 */
    private int textSide(String text) {
        BilingualLanguageResolver.Resolution resolution =
                BilingualLanguageResolver.resolve(text, languageA, languageB);
        return resolution.scoreA() == 0 && resolution.scoreB() == 0 ? 0
                : resolution.language().equals(languageA) ? 1 : 2;
    }

    private static String normalize(String language) {
        return BilingualLanguageResolver.normalize(language);
    }
}
