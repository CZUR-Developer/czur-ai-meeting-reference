package com.czur.aimeeting.reference.meeting;

import com.czur.aimeeting.client.MeetingClientConfig;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.server.HttpMeetingServerClient;
import com.czur.aimeeting.client.server.MeetingServerClient;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.SummaryResult;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.io.IOException;
import java.util.List;

/** P6-6 迁移期薄转发：协议唯一实现已在 core，P6-7 Service 改组合入口后删除。 */
public final class HttpReferenceMeetingClient implements ReferenceMeetingClient {
    private final MeetingServerClient delegate;

    public HttpReferenceMeetingClient(String baseUrl, String bearerToken) {
        delegate = new HttpMeetingServerClient(MeetingClientConfig.reference(baseUrl, bearerToken));
    }
    @Override public CreatedMeeting createMeeting(MeetingSetup setup) throws IOException {
        var created = delegate.createMeeting(setup.toOptions());
        return new CreatedMeeting(created.meetingId(), created.modelSession(),
                created.capabilities(), created.maxMeetingDurationMs());
    }
    @Override public void startClient(String meetingId) throws IOException {
        delegate.startClient(meetingId);
    }
    @Override public ModelSessionGrant requestModelSession(String meetingId) throws IOException {
        return delegate.requestModelSession(meetingId);
    }
    @Override public SummarySessionGrant requestSummarySession(String meetingId) throws IOException {
        return delegate.requestSummarySession(meetingId);
    }
    @Override public void appendTranscript(String meetingId, int sequence, TranscriptEvent event)
            throws IOException { delegate.appendTranscript(meetingId, sequence, event); }
    @Override public void appendTranslation(String meetingId, int sequence, TranslationEvent event)
            throws IOException { delegate.appendTranslation(meetingId, sequence, event); }
    @Override public void appendPresentation(String meetingId, int sequence, long offsetMs, byte[] jpeg)
            throws IOException { delegate.appendPresentation(meetingId, sequence, offsetMs, jpeg); }
    @Override public void submitSummary(String meetingId, SummaryResult summary) throws IOException {
        delegate.submitSummary(meetingId, summary);
    }
    @Override public boolean stopMeeting(String meetingId) throws IOException {
        return delegate.stopMeeting(meetingId).accepted();
    }
    @Override public void sendResultEmail(String meetingId, List<String> recipients, String resultUrl)
            throws IOException { delegate.sendResultEmail(meetingId, recipients, resultUrl); }
    @Override public LocalMeetingResult readLocalResult(String meetingId, long genTime)
            throws IOException {
        MeetingResult result = delegate.readResult(meetingId, genTime, true);
        return new LocalMeetingResult(result.title(), result.content());
    }
}
