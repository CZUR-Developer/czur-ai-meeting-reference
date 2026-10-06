package com.czur.aimeeting.client;

import com.czur.aimeeting.model.SummaryResult;
import java.util.List;
import java.util.Set;

/**
 * 只包含 Manifest 中 READY 的最终产物。章节存在性由 artifactTypes 决定，
 * 不从会前模式猜测；全文保留无分段产物的 fallback。PDF/图片绘制属于 App。
 */
public record MeetingResultContent(Set<String> artifactTypes, String transcriptText,
        List<FinalTranscriptSegment> transcriptSegments, List<FinalTranslation> translations,
        SummaryResult summary, List<PresentationFrame> presentationFrames) {
    public MeetingResultContent {
        artifactTypes = Set.copyOf(artifactTypes);
        transcriptSegments = List.copyOf(transcriptSegments);
        translations = List.copyOf(translations);
        presentationFrames = List.copyOf(presentationFrames);
    }
}
