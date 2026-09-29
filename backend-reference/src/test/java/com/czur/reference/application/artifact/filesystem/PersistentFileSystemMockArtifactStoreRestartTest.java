package com.czur.reference.application.artifact.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用不同真实 JVM 证明完整发布、半写崩溃和删除 tombstone 的恢复语义。 */
class PersistentFileSystemMockArtifactStoreRestartTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void completedArtifactIsReadableAndReplayUniqueAfterTrueJvmRestart() throws Exception {
        Path root = temporaryDirectory.resolve("completed");

        assertSuccess(runProbe(root, "complete"));
        assertSuccess(runProbe(root, "verify-ready"));
        assertSuccess(runProbe(root, "replay"));

        try (var files = Files.list(root.resolve("objects"))) {
            List<Path> objects = files.toList();
            assertEquals(1, objects.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".blob")).count());
            assertEquals(1, objects.stream()
                    .filter(path -> path.getFileName().toString().endsWith(".ready")).count());
        }
    }

    @Test
    void halfWriteCrashNeverBecomesReadyAndSameIntentRecovers() throws Exception {
        Path root = temporaryDirectory.resolve("half-write");

        ProbeResult crash = runProbe(root, "crash-half");
        assertEquals(47, crash.exitCode(), crash.output());
        assertSuccess(runProbe(root, "verify-pending"));
        assertSuccess(runProbe(root, "complete"));
        assertSuccess(runProbe(root, "verify-ready"));
    }

    @Test
    void deletionTombstoneSurvivesTrueJvmRestartAndPreventsResurrection() throws Exception {
        Path root = temporaryDirectory.resolve("deleted");

        assertSuccess(runProbe(root, "complete"));
        assertSuccess(runProbe(root, "delete"));
        assertSuccess(runProbe(root, "verify-deleted"));

        try (var files = Files.list(root.resolve("objects"))) {
            assertTrue(files.findAny().isEmpty());
        }
        try (var files = Files.list(root.resolve("tombstones"))) {
            List<Path> tombstones = files.toList();
            assertEquals(1, tombstones.size());
            assertTrue(tombstones.getFirst().getFileName().toString().endsWith(".deleted"));
        }
    }

    @Test
    void deletingPublishHaltIsInspectedRecoveredAndFencesStaleWriterAcrossJvms()
            throws Exception {
        Path root = temporaryDirectory.resolve("spec-delete-deleting-halt");

        ProbeResult crash = runProbe(root, "crash-delete-deleting");
        assertEquals(47, crash.exitCode(), crash.output());
        assertSuccess(runProbe(root, "recover-deleting"));
        assertSuccess(runProbe(root, "verify-stale-writer-fenced"));
        assertSuccess(runProbe(root, "recover-deleted"));
    }

    @Test
    void deletedPublishHaltIsReadOnlyRecoverableAndUniqueAcrossJvms()
            throws Exception {
        Path root = temporaryDirectory.resolve("spec-delete-deleted-halt");

        assertSuccess(runProbe(root, "complete"));
        ProbeResult crash = runProbe(root, "crash-delete-deleted");
        assertEquals(47, crash.exitCode(), crash.output());
        assertSuccess(runProbe(root, "recover-deleted"));
        assertSuccess(runProbe(root, "verify-stale-writer-fenced"));

        try (var files = Files.list(root.resolve("objects"))) {
            assertTrue(files.findAny().isEmpty());
        }
        try (var files = Files.list(root.resolve("tombstones"))) {
            List<Path> tombstones = files.toList();
            assertEquals(1, tombstones.size());
            assertTrue(tombstones.getFirst().getFileName().toString().endsWith(".deleted"));
        }
    }

    @Test
    void twoRealJvmsUseFileLockAndHaveOneWinnerPerPublishPhase() throws Exception {
        Path root = temporaryDirectory.resolve("concurrent");
        Path barrier = temporaryDirectory.resolve("concurrent.start");
        Process first = startProbe(root, "concurrent-complete", barrier);
        Process second = startProbe(root, "concurrent-complete", barrier);
        Files.writeString(barrier, "start");

        ProbeResult firstResult = finishProbe(first, "concurrent-complete-a");
        ProbeResult secondResult = finishProbe(second, "concurrent-complete-b");
        assertSuccess(firstResult);
        assertSuccess(secondResult);
        String combined = firstResult.output() + secondResult.output();
        assertEquals(1, occurrences(combined, "prepare=APPLIED"), combined);
        assertEquals(1, occurrences(combined, "prepare=REPLAYED"), combined);
        assertEquals(1, occurrences(combined, "stage=APPLIED"), combined);
        assertEquals(1, occurrences(combined, "stage=REPLAYED"), combined);
        assertEquals(1, occurrences(combined, "complete=APPLIED"), combined);
        assertEquals(1, occurrences(combined, "complete=REPLAYED"), combined);
        assertSuccess(runProbe(root, "verify-ready"));
    }

    private static ProbeResult runProbe(Path root, String action) throws Exception {
        return finishProbe(startProbe(root, action, null), action);
    }

    private static Process startProbe(Path root, String action, Path extra) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new java.util.ArrayList<>(List.of(
                java, "-cp", classpath,
                PersistentFileSystemMockArtifactStoreProcessProbe.class.getName(),
                root.toString(), action));
        if (extra != null) {
            command.add(extra.toString());
        }
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static ProbeResult finishProbe(Process process, String action) throws Exception {
        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("probe timed out: " + action);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProbeResult(process.exitValue(), output);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void assertSuccess(ProbeResult result) {
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("PROBE_OK"), result.output());
        assertNotEquals(-1, result.output().indexOf("action="));
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
