package com.czur.reference.application.speaker;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.domain.capability.CapabilityDescriptor;
import com.czur.reference.domain.capability.CapabilityIds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考默认会后人物实现：MySQL 标准人物映射＋FFmpeg 样本＋可配置讯飞供应商。
 *
 * <p>插件独立于实时 ASR Adapter，可与任何能提供本场 speaker 与时间轴的模型
 * 组合。人物库只按部署和主体复用，默认分组前缀为 reference，避免与 CZUR
 * 旧服务分组混用。未来 CZUR 可注入自己的 Provider，继续使用其既有人员库，
 * 不需要使用本实现或重复建立讯飞特征。</p>
 */
public final class ReferenceXfyunSpeakerIdentityProvider implements SpeakerIdentityProvider {
    private static final Logger LOGGER = Logger.getLogger(ReferenceXfyunSpeakerIdentityProvider.class.getName());
    private static final String PROVIDER = "xfyun";
    private final ReferenceSpeakerIdentityConfig config;
    private final JdbcReferenceSpeakerPersons persons;
    private final ArtifactStore artifacts;
    private final XfyunSpeakerClient client;

    public ReferenceXfyunSpeakerIdentityProvider(ReferenceSpeakerIdentityConfig config,
            JdbcReferenceSpeakerPersons persons, ArtifactStore artifacts, Clock clock) {
        this(config, persons, artifacts, new XfyunSpeakerClient(config, clock));
    }

    ReferenceXfyunSpeakerIdentityProvider(ReferenceSpeakerIdentityConfig config,
            JdbcReferenceSpeakerPersons persons, ArtifactStore artifacts, XfyunSpeakerClient client) {
        this.config = config;
        this.persons = persons;
        this.artifacts = artifacts;
        this.client = client;
    }

    @Override public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("reference-xfyun-speaker", "1", 1,
                Set.of(CapabilityIds.SPEAKER_IDENTITY_CROSS_MEETING));
    }

    @Override public Identification identify(MeetingSpeakers request) throws Exception {
        var entries = persons.list(request.deploymentScope(), request.subjectScope(), PROVIDER);
        if (entries.isEmpty()) return new Identification(Map.of());
        Map<String, Person> matches = new HashMap<>();
        try (var samples = new FfmpegSpeakerSamples(request, artifacts, config)) {
            for (var speaker : request.speakers()) {
                try {
                    var matched = client.identify(entries.getFirst().groupId(), samples.sample(speaker));
                    if (matched == null || matched.score() < config.matchThreshold()) continue;
                    entries.stream().filter(entry -> entry.featureId().equals(matched.featureId()))
                            .findFirst().ifPresent(entry -> matches.put(speaker.sessionSpeakerId(), entry.person()));
                } catch (SampleUnavailable unavailable) {
                    LOGGER.log(Level.FINE, "本场发言人尚无足够声纹样本，保留未识别");
                }
            }
        }
        return new Identification(matches);
    }

    @Override public Person enroll(Enrollment request) throws Exception {
        var meeting = request.meeting();
        List<JdbcReferenceSpeakerPersons.Entry> entries = persons.list(
                meeting.deploymentScope(), meeting.subjectScope(), PROVIDER);
        String id = request.existingPerson() == null
                ? "person_" + digest(meeting.deploymentScope() + "\0" + meeting.subjectScope()
                        + "\0" + meeting.meetingId() + "\0" + request.sessionSpeakerId()).substring(0, 32)
                : request.existingPerson().personId();
        var existing = entries.stream().filter(entry -> entry.person().personId().equals(id)).findFirst().orElse(null);
        Person person = new Person(id, request.displayName());
        if (existing != null) {
            persons.save(meeting.deploymentScope(), meeting.subjectScope(),
                    new JdbcReferenceSpeakerPersons.Entry(person, PROVIDER, existing.groupId(), existing.featureId()));
            return person;
        }
        var speaker = meeting.speakers().stream().filter(item ->
                request.sessionSpeakerId().equals(item.sessionSpeakerId())).findFirst().orElseThrow();
        String groupId = config.groupPrefix() + digest(meeting.deploymentScope()
                + "\0" + meeting.subjectScope()).substring(0, 32 - config.groupPrefix().length());
        String featureId = "vp" + digest(id).substring(0, 30);
        try (var samples = new FfmpegSpeakerSamples(meeting, artifacts, config)) {
            byte[] sample = samples.sample(speaker);
            client.ensureGroup(groupId);
            client.createFeature(groupId, featureId, sample);
        }
        persons.save(meeting.deploymentScope(), meeting.subjectScope(),
                new JdbcReferenceSpeakerPersons.Entry(person, PROVIDER, groupId, featureId));
        LOGGER.log(Level.INFO, "参考人物样本已注册 provider=xfyun");
        return person;
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
