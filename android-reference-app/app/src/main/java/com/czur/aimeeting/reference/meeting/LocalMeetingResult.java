package com.czur.aimeeting.reference.meeting;

import java.util.List;
import org.json.JSONObject;

/** 只包含本场 Manifest 中 READY 的最终产物；缺席的章节保持 null/空列表。 */
public record LocalMeetingResult(String title, JSONObject transcript, JSONObject translation,
        JSONObject summary, List<JSONObject> presentationCaptures) {
    public LocalMeetingResult {
        presentationCaptures = List.copyOf(presentationCaptures);
    }
}
