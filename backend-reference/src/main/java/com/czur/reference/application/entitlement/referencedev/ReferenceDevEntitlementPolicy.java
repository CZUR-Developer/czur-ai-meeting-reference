package com.czur.reference.application.entitlement.referencedev;

import com.czur.reference.application.entitlement.FixedEntitlementPolicy;
import com.czur.reference.application.entitlement.FixedEntitlementPolicyConfig;
import com.czur.reference.application.port.entitlement.AuthorizeStartRequest;
import com.czur.reference.application.port.entitlement.EntitlementIdGenerator;
import com.czur.reference.application.port.entitlement.EntitlementPolicy;
import com.czur.reference.application.port.entitlement.EntitlementProviderResult;
import com.czur.reference.application.port.entitlement.MeetingGrant;
import com.czur.reference.application.port.entitlement.ReserveUsageRequest;
import com.czur.reference.application.port.entitlement.UsageReservation;
import java.time.Clock;

/** 参考策略的兼容名称；授权和预占算法仅在共享FixedEntitlementPolicy中维护。 */
public final class ReferenceDevEntitlementPolicy implements EntitlementPolicy {
    private final FixedEntitlementPolicy delegate;

    public ReferenceDevEntitlementPolicy(ReferenceDevEntitlementPolicyConfig config,
            Clock clock, EntitlementIdGenerator ids) {
        this(new FixedEntitlementPolicyConfig(config.deploymentScope(), config.profileId(),
                config.profileRevision(), config.supportedCapabilities(), config.maxDurationMs(),
                config.grantTtl(), config.reservationTtl()), clock, ids);
    }

    public ReferenceDevEntitlementPolicy(FixedEntitlementPolicyConfig config,
            Clock clock, EntitlementIdGenerator ids) {
        delegate = new FixedEntitlementPolicy(config, clock, ids);
    }

    @Override public EntitlementProviderResult<MeetingGrant> authorizeStart(AuthorizeStartRequest request) {
        return delegate.authorizeStart(request);
    }
    @Override public EntitlementProviderResult<UsageReservation> prepareReservation(ReserveUsageRequest request) {
        return delegate.prepareReservation(request);
    }
}
