package com.czur.reference.application.port.speaker;

import com.czur.reference.application.command.MeetingCommandContext;

/**
 * 会后人工名称编辑扩展口，与模型分人和跨会议声纹分开。
 * SINGLE 只修改 segmentId 对应的原文段；ALL 修改本场同一 sessionSpeakerId 的所有段。
 * remember 仅表示另行登记下次识别，不扩大本场修改范围。实现必须先保存本场名称，
 * 再执行可选声纹登记；登记失败仍返回已保存及其独立状态。匿名结果链接没有写权限。
 */
@FunctionalInterface
public interface SpeakerNameEditPort {
    Result edit(MeetingCommandContext context, String meetingId, String sessionSpeakerId, Edit edit);

    enum Scope { SINGLE, ALL }
    record Edit(String displayName, Scope scope, String segmentId, boolean remember) {
        public Edit {
            if (displayName == null || displayName.isBlank() || scope == null
                    || displayName.codePointCount(0, displayName.length()) > 128
                    || scope == Scope.SINGLE && (segmentId == null || segmentId.isBlank()))
                throw new IllegalArgumentException("speaker edit is invalid");
            displayName = displayName.strip();
        }
    }
    sealed interface Result permits Updated, Rejected { }
    record Updated(int manifestRevision, int changedSegments, Registration registration) implements Result { }
    record Rejected(Reason reason) implements Result { }
    enum Registration { NOT_REQUESTED, REGISTERED, SAMPLE_UNAVAILABLE, NOT_GRANTED, UNAVAILABLE }
    enum Reason { NOT_FOUND, ACCESS_DENIED, INVALID_STATE, SEGMENT_NOT_FOUND, UNAVAILABLE }
}
