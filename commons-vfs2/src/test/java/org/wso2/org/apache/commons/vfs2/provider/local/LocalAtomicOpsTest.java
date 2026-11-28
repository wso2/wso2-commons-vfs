/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;

/**
 * The calls of AtomicOps on a local path, on real files. A folder pair across two file systems
 * gives the plain FileSystemException of a link that can not be made, with a working attribute read.
 */
public class LocalAtomicOpsTest {

    private static final byte[] VALUE = "value of the winner".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private Path shm;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        manager = new StandardFileSystemManager();
        manager.init();
        final Path devShm = Paths.get("/dev/shm");
        shm = Files.isDirectory(devShm) && !Files.getFileStore(devShm).equals(Files.getFileStore(dir))
                ? Files.createTempDirectory(devShm, "linktest") : null;
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        if (shm != null) {
            try (java.util.stream.Stream<Path> files = Files.walk(shm)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private AtomicOps ops(final Path path) throws FileSystemException {
        return AtomicOps.of(manager.resolveFile(path.toUri()));
    }

    private FileObject object(final Path path) throws FileSystemException {
        return manager.resolveFile(path.toUri());
    }

    private Path folder(final String name) throws Exception {
        return Files.createDirectories(dir.resolve(name));
    }

    @Test
    public void localFileHasTheCalls() throws Exception {
        assertTrue(ops(dir.resolve("a")) instanceof LocalFile);
    }

    @Test
    public void capabilitiesBeforeAnyCall() throws Exception {
        final AtomicOps lock = ops(folder("in").resolve("a.lock"));
        assertEquals(Answer.YES, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.YES, lock.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void exclusiveCreateWritesTheContentAndASecondCreateIsExists() throws Exception {
        final Path lock = folder("in").resolve("a.lock");
        assertEquals(AtomicOutcome.DONE, ops(lock).createExclusive(VALUE, "s.lock", "p.lock").getOutcome());
        assertArrayEquals(VALUE, Files.readAllBytes(lock));
        assertEquals(AtomicOutcome.EXISTS, ops(lock).createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        assertArrayEquals(VALUE, Files.readAllBytes(lock));
    }

    @Test
    public void exclusiveCreateWithNoFolderEndsWithItsFaultAndNoUnclearException() throws Exception {
        final Path lock = dir.resolve("none/a.lock");
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> ops(lock).createExclusive(VALUE, "s.lock", "p.lock"));
        // The kernel answers that the folder is not there, so nothing was made. The error is not unclear. It keeps
        // the fault MISSING and its cause.
        assertFalse(error instanceof UnclearResultException);
        assertEquals(Fault.MISSING, error.getFault());
        assertTrue(error.getCause() instanceof NoSuchFileException);
    }

    // The plain JDK error decides nothing, so an exclusive create, a replacing rename or a take away that ends with it
    // throws UnclearResultException with the phase and the temp path of the call and keeps the cause.

    private static UnclearResultException unclear(final MovePhase phase, final String tempPath,
            final Executable call) {
        final UnclearResultException error = assertThrows(UnclearResultException.class, call);
        assertEquals(phase, error.getPhase());
        assertEquals(tempPath, error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(java.nio.file.FileSystemException.class, error.getCause().getClass());
        return error;
    }

    @Test
    public void exclusiveCreateBelowAFileIsUnclearWithNoTempPath() throws Exception {
        final Path file = folder("in").resolve("f");
        Files.write(file, VALUE);
        unclear(MovePhase.COMMIT, null, () -> ops(file.resolve("a.lock")).createExclusive(VALUE, "s.lock", "p.lock"));
    }

    @Test
    public void replacingRenameOntoAFolderIsUnclearWithTheReceiverPath() throws Exception {
        final Path temp = folder("out/.part").resolve("t.tmp");
        Files.write(temp, VALUE);
        final Path target = folder("out/t.csv");
        Files.write(target.resolve("x"), VALUE);
        unclear(MovePhase.COMMIT, object(temp).getName().getPath(),
                () -> ops(temp).renameReplacing(object(target), null, null));
        assertArrayEquals(VALUE, Files.readAllBytes(temp));
    }

    @Test
    public void takeAwayOntoAFolderIsUnclearWithNoTempPath() throws Exception {
        final Path lock = folder("in").resolve("a.lock");
        Files.write(lock, VALUE);
        final Path away = folder("in/a.x1.lock");
        Files.write(away.resolve("x"), VALUE);
        unclear(MovePhase.RENAME, null, () -> ops(lock).takeAway(object(away)));
    }

    @Test
    public void rootIsItsOwnFolderInBothQueryForms() throws Exception {
        final FileObject root = manager.resolveFile("file:///");
        final Path file = folder("in").resolve("a.csv");
        Files.write(file, VALUE);
        assertEquals(Answer.YES, AtomicOps.of(root).atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(root).atomicCapabilities().getExclusivePublish());
        // A folder can not be hard linked.
        assertEquals(Answer.NO, AtomicOps.of(root).atomicCapabilities(object(file)).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, ops(file).atomicCapabilities(root).getExclusivePublish());
        assertEquals(Answer.YES, ops(file).atomicCapabilities(root).getReplacingRename());
        assertTrue(linkMemory(root).isEmpty());
    }

    // The link memory of the file system of a file.
    private static Map<?, ?> linkMemory(final FileObject file) throws Exception {
        final Field field = LocalFileSystem.class.getDeclaredField("linkMemory");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(file.getFileSystem());
    }

    @Test
    public void queriesOnUnseenPairsKeepNoMemoryAndALinkKeepsOnlyItsPair() throws Exception {
        final Path out = folder("out");
        final List<FileObject> files = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final Path file = folder("d" + i).resolve("a");
            Files.write(file, VALUE);
            files.add(object(file));
        }
        final Map<?, ?> memory = linkMemory(files.get(0));
        for (final FileObject file : files) {
            assertEquals(Answer.NOT_KNOWN, AtomicOps.of(file).atomicCapabilities().getExclusivePublish());
            assertEquals(Answer.NOT_KNOWN, AtomicOps.of(file).atomicCapabilities(object(out.resolve("a")))
                    .getExclusivePublish());
        }
        assertTrue(memory.isEmpty());
        assertEquals(AtomicOutcome.DONE, AtomicOps.of(files.get(7)).linkExclusive(object(out.resolve("a")), "p.part")
                .getOutcome());
        assertEquals(1, memory.size());
        assertEquals(Answer.YES, AtomicOps.of(files.get(7)).atomicCapabilities(object(out.resolve("b")))
                .getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(files.get(8)).atomicCapabilities(object(out.resolve("b")))
                .getExclusivePublish());
        assertEquals(1, memory.size());
    }

    @Test
    public void twoPartiesAtABarrierGiveOneWinnerInEveryRound() throws Exception {
        final Path in = folder("race");
        final StandardFileSystemManager other = new StandardFileSystemManager();
        other.init();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                final Path lock = in.resolve("r" + round + ".lock");
                final AtomicOps first = ops(lock);
                final AtomicOps second = AtomicOps.of(other.resolveFile(lock.toUri()));
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final Future<AtomicResult> a = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return first.createExclusive("A".getBytes(StandardCharsets.UTF_8), "s.lock", "p.lock");
                });
                final Future<AtomicResult> b = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return second.createExclusive("B".getBytes(StandardCharsets.UTF_8), "s.lock", "p.lock");
                });
                final AtomicOutcome outcomeA = a.get(10, TimeUnit.SECONDS).getOutcome();
                final AtomicOutcome outcomeB = b.get(10, TimeUnit.SECONDS).getOutcome();
                assertTrue(outcomeA == AtomicOutcome.DONE ^ outcomeB == AtomicOutcome.DONE,
                        "round " + round + ": " + outcomeA + " " + outcomeB);
                final String winner = outcomeA == AtomicOutcome.DONE ? "A" : "B";
                assertEquals(winner, new String(Files.readAllBytes(lock), StandardCharsets.UTF_8));
            }
        } finally {
            pool.shutdownNow();
            other.close();
        }
    }

    @Test
    public void linkLeavesTheSourceAndRefusesAnExistingTarget() throws Exception {
        final Path source = folder("in").resolve("a.csv");
        Files.write(source, VALUE);
        final Path target = folder("out").resolve("a.csv");
        final AtomicOps ops = ops(source);
        final AtomicResult done = ops.linkExclusive(object(target), "p.part");
        assertEquals(AtomicOutcome.DONE, done.getOutcome());
        assertTrue(Files.exists(source));
        assertTrue(Files.isSameFile(source, target));
        assertEquals(Answer.YES, ops.atomicCapabilities(object(target)).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, ops(target).atomicCapabilities(object(source)).getExclusivePublish());
        final Path other = folder("in").resolve("b.csv");
        Files.write(other, new byte[] {9});
        assertEquals(AtomicOutcome.EXISTS, ops(other).linkExclusive(object(target), "p.part").getOutcome());
        assertArrayEquals(VALUE, Files.readAllBytes(target));
    }

    @Test
    public void linkOfAMissingSourceIsGone() throws Exception {
        final Path target = folder("out").resolve("a.csv");
        assertEquals(AtomicOutcome.GONE,
                ops(folder("in").resolve("none.csv")).linkExclusive(object(target), "p.part").getOutcome());
        assertFalse(Files.exists(target));
    }

    @Test
    public void linkIntoAFolderWithNoWriteAccessIsRefused() throws Exception {
        final Path source = folder("in").resolve("a.csv");
        Files.write(source, VALUE);
        final Path closed = folder("closed");
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(closed), "the test runs as a user that can not write the folder");
            final AtomicResult result = ops(source).linkExclusive(object(closed.resolve("a.csv")), "p.part");
            assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
            assertTrue(result.getDetail().startsWith("java.nio.file.AccessDeniedException"), result.getDetail());
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    public void folderPairThatCanNotLinkGivesUnsupportedForTheCallAndNoMark() throws Exception {
        assumeTrue(shm != null, "the test needs a second file system");
        final Path source = shm.resolve("a.tmp");
        Files.write(source, VALUE);
        final Path out = folder("out");
        final AtomicOps ops = ops(source);
        final AtomicResult result = ops.linkExclusive(object(out.resolve("a.csv")), "p.part");
        assertEquals(AtomicOutcome.UNSUPPORTED, result.getOutcome());
        assertTrue(result.getDetail().startsWith("java.nio.file.FileSystemException"), result.getDetail());
        assertFalse(Files.exists(out.resolve("a.csv")));
        assertFalse(Files.exists(out.resolve("p.part")));
        assertTrue(Files.exists(source));
        // One result marks nothing.
        assertEquals(Answer.NOT_KNOWN, ops.atomicCapabilities(object(out.resolve("a.csv"))).getExclusivePublish());
    }

    @Test
    public void linkOntoAnExistingNameOnAPairThatCanNotLinkIsExists() throws Exception {
        assumeTrue(shm != null, "the test needs a second file system");
        final Path source = shm.resolve("a.tmp");
        Files.write(source, VALUE);
        final Path target = folder("out").resolve("a.csv");
        Files.write(target, new byte[] {7});
        assertEquals(AtomicOutcome.EXISTS, ops(source).linkExclusive(object(target), "p.part").getOutcome());
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
    }

    @Test
    public void publishRemovesTheTempNameOnlyAfterDone() throws Exception {
        final Path part = folder("out/.part");
        final Path temp = part.resolve("a.csv.x.part");
        Files.write(temp, VALUE);
        final Path target = dir.resolve("out/a.csv");
        assertEquals(AtomicOutcome.DONE, ops(temp).publishExclusive(object(target), "p.part").getOutcome());
        assertFalse(Files.exists(temp));
        assertArrayEquals(VALUE, Files.readAllBytes(target));
        final Path second = part.resolve("a.csv.y.part");
        Files.write(second, new byte[] {2});
        assertEquals(AtomicOutcome.EXISTS, ops(second).publishExclusive(object(target), "p.part").getOutcome());
        assertTrue(Files.exists(second));
        assertArrayEquals(VALUE, Files.readAllBytes(target));
    }

    @Test
    public void twoPublishesOntoOneAbsentNameGiveOneWinnerInEveryRound() throws Exception {
        final Path part = folder("out/.part");
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                final Path a = part.resolve("t" + round + ".a.part");
                final Path b = part.resolve("t" + round + ".b.part");
                Files.write(a, "A".getBytes(StandardCharsets.UTF_8));
                Files.write(b, "B".getBytes(StandardCharsets.UTF_8));
                final Path target = dir.resolve("out/t" + round + ".bin");
                final AtomicOps first = ops(a);
                final AtomicOps second = ops(b);
                final FileObject targetObject = object(target);
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final Future<AtomicResult> ra = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return first.publishExclusive(targetObject, "p.part");
                });
                final Future<AtomicResult> rb = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return second.publishExclusive(targetObject, "p.part");
                });
                final AtomicOutcome oa = ra.get(10, TimeUnit.SECONDS).getOutcome();
                final AtomicOutcome ob = rb.get(10, TimeUnit.SECONDS).getOutcome();
                assertTrue(oa == AtomicOutcome.DONE ^ ob == AtomicOutcome.DONE,
                        "round " + round + ": " + oa + " " + ob);
                assertEquals(AtomicOutcome.EXISTS, oa == AtomicOutcome.DONE ? ob : oa);
                final String winner = oa == AtomicOutcome.DONE ? "A" : "B";
                assertEquals(winner, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
                // The temp file of the loser stays for its caller.
                assertTrue(Files.exists(oa == AtomicOutcome.DONE ? b : a));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void replacingRenameReplacesTheTargetAndAMissingSourceIsGone() throws Exception {
        final Path temp = folder("out/.part").resolve("t.tmp");
        Files.write(temp, VALUE);
        final Path target = dir.resolve("out/t.csv");
        Files.write(target, new byte[] {1});
        assertEquals(AtomicOutcome.DONE, ops(temp).renameReplacing(object(target), null, null).getOutcome());
        assertArrayEquals(VALUE, Files.readAllBytes(target));
        assertFalse(Files.exists(temp));
        assertEquals(AtomicOutcome.GONE, ops(temp).renameReplacing(object(target), null, null).getOutcome());
    }

    @Test
    public void replacingRenameThatTheFolderRefusesIsRefusedAndKeepsTheTarget() throws Exception {
        final Path temp = folder("ro").resolve("t.tmp");
        Files.write(temp, VALUE);
        final Path target = dir.resolve("ro/t.csv");
        Files.write(target, new byte[] {1});
        Files.setPosixFilePermissions(dir.resolve("ro"), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeTrue(!Files.isWritable(dir.resolve("ro")), "the test runs as a user that can not write the folder");
            final AtomicResult result = ops(temp).renameReplacing(object(target), null, null);
            assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(target));
        } finally {
            Files.setPosixFilePermissions(dir.resolve("ro"), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    public void twoPartiesThatTakeOneLockAwayGetOneDoneAndOneGone() throws Exception {
        final Path in = folder("take");
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                final Path lock = in.resolve("r" + round + ".lock");
                Files.write(lock, VALUE);
                final AtomicOps first = ops(lock);
                final AtomicOps second = AtomicOps.of(object(lock));
                final FileObject awayA = object(in.resolve("r" + round + ".a.lock"));
                final FileObject awayB = object(in.resolve("r" + round + ".b.lock"));
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final Future<AtomicResult> ra = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return first.takeAway(awayA);
                });
                final Future<AtomicResult> rb = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return second.takeAway(awayB);
                });
                final List<AtomicOutcome> outcomes = new ArrayList<>();
                outcomes.add(ra.get(10, TimeUnit.SECONDS).getOutcome());
                outcomes.add(rb.get(10, TimeUnit.SECONDS).getOutcome());
                assertTrue(outcomes.contains(AtomicOutcome.DONE) && outcomes.contains(AtomicOutcome.GONE),
                        "round " + round + ": " + outcomes);
                assertFalse(Files.exists(lock));
                // No file is under the away name of the loser.
                assertTrue(Files.exists(in.resolve("r" + round + ".a.lock")) ^ Files.exists(in.resolve("r" + round
                        + ".b.lock")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void lookIsFalseOnlyForANameThatIsNotThere() throws Exception {
        final Path file = folder("in").resolve("a.csv");
        Files.write(file, VALUE);
        assertTrue(ops(file).existsNow());
        assertFalse(ops(dir.resolve("in/none.csv")).existsNow());
        assertFalse(ops(dir.resolve("none/x.csv")).existsNow());
        // A path below a file is not a "not there".
        assertThrows(FileSystemException.class, () -> ops(file.resolve("x")).existsNow());
    }

    @Test
    public void lookBelowAFolderWithNoSearchPermissionThrowsAndThenAnswersTrue() throws Exception {
        final Path closed = folder("noaccess");
        final Path file = closed.resolve("a.csv");
        Files.write(file, VALUE);
        // The object is resolved first. A resolve below a folder with no access fails in its own probe.
        final AtomicOps ops = ops(file);
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isExecutable(closed), "the test runs as a user that can not search the folder");
            final FileSystemException error = assertThrows(FileSystemException.class, ops::existsNow);
            assertEquals(Fault.APPLICATION, error.getFault());
            assertEquals("java.nio.file.AccessDeniedException", error.getCause().getClass().getName());
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertTrue(ops.existsNow());
    }

    @Test
    public void lookDoesNotFollowALink() throws Exception {
        final Path link = folder("in").resolve("dangling.lock");
        Files.createSymbolicLink(link, dir.resolve("in/none"));
        assertFalse(Files.exists(link));
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertTrue(ops(link).existsNow());
    }

    @Test
    public void folderLookAndStat() throws Exception {
        final Path in = folder("in");
        final Path file = in.resolve("a.csv");
        Files.write(file, VALUE);
        assertTrue(ops(in).folderExistsNow());
        assertFalse(ops(dir.resolve("none")).folderExistsNow());
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, () -> ops(file).folderExistsNow())
                .getFault());
        final StatNow found = ops(file).statNow();
        assertTrue(found.isThere());
        assertEquals(VALUE.length, found.getSize());
        assertEquals(Files.getLastModifiedTime(file).toMillis(), found.getLastModifiedMillis());
        assertFalse(found.isFromListing());
        assertFalse(ops(dir.resolve("in/none")).statNow().isThere());
    }

    @Test
    public void folderAsTheThingToMoveNeverLinks() throws Exception {
        final Path in = folder("in");
        final FileObject folderObject = object(in);
        assertTrue(folderObject.isFolder());
        assertEquals(Answer.NO, AtomicOps.of(folderObject).atomicCapabilities(object(dir.resolve("out/x")))
                .getExclusivePublish());
        assertNotNull(AtomicOps.of(folderObject));
        assertNull(AtomicOps.of(manager.resolveFile("ram:///x")));
    }
}
