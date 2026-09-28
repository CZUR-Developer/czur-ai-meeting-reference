package com.czur.reference.application.artifact.filesystem;

import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStore.CompleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.ContentClaim;
import com.czur.reference.application.port.artifact.ArtifactStore.DeleteStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareResult;
import com.czur.reference.application.port.artifact.ArtifactStore.PrepareStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StageStatus;
import com.czur.reference.application.port.artifact.ArtifactStore.StorageBinding;
import com.czur.reference.application.port.artifact.ArtifactStore.StoreState;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntent;
import com.czur.reference.application.port.artifact.ArtifactStore.WriteIntentSpec;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.czur.reference.application.port.artifact.ArtifactStoreException.Code;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件系统 Mock 的两段式发布、并发、删除和安全关闭门禁。 */
class PersistentFileSystemMockArtifactStoreTest {
    private static final byte[] CONTENT =
            "deterministic transcript content".getBytes(StandardCharsets.UTF_8);
    private static final long LIMIT = 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void intentStageCompleteAndReadRemainStableAcrossStoreInstances() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore first = store(root);
        WriteIntentSpec spec = spec("art_lifecycle", CONTENT);

        PrepareResult prepared = first.createWriteIntent(spec);
        assertEquals(PrepareStatus.APPLIED, prepared.status());
        assertEquals(StoreState.PENDING, first.stat(prepared.intent().binding()).state());

        var staged = first.writeContent(
                prepared.intent(), new ByteArrayInputStream(CONTENT));
        assertEquals(StageStatus.APPLIED, staged.status());
        assertEquals(StoreState.PENDING, first.stat(prepared.intent().binding()).state());

        var completed = first.completeWrite(prepared.intent(), claim(CONTENT));
        assertEquals(CompleteStatus.APPLIED, completed.status());
        assertEquals(StoreState.READY, first.stat(prepared.intent().binding()).state());

        PersistentFileSystemMockArtifactStore restarted = store(root);
        PrepareResult replay = restarted.createWriteIntent(spec);
        assertEquals(PrepareStatus.REPLAYED, replay.status());
        assertEquals(prepared.intent(), replay.intent());
        assertEquals(StageStatus.REPLAYED,
                restarted.writeContent(replay.intent(), new ByteArrayInputStream(CONTENT)).status());
        assertEquals(CompleteStatus.REPLAYED,
                restarted.completeWrite(replay.intent(), claim(CONTENT)).status());
        try (ArtifactStore.ReadHandle read = restarted.openRead(replay.intent().binding())) {
            assertArrayEquals(CONTENT, read.content().readAllBytes());
        }

        List<Path> readyBlobs;
        try (var files = Files.list(root.resolve("objects"))) {
            readyBlobs = files.filter(path -> path.getFileName().toString().endsWith(".blob"))
                    .toList();
        }
        assertEquals(1, readyBlobs.size());
    }

    @Test
    void fullSpecConflictKeepsFirstWinnerAndStableBinding() throws Exception {
        PersistentFileSystemMockArtifactStore store = store(temporaryDirectory.resolve("store"));
        WriteIntentSpec winner = spec("art_conflict", CONTENT);
        PrepareResult first = store.createWriteIntent(winner);

        List<WriteIntentSpec> conflicts = List.of(
                new WriteIntentSpec(
                        winner.artifactId(), "other-meeting", winner.artifactType(),
                        winner.mediaType(), winner.language(), winner.revision(),
                        winner.expectedSize(), winner.expectedChecksum()),
                new WriteIntentSpec(
                        winner.artifactId(), winner.meetingId(), "summary.structured",
                        winner.mediaType(), winner.language(), winner.revision(),
                        winner.expectedSize(), winner.expectedChecksum()),
                new WriteIntentSpec(
                        winner.artifactId(), winner.meetingId(), winner.artifactType(),
                        "text/plain", winner.language(), winner.revision(),
                        winner.expectedSize(), winner.expectedChecksum()),
                new WriteIntentSpec(
                        winner.artifactId(), winner.meetingId(), winner.artifactType(),
                        winner.mediaType(), "en-US", winner.revision(),
                        winner.expectedSize(), winner.expectedChecksum()),
                new WriteIntentSpec(
                        winner.artifactId(), winner.meetingId(), winner.artifactType(),
                        winner.mediaType(), winner.language(), 2,
                        winner.expectedSize(), winner.expectedChecksum()),
                spec("art_conflict", "different-content".getBytes(StandardCharsets.UTF_8)));

        for (WriteIntentSpec conflict : conflicts) {
            PrepareResult result = store.createWriteIntent(conflict);
            assertEquals(PrepareStatus.CONFLICT, result.status());
            assertEquals(first.intent(), result.intent());
        }
        assertEquals(first.intent().binding(), store.createWriteIntent(winner).intent().binding());
    }

    @Test
    void sizeChecksumAndInterruptedWritesNeverBecomeReadyAndCanRetry() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore store = store(root);

        WriteIntent sizeIntent = store.createWriteIntent(spec("art_size", CONTENT)).intent();
        ArtifactStoreException shortWrite = assertThrows(
                ArtifactStoreException.class,
                () -> store.writeContent(
                        sizeIntent,
                        new ByteArrayInputStream(Arrays.copyOf(CONTENT, CONTENT.length - 1))));
        assertEquals(Code.SIZE_MISMATCH, shortWrite.code());
        assertEquals(StoreState.PENDING, store.stat(sizeIntent.binding()).state());

        byte[] wrong = CONTENT.clone();
        wrong[0] ^= 1;
        ArtifactStoreException checksum = assertThrows(
                ArtifactStoreException.class,
                () -> store.writeContent(sizeIntent, new ByteArrayInputStream(wrong)));
        assertEquals(Code.CHECKSUM_MISMATCH, checksum.code());
        assertEquals(StoreState.PENDING, store.stat(sizeIntent.binding()).state());

        WriteIntent interrupted = store.createWriteIntent(spec("art_interrupted", CONTENT)).intent();
        ArtifactStoreException interruptedFailure = assertThrows(
                ArtifactStoreException.class,
                () -> store.writeContent(interrupted, new FailingInputStream(CONTENT, 7)));
        assertEquals(Code.STORAGE_UNAVAILABLE, interruptedFailure.code());
        assertNotEquals(StoreState.READY, store.stat(interrupted.binding()).state());

        PersistentFileSystemMockArtifactStore restarted = store(root);
        assertEquals(StageStatus.APPLIED,
                restarted.writeContent(interrupted, new ByteArrayInputStream(CONTENT)).status());
        assertEquals(CompleteStatus.APPLIED,
                restarted.completeWrite(interrupted, claim(CONTENT)).status());
        assertEquals(StoreState.READY, restarted.stat(interrupted.binding()).state());
    }

    @Test
    void completeRequiresExpectedActualAndClaimToMatchBeforePublishingReady()
            throws Exception {
        PersistentFileSystemMockArtifactStore store = store(temporaryDirectory.resolve("store"));
        WriteIntent intent = store.createWriteIntent(spec("art_three_way", CONTENT)).intent();
        ContentClaim actual = store.writeContent(
                intent, new ByteArrayInputStream(CONTENT)).content();

        ArtifactStoreException size = assertThrows(
                ArtifactStoreException.class,
                () -> store.completeWrite(
                        intent, new ContentClaim(actual.size() + 1, actual.checksum())));
        assertEquals(Code.SIZE_MISMATCH, size.code());
        assertEquals(StoreState.PENDING, store.stat(intent.binding()).state());

        ArtifactStoreException checksum = assertThrows(
                ArtifactStoreException.class,
                () -> store.completeWrite(
                        intent, new ContentClaim(actual.size(), "sha256:" + "00".repeat(32))));
        assertEquals(Code.CHECKSUM_MISMATCH, checksum.code());
        assertEquals(StoreState.PENDING, store.stat(intent.binding()).state());

        assertEquals(CompleteStatus.APPLIED, store.completeWrite(intent, actual).status());
        assertEquals(StoreState.READY, store.stat(intent.binding()).state());
    }

    @Test
    void objectBlobWithoutAtomicReadyMarkerRemainsPendingAndCompleteRecovers()
            throws Exception {
        Path root = temporaryDirectory.resolve("publish-crash");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntent intent = store.createWriteIntent(spec("art_publish_crash", CONTENT)).intent();
        ContentClaim claim = store.writeContent(
                intent, new ByteArrayInputStream(CONTENT)).content();
        String hash = hash(intent.binding());
        Files.move(
                root.resolve("staging").resolve(hash + ".blob"),
                root.resolve("objects").resolve(hash + ".blob"));

        assertEquals(StoreState.PENDING, store.stat(intent.binding()).state());
        ArtifactStoreException notReady = assertThrows(
                ArtifactStoreException.class, () -> store.openRead(intent.binding()));
        assertEquals(Code.NOT_READY, notReady.code());

        PersistentFileSystemMockArtifactStore restarted = store(root);
        assertEquals(CompleteStatus.APPLIED,
                restarted.completeWrite(intent, claim).status());
        assertEquals(StoreState.READY, restarted.stat(intent.binding()).state());
    }

    @Test
    void sameBindingHasOneWinnerAcrossStoreInstancesAndThreads() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore first = store(root);
        PersistentFileSystemMockArtifactStore second = store(root);
        WriteIntentSpec spec = spec("art_concurrent", CONTENT);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Future<PrepareResult>> futures = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                PersistentFileSystemMockArtifactStore candidate = index % 2 == 0 ? first : second;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return candidate.createWriteIntent(spec);
                }));
            }
            ready.await();
            start.countDown();
            int applied = 0;
            WriteIntent winner = null;
            for (Future<PrepareResult> future : futures) {
                PrepareResult result = future.get();
                if (result.status() == PrepareStatus.APPLIED) {
                    applied++;
                }
                if (winner == null) {
                    winner = result.intent();
                } else {
                    assertEquals(winner, result.intent());
                }
            }
            assertEquals(1, applied);
        } finally {
            executor.shutdownNow();
        }

        WriteIntent intent = first.createWriteIntent(spec).intent();
        assertEquals(StageStatus.APPLIED,
                first.writeContent(intent, new ByteArrayInputStream(CONTENT)).status());
        assertEquals(StageStatus.REPLAYED,
                second.writeContent(intent, new ByteArrayInputStream(CONTENT)).status());
        assertEquals(CompleteStatus.APPLIED,
                second.completeWrite(intent, claim(CONTENT)).status());
        assertEquals(CompleteStatus.REPLAYED,
                first.completeWrite(intent, claim(CONTENT)).status());
    }

    @Test
    void deletePersistsTombstonePreventsResurrectionAndIsRestartIdempotent() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntentSpec spec = spec("art_delete", CONTENT);
        WriteIntent intent = complete(store, spec);

        assertEquals(DeleteStatus.DELETED, store.delete(intent.binding()).status());
        assertEquals(StoreState.DELETED, store.stat(intent.binding()).state());
        ArtifactStoreException readDeleted = assertThrows(
                ArtifactStoreException.class, () -> store.openRead(intent.binding()));
        assertEquals(Code.DELETED, readDeleted.code());
        ArtifactStoreException recreate = assertThrows(
                ArtifactStoreException.class, () -> store.createWriteIntent(spec));
        assertEquals(Code.DELETED, recreate.code());

        PersistentFileSystemMockArtifactStore restarted = store(root);
        assertEquals(DeleteStatus.ALREADY_DELETED,
                restarted.delete(intent.binding()).status());
        assertEquals(StoreState.DELETED, restarted.stat(intent.binding()).state());
        try (var files = Files.list(root.resolve("objects"))) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    @Test
    void corruptionUnsafeBindingSymlinkAndSizeLimitFailClosed() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntent intent = complete(store, spec("art_corrupt", CONTENT));
        String hash = hash(intent.binding());
        Path blob = root.resolve("objects").resolve(hash + ".blob");
        Files.write(blob, new byte[]{1}, StandardOpenOption.TRUNCATE_EXISTING);
        ArtifactStoreException corrupt = assertThrows(
                ArtifactStoreException.class, () -> store.stat(intent.binding()));
        assertEquals(Code.STORAGE_CORRUPT, corrupt.code());

        ArtifactStoreException invalidBinding = assertThrows(
                ArtifactStoreException.class,
                () -> store.stat(new StorageBinding("../unsafe-binding")));
        assertEquals(Code.INVALID_INTENT, invalidBinding.code());

        PersistentFileSystemMockArtifactStore tiny =
                new PersistentFileSystemMockArtifactStore(
                        temporaryDirectory.resolve("tiny"), 2, () -> "diagnostic");
        ArtifactStoreException tooLarge = assertThrows(
                ArtifactStoreException.class,
                () -> tiny.createWriteIntent(spec("art_large", CONTENT)));
        assertEquals(Code.CONTENT_TOO_LARGE, tooLarge.code());

        Path real = temporaryDirectory.resolve("real-root");
        Files.createDirectories(real);
        Path link = temporaryDirectory.resolve("linked-root");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("symbolic links are unavailable");
        }
        ArtifactStoreException unsafe = assertThrows(
                ArtifactStoreException.class,
                () -> new PersistentFileSystemMockArtifactStore(
                        link, LIMIT, () -> "diagnostic"));
        assertEquals(Code.UNSAFE_FILESYSTEM, unsafe.code());
    }

    @Test
    void metadataVersionAndPerBindingSymlinkFailClosed() throws Exception {
        Path root = temporaryDirectory.resolve("store");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntent intent = store.createWriteIntent(spec("art_metadata", CONTENT)).intent();
        String hash = hash(intent.binding());
        Path intentFile = root.resolve("intents").resolve(hash + ".intent");
        byte[] unsupportedVersion = Files.readAllBytes(intentFile);
        // metadata layout begins with 4-byte magic followed by 4-byte version.
        unsupportedVersion[7] = 2;
        Files.write(intentFile, unsupportedVersion, StandardOpenOption.TRUNCATE_EXISTING);
        ArtifactStoreException malformed = assertThrows(
                ArtifactStoreException.class, () -> store.stat(intent.binding()));
        assertEquals(Code.STORAGE_CORRUPT, malformed.code());

        Path secondRoot = temporaryDirectory.resolve("symlink-store");
        PersistentFileSystemMockArtifactStore second = store(secondRoot);
        WriteIntent secondIntent =
                second.createWriteIntent(spec("art_symlink", CONTENT)).intent();
        String secondHash = hash(secondIntent.binding());
        Path target = temporaryDirectory.resolve("external-sensitive");
        Files.writeString(target, "external");
        Path part = secondRoot.resolve("staging").resolve(secondHash + ".blob.part");
        try {
            Files.createSymbolicLink(part, target);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("symbolic links are unavailable");
        }
        ArtifactStoreException symlink = assertThrows(
                ArtifactStoreException.class,
                () -> second.writeContent(secondIntent, new ByteArrayInputStream(CONTENT)));
        assertEquals(Code.UNSAFE_FILESYSTEM, symlink.code());
        assertEquals("external", Files.readString(target));
    }

    @Test
    void orphanContentWithoutPersistedIntentIsCorruptButIntentTemporaryIsPending()
            throws Exception {
        Path root = temporaryDirectory.resolve("orphan-store");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntent intent = store.createWriteIntent(spec("art_orphan", CONTENT)).intent();
        String hash = hash(intent.binding());
        Path persistedIntent = root.resolve("intents").resolve(hash + ".intent");
        Path intentTemporary = root.resolve("intents").resolve(hash + ".intent.tmp");
        Files.move(persistedIntent, intentTemporary);

        assertEquals(StoreState.PENDING, store.stat(intent.binding()).state());

        Path orphanBlob = root.resolve("staging").resolve(hash + ".blob.part");
        Files.write(orphanBlob, new byte[]{1, 2, 3});
        ArtifactStoreException corrupt = assertThrows(
                ArtifactStoreException.class, () -> store.stat(intent.binding()));
        assertEquals(Code.STORAGE_CORRUPT, corrupt.code());
        ArtifactStoreException createOverOrphan = assertThrows(
                ArtifactStoreException.class,
                () -> store.createWriteIntent(spec("art_orphan", CONTENT)));
        assertEquals(Code.STORAGE_CORRUPT, createOverOrphan.code());
    }

    @Test
    void replacingFixedSubdirectoryWithSymlinkIsRejectedBeforeFollowingParentPath()
            throws Exception {
        Path root = temporaryDirectory.resolve("parent-symlink-store");
        PersistentFileSystemMockArtifactStore store = store(root);
        WriteIntent intent = store.createWriteIntent(spec("art_parent_symlink", CONTENT)).intent();
        Path staging = root.resolve("staging");
        Path external = temporaryDirectory.resolve("external-staging");
        Files.createDirectories(external);
        Files.delete(staging);
        try {
            Files.createSymbolicLink(staging, external);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("symbolic links are unavailable");
        }

        ArtifactStoreException unsafe = assertThrows(
                ArtifactStoreException.class,
                () -> store.writeContent(intent, new ByteArrayInputStream(CONTENT)));
        assertEquals(Code.UNSAFE_FILESYSTEM, unsafe.code());
        try (var files = Files.list(external)) {
            assertTrue(files.findAny().isEmpty());
        }
    }

    @Test
    void parentSymlinkAtConstructionIsAcceptedButPinnedToCanonicalRoot()
            throws Exception {
        Path realParent = temporaryDirectory.resolve("real-parent");
        Files.createDirectories(realParent);
        Path aliasParent = temporaryDirectory.resolve("alias-parent");
        try {
            Files.createSymbolicLink(aliasParent, realParent);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("symbolic links are unavailable");
        }

        PersistentFileSystemMockArtifactStore store = store(aliasParent.resolve("store"));
        Path canonicalRoot = realParent.resolve("store").toRealPath();
        Path attackerParent = temporaryDirectory.resolve("attacker-parent");
        Path attackerRoot = attackerParent.resolve("store");
        createFixedDirectoryLayout(attackerRoot);
        Files.delete(aliasParent);
        Files.createSymbolicLink(aliasParent, attackerParent);

        WriteIntent intent = store.createWriteIntent(
                spec("art_canonical_parent", CONTENT)).intent();
        assertTrue(Files.exists(
                canonicalRoot.resolve("intents").resolve(hash(intent.binding()) + ".intent")));
        assertFixedDirectoriesEmpty(attackerRoot);
    }

    @Test
    void parentRenameAndSymlinkSwapIsRejectedBeforeWritingAttackerTree()
            throws Exception {
        Path originalParent = temporaryDirectory.resolve("original-parent");
        Path root = originalParent.resolve("root");
        PersistentFileSystemMockArtifactStore store = store(root);
        Path movedParent = temporaryDirectory.resolve("moved-parent");
        Files.move(originalParent, movedParent);

        Path attackerParent = temporaryDirectory.resolve("attacker-swap");
        Path attackerRoot = attackerParent.resolve("root");
        createFixedDirectoryLayout(attackerRoot);
        try {
            Files.createSymbolicLink(originalParent, attackerParent);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.abort("symbolic links are unavailable");
        }

        ArtifactStoreException unsafe = assertThrows(
                ArtifactStoreException.class,
                () -> store.createWriteIntent(spec("art_parent_swap", CONTENT)));
        assertEquals(Code.UNSAFE_FILESYSTEM, unsafe.code());
        assertFixedDirectoriesEmpty(attackerRoot);
    }

    @Test
    void deleteAndRecreateCanonicalRootAtSamePathIsRejectedByDirectoryIdentity()
            throws Exception {
        Path root = temporaryDirectory.resolve("replace-root");
        PersistentFileSystemMockArtifactStore store = store(root);
        Object originalFileKey = fileKey(root);
        deleteFixedDirectoryLayout(root);

        Object replacementFileKey = null;
        for (int attempt = 0; attempt < 32; attempt++) {
            Files.createDirectory(root);
            replacementFileKey = fileKey(root);
            if (!originalFileKey.equals(replacementFileKey)) {
                break;
            }
            Files.delete(root);
            Files.createDirectory(temporaryDirectory.resolve("identity-reservation-" + attempt));
        }
        assertNotEquals(originalFileKey, replacementFileKey);
        createFixedDirectoryLayout(root);

        ArtifactStoreException unsafe = assertThrows(
                ArtifactStoreException.class,
                () -> store.createWriteIntent(spec("art_recreated_root", CONTENT)));
        assertEquals(Code.UNSAFE_FILESYSTEM, unsafe.code());
        assertFixedDirectoriesEmpty(root);
    }

    @Test
    void pathsLogsAndDiagnosticsDoNotExposeArtifactFacts() throws Exception {
        Path root = temporaryDirectory.resolve("root-sensitive");
        Logger logger = Logger.getLogger(PersistentFileSystemMockArtifactStore.class.getName());
        StringBuilder output = new StringBuilder();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                output.append(record.getMessage())
                        .append(Arrays.toString(record.getParameters()));
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            PersistentFileSystemMockArtifactStore store =
                    new PersistentFileSystemMockArtifactStore(
                            root, LIMIT, () -> "safe-diagnostic-id");
            WriteIntentSpec spec = spec("art_secret_identifier", CONTENT);
            WriteIntent intent = complete(store, spec);
            store.delete(intent.binding());
            String diagnostics = output + " " + spec + " " + intent + " "
                    + claim(CONTENT) + " " + store.stat(intent.binding());
            for (String secret : new String[]{
                    "art_secret_identifier", "meeting-sensitive", "transcript.final",
                    "application/json", "zh-CN", checksum(CONTENT),
                    root.toString(), intent.binding().value()}) {
                assertFalse(diagnostics.contains(secret));
            }

            try (var paths = Files.walk(root)) {
                List<String> names = paths.map(path -> path.getFileName().toString()).toList();
                assertTrue(names.stream().noneMatch(name -> name.contains("art_secret_identifier")));
                assertTrue(names.stream().noneMatch(name -> name.contains("meeting-sensitive")));
            }
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }

    private PersistentFileSystemMockArtifactStore store(Path root)
            throws ArtifactStoreException {
        return new PersistentFileSystemMockArtifactStore(root, LIMIT, () -> "diagnostic-id");
    }

    private static void createFixedDirectoryLayout(Path root) throws IOException {
        Files.createDirectories(root);
        for (String name : List.of("intents", "staging", "objects", "tombstones", "locks")) {
            Files.createDirectory(root.resolve(name));
        }
    }

    private static void deleteFixedDirectoryLayout(Path root) throws IOException {
        for (String name : List.of("intents", "staging", "objects", "tombstones", "locks")) {
            Files.delete(root.resolve(name));
        }
        Files.delete(root);
    }

    private static void assertFixedDirectoriesEmpty(Path root) throws IOException {
        for (String name : List.of("intents", "staging", "objects", "tombstones", "locks")) {
            try (var files = Files.list(root.resolve(name))) {
                assertTrue(files.findAny().isEmpty(), name + " must remain empty");
            }
        }
    }

    private static Object fileKey(Path directory) throws IOException {
        return Files.readAttributes(
                directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static WriteIntent complete(
            PersistentFileSystemMockArtifactStore store,
            WriteIntentSpec spec) throws Exception {
        WriteIntent intent = store.createWriteIntent(spec).intent();
        store.writeContent(intent, new ByteArrayInputStream(CONTENT));
        store.completeWrite(intent, claim(CONTENT));
        return intent;
    }

    private static WriteIntentSpec spec(String artifactId, byte[] content) {
        return new WriteIntentSpec(
                artifactId,
                "meeting-sensitive",
                "transcript.final",
                "application/json",
                "zh-CN",
                1,
                content.length,
                checksum(content));
    }

    private static ContentClaim claim(byte[] content) {
        return new ContentClaim(content.length, checksum(content));
    }

    private static String checksum(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String hash(StorageBinding binding) {
        return binding.value().substring("fsmock_v1_".length());
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] content;
        private final int failAfter;
        private int position;

        private FailingInputStream(byte[] content, int failAfter) {
            this.content = content.clone();
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (position >= failAfter) {
                throw new IOException("sensitive injected failure");
            }
            return position >= content.length ? -1 : content[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= failAfter) {
                throw new IOException("sensitive injected failure");
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, failAfter - position), content.length - position);
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
