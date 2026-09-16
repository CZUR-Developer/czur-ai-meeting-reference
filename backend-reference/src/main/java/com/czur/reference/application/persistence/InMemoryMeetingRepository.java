package com.czur.reference.application.persistence;

import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的内存会议仓库，仅用于参考后端演示和单元测试。 */
public final class InMemoryMeetingRepository implements MeetingRepository {
    private final Map<String, MeetingAggregate.MeetingSnapshot> meetings = new ConcurrentHashMap<>();

    @Override
    public Optional<MeetingAggregate.MeetingSnapshot> find(String meetingId) {
        return Optional.ofNullable(meetings.get(meetingId));
    }

    @Override
    public Optional<MeetingAggregate.MeetingSnapshot> findByDeploymentScopeAndClientSessionId(
            String deploymentScope,
            String clientSessionId) {
        return meetings.values().stream()
                .filter(meeting -> meeting.deploymentScope().equals(deploymentScope))
                .filter(meeting -> meeting.clientSessionId().equals(clientSessionId))
                .findFirst();
    }

    @Override
    public void save(MeetingAggregate.MeetingSnapshot meeting) {
        meetings.put(meeting.meetingId(), meeting);
    }
}
