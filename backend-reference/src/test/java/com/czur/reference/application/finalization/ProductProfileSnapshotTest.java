package com.czur.reference.application.finalization;

import com.czur.reference.domain.capability.ProductProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Product Profile 完整冻结、独立恢复、不可变性和诊断脱敏门禁。 */
class ProductProfileSnapshotTest {
    private static final String TRANSCRIPT = "secret.capability.transcript";
    private static final String SUMMARY = "secret.capability.summary";
    private static final String FALLBACK = "secret.capability.fallback";

    @Test
    void capturesEveryProfileFieldAndRestoresEquivalentDomainObject() {
        ProductProfile original = completeProfile();

        ProductProfileSnapshot snapshot = ProductProfileSnapshot.from(original);
        ProductProfile restored = snapshot.toProductProfile();

        assertEquals(original.id(), restored.id());
        assertEquals(original.revision(), restored.revision());
        assertEquals(original.requiredCapabilities(), restored.requiredCapabilities());
        assertEquals(original.optionalCapabilities(), restored.optionalCapabilities());
        assertEquals(original.dependencies(), restored.dependencies());
        assertEquals(original.missingPolicies(), restored.missingPolicies());
        assertEquals(original.fallbackCapabilities(), restored.fallbackCapabilities());
        assertEquals(original.affectedWorkflows(), restored.affectedWorkflows());
        assertEquals(original.producedArtifacts(), restored.producedArtifacts());
        assertEquals(snapshot, ProductProfileSnapshot.from(restored));
    }

    @Test
    void constructorDeepCopiesInputsAndAllViewsAreImmutable() {
        Set<String> required = new HashSet<>(Set.of(TRANSCRIPT));
        Set<String> optional = new HashSet<>(Set.of(SUMMARY, FALLBACK));
        Map<String, Set<String>> dependencies = new HashMap<>();
        dependencies.put(SUMMARY, new HashSet<>(Set.of(TRANSCRIPT)));
        Map<String, ProductProfile.MissingCapabilityPolicy> policies = new HashMap<>();
        policies.put(SUMMARY, ProductProfile.MissingCapabilityPolicy.USE_FALLBACK);
        Map<String, String> fallbacks = new HashMap<>();
        fallbacks.put(SUMMARY, FALLBACK);
        Map<String, List<String>> workflows = new HashMap<>();
        workflows.put(TRANSCRIPT, new ArrayList<>(List.of("secret.workflow.transcript")));
        workflows.put(SUMMARY, new ArrayList<>(List.of("secret.workflow.summary")));
        workflows.put(FALLBACK, new ArrayList<>(List.of("secret.workflow.fallback")));
        Map<String, List<String>> artifacts = new HashMap<>();
        artifacts.put(TRANSCRIPT, new ArrayList<>(List.of("secret.artifact.transcript")));
        artifacts.put(SUMMARY, new ArrayList<>(List.of("secret.artifact.summary")));
        artifacts.put(FALLBACK, new ArrayList<>(List.of("secret.artifact.fallback")));

        ProductProfileSnapshot snapshot = new ProductProfileSnapshot(
                "secret.profile/v4", 4, required, optional, dependencies, policies,
                fallbacks, workflows, artifacts);
        required.clear();
        optional.clear();
        dependencies.get(SUMMARY).clear();
        workflows.get(TRANSCRIPT).clear();
        artifacts.clear();

        assertEquals(Set.of(TRANSCRIPT), snapshot.requiredCapabilities());
        assertEquals(Set.of(SUMMARY, FALLBACK), snapshot.optionalCapabilities());
        assertEquals(Set.of(TRANSCRIPT), snapshot.dependencies().get(SUMMARY));
        assertEquals(List.of("secret.workflow.transcript"),
                snapshot.affectedWorkflows().get(TRANSCRIPT));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.requiredCapabilities().add("another"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.dependencies().get(SUMMARY).add(FALLBACK));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.affectedWorkflows().get(TRANSCRIPT).add("another"));
    }

    @Test
    void diagnosticsAndValidationFailuresDoNotRevealIdentifiersOrMappings() {
        ProductProfileSnapshot snapshot = ProductProfileSnapshot.from(completeProfile());
        String diagnostic = snapshot.toString();
        assertRedacted(diagnostic);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProductProfileSnapshot(
                        "secret.profile/v4",
                        4,
                        Set.of(TRANSCRIPT),
                        Set.of(SUMMARY),
                        Map.of("undeclared.secret.capability", Set.of(TRANSCRIPT)),
                        Map.of(),
                        Map.of(),
                        Map.of(TRANSCRIPT, List.of("secret.workflow.transcript")),
                        Map.of(TRANSCRIPT, List.of("secret.artifact.transcript"))));
        assertEquals("Product profile snapshot is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertRedacted(failure.toString());
    }

    @Test
    void rejectsDuplicateWorkflowAndArtifactMappingsAtSnapshotBoundary() {
        assertSnapshotFailure(() -> new ProductProfileSnapshot(
                "secret.profile/v4",
                4,
                Set.of(TRANSCRIPT),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(TRANSCRIPT, List.of(
                        "secret.workflow.duplicate", "secret.workflow.duplicate")),
                Map.of(TRANSCRIPT, List.of("secret.artifact.transcript"))));
        assertSnapshotFailure(() -> new ProductProfileSnapshot(
                "secret.profile/v4",
                4,
                Set.of(TRANSCRIPT),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(TRANSCRIPT, List.of("secret.workflow.transcript")),
                Map.of(TRANSCRIPT, List.of(
                        "secret.artifact.duplicate", "secret.artifact.duplicate"))));
    }

    @Test
    void rejectsMalformedUtf16AcrossProfileIdentityCapabilitiesAndMappings() {
        assertSnapshotFailure(() -> new ProductProfileSnapshot(
                "secret.profile/invalid-\uD800",
                4,
                Set.of(TRANSCRIPT),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(TRANSCRIPT, List.of("secret.workflow.transcript")),
                Map.of(TRANSCRIPT, List.of("secret.artifact.transcript"))));

        String malformedCapability = "secret.capability.invalid-\uD800";
        assertSnapshotFailure(() -> new ProductProfileSnapshot(
                "secret.profile/v4",
                4,
                Set.of(malformedCapability),
                Set.of(),
                Map.of(malformedCapability, Set.of()),
                Map.of(malformedCapability, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(),
                Map.of(malformedCapability, List.of("secret.workflow.transcript")),
                Map.of(malformedCapability, List.of("secret.artifact.transcript"))));

        assertSnapshotFailure(() -> new ProductProfileSnapshot(
                "secret.profile/v4",
                4,
                Set.of(TRANSCRIPT),
                Set.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(TRANSCRIPT, List.of("secret.workflow.invalid-\uD800")),
                Map.of(TRANSCRIPT, List.of("secret.artifact.transcript"))));
    }

    private static ProductProfile completeProfile() {
        return new ProductProfile(
                "secret.profile/v4",
                4,
                Set.of(TRANSCRIPT),
                Set.of(SUMMARY, FALLBACK),
                Map.of(SUMMARY, Set.of(TRANSCRIPT)),
                Map.of(
                        SUMMARY, ProductProfile.MissingCapabilityPolicy.USE_FALLBACK,
                        FALLBACK, ProductProfile.MissingCapabilityPolicy.DISABLE),
                Map.of(SUMMARY, FALLBACK),
                Map.of(
                        TRANSCRIPT, List.of("secret.workflow.transcript"),
                        SUMMARY, List.of("secret.workflow.summary"),
                        FALLBACK, List.of("secret.workflow.fallback")),
                Map.of(
                        TRANSCRIPT, List.of("secret.artifact.transcript"),
                        SUMMARY, List.of("secret.artifact.summary"),
                        FALLBACK, List.of("secret.artifact.fallback")));
    }

    private static void assertRedacted(String diagnostic) {
        assertFalse(diagnostic.contains("secret.profile"));
        assertFalse(diagnostic.contains("secret.capability"));
        assertFalse(diagnostic.contains("secret.workflow"));
        assertFalse(diagnostic.contains("secret.artifact"));
        assertFalse(diagnostic.contains("undeclared"));
    }

    private static void assertSnapshotFailure(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Product profile snapshot is invalid", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertRedacted(failure.toString());
    }
}
