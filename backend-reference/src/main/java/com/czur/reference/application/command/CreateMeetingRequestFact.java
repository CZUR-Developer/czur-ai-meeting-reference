package com.czur.reference.application.command;

import java.util.Objects;

/**
 * 完整 Create 请求的不可变规范事实。
 *
 * <p>本事实把提交时的 {@link CreateMeetingRequestSnapshot 请求快照} 与该快照
 * 的规范摘要、编码版本绑定在一起。它不是只保存一个摘要字符串：构造器会使用
 * {@link MeetingRequestCanonicalizer#digest(CreateMeetingRequestSnapshot)} 重新计算
 * 摘要，并要求结果与调用方传入的 {@code canonicalRequestDigest} 使用严格的
 * {@link String#equals(Object)} 完全相等。这样，错误摘要、来自另一份请求的摘要、
 * 或被替换过字段的快照都会在事实边界被拒绝，而不会等到持久化或重放时才发现。
 * </p>
 *
 * <p>{@code schemaVersion} 只接受当前完整 Create 快照契约的
 * {@link MeetingRequestCanonicalizer#CREATE_SNAPSHOT_SCHEMA_VERSION}。版本不匹配
 * 时拒绝构造，避免把未来或旧编码误解释为当前事实。这个类型只包含已经存在的
 * 请求快照和规范摘要元数据，不引入凭据、Token、Provider 响应、数据库句柄或
 * 其它秘密；它也没有任何数据库、迁移、工作单元或 application service 调用。
 * </p>
 *
 * <p>请求快照本身在其构造器中完成防御性复制并保持不可变；本事实只保存该安全
 * 值对象的引用。规范摘要是幂等边界中的敏感标识，{@link #toString()} 只报告其
 * 存在性和版本，不输出快照字段或摘要正文，以免诊断日志意外暴露请求内容。</p>
 *
 * @param requestSnapshot 已冻结的完整 Create 请求字段
 * @param canonicalRequestDigest 按当前快照编码重新计算出的 64 位摘要文本
 * @param schemaVersion 完整 Create 快照规范的版本，必须等于当前支持版本
 */
public final class CreateMeetingRequestFact {
    private final CreateMeetingRequestSnapshot requestSnapshot;
    private final String canonicalRequestDigest;
    private final int schemaVersion;

    /**
     * 创建并校验完整 Create 请求事实。
     *
     * <p>校验顺序先确认版本，再对非空摘要进行严格等值校验。摘要不会被修剪、
     * 大小写归一化或重新写回字段；事实始终保留调用方提供的、且已通过精确匹配
     * 的摘要文本。</p>
     *
     * @throws NullPointerException requestSnapshot 为空
     * @throws IllegalArgumentException 版本、摘要为空或摘要与快照不一致
     */
    public CreateMeetingRequestFact(
            CreateMeetingRequestSnapshot requestSnapshot,
            String canonicalRequestDigest,
            int schemaVersion) {
        this.requestSnapshot = Objects.requireNonNull(requestSnapshot, "requestSnapshot");
        if (schemaVersion != MeetingRequestCanonicalizer.CREATE_SNAPSHOT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported Create snapshot schemaVersion: " + schemaVersion);
        }
        requireDigestText(canonicalRequestDigest);
        String recomputedDigest = MeetingRequestCanonicalizer.digest(requestSnapshot);
        if (!recomputedDigest.equals(canonicalRequestDigest)) {
            throw new IllegalArgumentException(
                    "canonicalRequestDigest does not match requestSnapshot");
        }
        this.canonicalRequestDigest = canonicalRequestDigest;
        this.schemaVersion = schemaVersion;
    }

    /** 返回已经冻结的完整 Create 请求快照。 */
    public CreateMeetingRequestSnapshot requestSnapshot() {
        return requestSnapshot;
    }

    /**
     * 返回与快照严格匹配的规范摘要。
     *
     * <p>调用方可以把它作为幂等事实的稳定键使用，但不应把正文写入日志或错误
     * 消息。该方法不会重新计算或改变事实。</p>
     */
    public String canonicalRequestDigest() {
        return canonicalRequestDigest;
    }

    /** 返回构造时已验证的完整 Create 快照 schema 版本。 */
    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * 日志安全的事实摘要；不输出快照字段、幂等键、设备标识或摘要正文。
     */
    @Override
    public String toString() {
        return "CreateMeetingRequestFact[schemaVersion=" + schemaVersion
                + ", requestSnapshotPresent=true"
                + ", canonicalRequestDigestPresent=true]";
    }

    private static void requireDigestText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("canonicalRequestDigest must not be blank");
        }
    }
}
