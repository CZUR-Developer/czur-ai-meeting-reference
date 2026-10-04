package com.czur.aimeeting.reference.meeting;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一场双语会议的左右分栏决策。栏位 1/2 对应会前语言 A/B；方向明确区优先，方向
 * 模糊或不可用时才按模型语种、原文字形降级。首次显示后按 segmentId 锁定，后续
 * partial/final 不搬栏；交换按钮只改变左右呈现，不改变已锁定的语义栏位。
 *
 * <p>P5 使用可控输入验证本规则；设备真实方向角由 P6 SDK 在字幕事件到达前调用
 * {@link #updateStableAngle(float)} 或 {@link #setSegmentDirection(String, int)} 提供。
 * 本类不会把无效角度 -1/361 当成有效方向，也不声称已完成实体 DOA 验收。</p>
 */
public final class DisplayRouter {
    public enum Basis { SEGMENT_DIRECTION, STABLE_DIRECTION, MODEL_LANGUAGE,
        TEXT_LANGUAGE, LANGUAGE_UNRESOLVED }

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

    public Route route(String segmentId, String modelLanguage, String sourceText) {
        Route previous = locked.get(segmentId);
        if (previous != null) return previous;
        Integer segmentSide = segmentDirections.remove(segmentId);
        Route decided;
        if (segmentSide != null) {
            decided = new Route(segmentSide, Basis.SEGMENT_DIRECTION);
        } else if (stableAngle >= 180f && stableAngle <= 260f) {
            decided = new Route(1, Basis.STABLE_DIRECTION);
        } else if (stableAngle >= 280f && stableAngle <= 360f) {
            decided = new Route(2, Basis.STABLE_DIRECTION);
        } else {
            int byModel = languageSide(modelLanguage);
            int byText = textSide(sourceText);
            decided = byModel != 0 ? new Route(byModel, Basis.MODEL_LANGUAGE)
                    : byText != 0 ? new Route(byText, Basis.TEXT_LANGUAGE)
                    : new Route(1, Basis.LANGUAGE_UNRESOLVED);
        }
        locked.put(segmentId, decided);
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

    /** 只为当前可见的中英配置识别显著字形；其他语种由模型语种或会前 A 兜底。 */
    private int textSide(String text) {
        if (text == null) return 0;
        int han = 0;
        int latin = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++;
            else if ((codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= 'a' && codePoint <= 'z')) latin++;
            offset += Character.charCount(codePoint);
        }
        if (han > latin && han > 0) return languageSide("zh");
        if (latin > han && latin > 0) return languageSide("en");
        return 0;
    }

    private static String normalize(String language) {
        if (language == null) return "";
        String base = language.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        return switch (base) {
            case "cn" -> "zh";
            case "jp" -> "ja";
            case "kr" -> "ko";
            default -> base;
        };
    }
}
