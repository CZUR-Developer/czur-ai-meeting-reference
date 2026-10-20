package com.czur.reference.application.http;

import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.speaker.SpeakerNameEditPort;
import com.czur.reference.application.port.speaker.SpeakerResultAccess;
import java.util.Set;

/**
 * 可替换的会后发言人编辑授权策略，不影响 App 登录和会议控制权限。
 * 参考宿主选择有效结果链接；伙伴可选择 ownerOnly 或实现自己的审批／登录策略。
 * requiresLogin 只指导默认 Web 是否展示登录表单，实际权限始终由 resolve 校验。
 */
@FunctionalInterface
public interface SpeakerResultAccessPolicy {
    Resolution resolve(HttpRequest request, String meetingId);

    default boolean requiresLogin() { return true; }

    sealed interface Resolution permits Allowed, Rejected { }
    record Allowed(SpeakerResultAccess access) implements Resolution { }
    record Rejected(HttpResponse response) implements Resolution { }

    /** 依赖全部来自同一宿主装配，不另建数据库或身份 Provider。 */
    @FunctionalInterface
    interface Factory {
        SpeakerResultAccessPolicy create(AuthenticatedMeetingHttpContextResolver identity,
                MeetingManifestPort manifest, String deploymentScope, HttpErrorMapper errors);
    }

    /** 旧宿主保持 Bearer＋会议所有者语义。 */
    static SpeakerResultAccessPolicy ownerOnly(AuthenticatedMeetingHttpContextResolver identity,
            HttpErrorMapper errors) {
        return (request, meetingId) -> {
            if (!request.query().isEmpty()) return new Rejected(errors.invalidRoute());
            var resolution = identity.forClientWrite(request);
            if (resolution instanceof AuthenticatedMeetingHttpContextResolver.Rejected rejected)
                return new Rejected(rejected.response());
            return new Allowed(SpeakerResultAccess.owner(
                    ((AuthenticatedMeetingHttpContextResolver.Ready) resolution).context()));
        };
    }

    /**
     * 与结果读取复用同一 Manifest 端口，统一检查 genTime 有效期、删除及完成状态。
     * 通过后只授权此部署的这一场结果；不会发行或伪造账号 Token。
     */
    static SpeakerResultAccessPolicy resultLink(AuthenticatedMeetingHttpContextResolver identity,
            MeetingManifestPort manifest,
            String deploymentScope, HttpErrorMapper errors) {
        return new SpeakerResultAccessPolicy() {
            @Override public boolean requiresLogin() { return false; }
            @Override public Resolution resolve(HttpRequest request, String meetingId) {
                // 保留既有不带 genTime 的 Bearer 调用；网页默认只使用链接分支。
                if (request.query().isEmpty()) return ownerOnly(identity, errors).resolve(request, meetingId);
                if (manifest == null) return new Rejected(errors.notImplemented());
                if (!request.query().keySet().equals(Set.of("genTime")))
                    return new Rejected(errors.invalidRoute());
                try {
                    var link = new MeetingManifestPort.Request(meetingId,
                            Long.parseLong(request.query().get("genTime")));
                    var result = manifest.get(link);
                    if (result instanceof MeetingManifestPort.Found) {
                        return new Allowed((id, deployment, subject) ->
                                meetingId.equals(id) && deploymentScope.equals(deployment));
                    }
                    if (result instanceof MeetingManifestPort.Pending)
                        return new Rejected(errors.speakerEditingFailure(SpeakerNameEditPort.Reason.INVALID_STATE));
                    return new Rejected(errors.manifest(result));
                } catch (RuntimeException failure) {
                    return new Rejected(errors.exception(failure));
                }
            }
        };
    }
}
