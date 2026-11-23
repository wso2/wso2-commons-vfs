/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.*;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

public class MoveRuleTest {
    @TempDir Path dir;
    private final byte[] next = {8, 6, 7};
    private final byte[] old = {5, 3};
    private StandardFileSystemManager manager;
    private LocalFile source;
    private LocalFile target;
    private LocalFile temp;
    private Path src;
    private Path dst;
    private Path staging;

    private LocalFile file(Path name) throws Exception {
        return spy((LocalFile) FileObjectUtils.getAbstractFileObject(manager.resolveFile(name.toUri())));
    }

    @BeforeEach
    void start() throws Exception {
        manager = new StandardFileSystemManager();
        manager.init();
        src = Files.write(dir.resolve("source"), next);
        dst = Files.write(dir.resolve("target"), old);
        staging = dir.resolve("staging");
        source = file(src);
        target = file(dst);
        temp = file(staging);
    }

    @AfterEach
    void stop() {
        manager.close();
    }

    private MoveOptions options(boolean replace) {
        return new MoveOptions(replace, temp, "testprobe", null, null);
    }

    private void rename(AtomicOutcome outcome) throws Exception {
        doReturn(new AtomicResult(outcome, null)).when(source).renameReplacing(eq(target), any(), any());
    }

    private void unchanged() throws Exception {
        assertArrayEquals(old, Files.readAllBytes(dst));
        assertArrayEquals(next, Files.readAllBytes(src));
    }

    private void moved() throws Exception {
        assertArrayEquals(next, Files.readAllBytes(dst));
        assertFalse(Files.exists(src));
    }

    @Test
    void doneIsOneRenameWithNoCopy() throws Exception {
        source.moveTo(target, options(true));
        moved();
        verify(source).renameReplacing(eq(target), any(), any());
        verify(source, never()).getContent();
        verify(target, never()).delete();
    }

    @Test
    void definiteRefusalUsesStagingAndKeepsOldTargetUntilCommit() throws Exception {
        rename(AtomicOutcome.REFUSED);
        doAnswer(call -> {
            unchanged();
            assertArrayEquals(next, Files.readAllBytes(staging));
            return call.callRealMethod();
        }).when(temp).renameReplacing(eq(target), any(), any());
        source.moveTo(target, options(true));
        moved();
        verify(target, never()).delete();
    }

    @Test
    void unsupportedThenRefusedFallsBackToCopy() throws Exception {
        rename(AtomicOutcome.UNSUPPORTED);
        doAnswer(call -> {
            assertFalse(Files.exists(dst));
            return new AtomicResult(AtomicOutcome.REFUSED, "refused");
        }).when(source).takeAway(target);
        source.moveTo(target, options(true));
        moved();
        verify(target).delete();
    }

    @Test
    void unsupportedThenCutReportsDeletedTarget() throws Exception {
        rename(AtomicOutcome.UNSUPPORTED);
        doThrow(new FileSystemException(new SocketException())).when(source).takeAway(target);
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.RENAME, error.getPhase());
        assertTrue(error.isOldTargetDeleted());
        assertNull(error.getTempPath());
        assertFalse(Files.exists(dst));
        assertArrayEquals(next, Files.readAllBytes(src));
        verify(source, never()).getContent();
    }

    @Test
    void lostRenameReplyPreservesNewFileAndStops() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new FileSystemException(new SocketException());
        }).when(source).renameReplacing(eq(target), any(), any());
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.RENAME, error.getPhase());
        assertNull(error.getTempPath());
        assertFalse(error.isCommitConfirmed());
        moved();
        verify(source, never()).getContent();
        verify(source, never()).takeAway(any());
        verify(target, never()).existsNow();
        verify(target, never()).delete();
    }

    @Test
    void confirmedRenameWithLostConnectionMovesTheBytesOnce() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            return new AtomicResult(AtomicOutcome.DONE, "close", Collections.emptyList(), true);
        }).when(source).renameReplacing(eq(target), any(), any());
        source.moveTo(target, options(true));
        moved();
        verify(source).renameReplacing(eq(target), any(), any());
        verify(source, never()).getContent();
        verify(target, never()).delete();
        assertFalse(Files.exists(staging));
    }

    @Test
    void noncompletionWithLostConnectionNeverFallsBack() throws Exception {
        for (AtomicOutcome outcome : EnumSet.complementOf(EnumSet.of(AtomicOutcome.DONE))) {
            doReturn(new AtomicResult(outcome, "cleanup", Collections.emptyList(), true)).when(source)
                    .renameReplacing(eq(target), any(), any());
            MoveFailedException error = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(true)));
            assertEquals(MovePhase.RENAME, error.getPhase());
            assertTrue(error.isConnectionLost());
            verify(target, never()).delete();
            verify(source, never()).getContent();
        }
        unchanged();
    }

    @Test
    void missingAndBusyStayInRename() throws Exception {
        for (AtomicOutcome outcome : new AtomicOutcome[] { AtomicOutcome.GONE, AtomicOutcome.BUSY }) {
            rename(outcome);
            MoveFailedException error = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(true)));
            assertEquals(MovePhase.RENAME, error.getPhase());
            assertEquals(outcome == AtomicOutcome.GONE ? Fault.MISSING : Fault.BUSY, error.getFault());
            assertNull(error.getTempPath());
            assertFalse(error.isCommitConfirmed());
        }
        unchanged();
        verify(source, never()).getContent();
    }

    @Test
    void mountedTargetSkipsRenameAndCommitsCopy() throws Exception {
        target.setIsMounted(true);
        source.moveTo(new DecoratedFileObject(target), options(true));
        moved();
        verify(source, never()).renameReplacing(any(), any(), any());
        verify(temp).renameReplacing(any(), any(), any());
        verify(target, never()).getContent();
    }

    @Test
    void failedSourceOpenLeavesTargetWholeAndNoStaging() throws Exception {
        source.setIsMounted(true);
        doThrow(new FileSystemException("refused")).when(source).getContent();
        MoveFailedException error = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.COPY, error.getPhase());
        assertEquals(temp.getName().getPath(), error.getTempPath());
        assertFalse(error.isCommitConfirmed());
        unchanged();
        assertFalse(Files.exists(staging));
    }

    @Test
    void refusedCommitCleansStagingAndPreservesSource() throws Exception {
        source.setIsMounted(true);
        doReturn(new AtomicResult(AtomicOutcome.REFUSED, null)).when(temp).renameReplacing(eq(target), any(), any());
        MoveFailedException error = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.COMMIT, error.getPhase());
        assertEquals(temp.getName().getPath(), error.getTempPath());
        assertFalse(error.isCommitConfirmed());
        unchanged();
        assertFalse(Files.exists(staging));
    }

    @Test
    void lostCommitAfterOldTargetDeleteMakesNoFurtherCall() throws Exception {
        source.setIsMounted(true);
        doReturn(new AtomicResult(AtomicOutcome.UNSUPPORTED, null)).when(temp).renameReplacing(eq(target), any(), any());
        doThrow(new FileSystemException(new SocketException())).when(temp).takeAway(target);
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.AFTER_DELETE, error.getPhase());
        assertTrue(error.isOldTargetDeleted());
        assertFalse(error.isCommitConfirmed());
        assertEquals(temp.getName().getPath(), error.getTempPath());
        assertArrayEquals(next, Files.readAllBytes(src));
        assertArrayEquals(next, Files.readAllBytes(staging));
        assertFalse(Files.exists(dst));
        verify(temp, never()).delete();
        verify(source, never()).doDelete();
    }

    @Test
    void confirmedCopyThenSourceDeleteRefusedKeepsBoth() throws Exception {
        source.setIsMounted(true);
        doThrow(new FileSystemException("refused")).when(source).doDelete();
        MoveFailedException error = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.DELETE_SOURCE, error.getPhase());
        assertTrue(error.isCommitConfirmed());
        assertEquals(temp.getName().getPath(), error.getTempPath());
        assertArrayEquals(next, Files.readAllBytes(src));
        assertArrayEquals(next, Files.readAllBytes(dst));
    }

    @Test
    void confirmedLinkThenDeleteDeniedOrUnclearKeepsCommitFlag() throws Exception {
        Files.delete(dst);
        doThrow(new FileSystemException("denied")).when(source).doDelete();
        MoveFailedException refusal = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.DELETE_SOURCE, refusal.getPhase());
        assertTrue(refusal.isCommitConfirmed());
        assertTrue(Files.isSameFile(src, dst));
        Files.delete(dst);
        doThrow(new SocketException()).when(source).doDelete();
        UnclearResultException lost = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.DELETE_SOURCE, lost.getPhase());
        assertTrue(lost.isCommitConfirmed());
        assertNull(lost.getTempPath());
        assertArrayEquals(next, Files.readAllBytes(dst));
    }

    @Test
    void exclusiveMoveAndExclusiveCopyKeepExistingTarget() throws Exception {
        MoveFailedException rename = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.RENAME, rename.getPhase());
        assertTrue(rename.isTargetExists());
        source.setIsMounted(true);
        MoveFailedException copy = assertThrows(MoveFailedException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.COMMIT, copy.getPhase());
        assertTrue(copy.isTargetExists());
        unchanged();
        assertFalse(Files.exists(staging));
    }

    @Test
    void mountedSelfMoveKeepsExistingNameAndMissingSelfFails() throws Exception {
        source.setIsMounted(true);
        source.moveTo(source);
        assertArrayEquals(next, Files.readAllBytes(src));
        assertTrue(assertThrows(MoveFailedException.class, () -> source.moveTo(source, options(false))).isTargetExists());
        Files.delete(src);
        assertThrows(MoveFailedException.class, () -> source.moveTo(source));
        assertFalse(Files.exists(staging));
    }

    @Test
    void explicitMoveRejectsRamBeforeMutationAndLegacyMoveStillWorks() throws Exception {
        final FileObject ram = manager.resolveFile("ram:///target");
        try (java.io.OutputStream out = ram.getContent().getOutputStream()) {
            out.write(old);
        }
        assertThrows(MoveFailedException.class, () -> source.moveTo(ram, options(true)));
        assertArrayEquals(old, ram.getContent().getByteArray());
        assertArrayEquals(next, Files.readAllBytes(src));
        assertFalse(Files.exists(staging));
        source.moveTo(ram);
        assertArrayEquals(next, ram.getContent().getByteArray());
        assertFalse(Files.exists(src));
    }

    @Test
    void confirmedLinkWithCleanupLossDoesNotDeleteSource() throws Exception {
        Files.delete(dst);
        doAnswer(call -> {
            Files.createLink(dst, src);
            return new AtomicResult(AtomicOutcome.DONE, "connection lost", Collections.emptyList(), true);
        }).when(source).linkExclusive(target, "testprobe");
        UnclearResultException e = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.DELETE_SOURCE, e.getPhase());
        assertTrue(e.isCommitConfirmed());
        assertEquals(Fault.TRANSPORT, e.getFault());
        assertNull(e.getTempPath());
        assertTrue(Files.isSameFile(src, dst));
        verify(source, never()).doDelete();
        verify(target, never()).existsNow();
    }

    @Test
    void confirmedCopyWithCleanupLossStopsBeforeDelete() throws Exception {
        source.setIsMounted(true);
        Files.delete(dst);
        doAnswer(call -> {
            Files.createLink(dst, staging);
            return new AtomicResult(AtomicOutcome.DONE, "cleanup", Collections.singletonList(staging.toString()), true);
        }).when(temp).publishExclusive(target, "testprobe");
        UnclearResultException e = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(false)));
        assertEquals(MovePhase.DELETE_SOURCE, e.getPhase());
        assertTrue(e.isCommitConfirmed());
        assertEquals(temp.getName().getPath(), e.getTempPath());
        assertArrayEquals(next, Files.readAllBytes(src));
        assertTrue(Files.isSameFile(staging, dst));
        verify(source, never()).doDelete();
        verify(temp, never()).delete();
        verify(target, never()).existsNow();
    }

    @Test
    void invalidExplicitTempKeepsBothBusinessFiles() throws Exception {
        for (FileObject bad : new FileObject[] {source, new DecoratedFileObject(target), manager.resolveFile("ram:///temp")}) {
            assertThrows(MoveFailedException.class, () -> source.moveTo(target, bad));
            unchanged();
        }
        verify(source, never()).renameReplacing(any(), any(), any());
    }

    @Test
    void timeRefusalContinuesAndTransportLossStopsBeforeCommit() throws Exception {
        source.setIsMounted(true);
        target.setUpdateLastModified(true);
        FileContent content = spy(temp.getContent());
        doReturn(content).when(temp).getContent();
        doThrow(new FileSystemException(new SocketException())).when(content).setLastModifiedTime(anyLong());
        UnclearResultException e = assertThrows(UnclearResultException.class, () -> source.moveTo(target, options(true)));
        assertEquals(MovePhase.COPY, e.getPhase());
        assertEquals(temp.getName().getPath(), e.getTempPath());
        assertFalse(e.isCommitConfirmed());
        unchanged();
        verify(temp, never()).renameReplacing(any(), any(), any());
        doThrow(new FileSystemException("refused")).when(content).setLastModifiedTime(anyLong());
        source.moveTo(target, options(true));
        moved();
    }

    @Test
    void twoExclusiveMovesLeaveTheLosingSourceWhole() throws Exception {
        Files.delete(dst);
        Path competitor = Files.write(dir.resolve("other"), old);
        java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try (StandardFileSystemManager second = new StandardFileSystemManager()) {
            second.init();
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (LocalFile party : new LocalFile[] {source,
                    (LocalFile) FileObjectUtils.getAbstractFileObject(second.resolveFile(competitor.toUri()))}) {
                FileObject name=party.getFileSystem().getFileSystemManager().resolveFile(dst.toUri());
                results.add(pool.submit(() -> {
                    gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        party.moveTo(name, new MoveOptions(false, null, null, null, null));
                        return true;
                    } catch (MoveFailedException e) {
                        assertTrue(e.isTargetExists());
                        assertEquals(MovePhase.RENAME,e.getPhase());
                        return false;
                    }
                }));
            }
            boolean first=results.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(first, results.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertArrayEquals(first ? next : old,Files.readAllBytes(dst));
            assertFalse(Files.exists(first ? src : competitor));
            assertArrayEquals(first ? old : next,Files.readAllBytes(first ? competitor : src));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unsupportedRenameUsesFreshTargetState() throws Exception {
        Files.delete(dst);
        target.refresh();
        assertFalse(target.exists());
        Files.write(dst,old);
        rename(AtomicOutcome.UNSUPPORTED);
        source.moveTo(target, options(true));
        moved();
        verify(target).delete();
        verify(source).takeAway(target);
    }

    @Test
    void localParentAliasesIdentifyEntriesAcrossFileSystemViews() throws Exception {
        final Path alias = Files.createSymbolicLink(dir.resolve("via"), dir);
        source.setIsMounted(true);
        try (StandardFileSystemManager other = new StandardFileSystemManager()) {
            other.init();
            final FileObject same = new DecoratedFileObject(other.resolveFile(alias.resolve("source").toUri()));
            source.moveTo(same);
            assertArrayEquals(next, Files.readAllBytes(src));
            final MoveFailedException exists = assertThrows(MoveFailedException.class,
                    () -> source.moveTo(same, new MoveOptions(false, null, null, null, null)));
            assertTrue(exists.isTargetExists());
        }
        for (Path aliasName : new Path[] {alias.resolve("source"), alias.resolve("target")}) {
            final FileObject bad = new DecoratedFileObject(file(aliasName));
            assertThrows(MoveFailedException.class, () -> source.moveTo(target, bad));
            unchanged();
        }
    }

    @Test
    void rejectsExistingTempAliasesAndDanglingTempSymlinksBeforeWrite() throws Exception {
        source.setIsMounted(true);
        for (Path business : new Path[] {src, dst}) {
            for (boolean symbolic : new boolean[] {false, true}) {
                if (symbolic) {
                    Files.createSymbolicLink(staging, business);
                } else {
                    Files.createLink(staging, business);
                }
                assertThrows(MoveFailedException.class, () -> source.moveTo(target, temp));
                assertTrue(Files.isSameFile(staging, business));
                unchanged();
                Files.delete(staging);
            }
        }
        Files.delete(dst);
        Files.createSymbolicLink(staging, dst);
        assertThrows(MoveFailedException.class, () -> source.moveTo(target, temp));
        assertFalse(Files.exists(dst));
        assertTrue(Files.isSymbolicLink(staging));
        assertArrayEquals(next, Files.readAllBytes(src));
    }

    @Test
    void mountedMoveOfDistinctHardLinkOrLeafSymlinkMovesOneName() throws Exception {
        for (boolean symbolic : new boolean[] {false, true}) {
            Files.write(src, next);
            Files.delete(dst);
            if (symbolic) {
                Files.createSymbolicLink(dst, src);
            } else {
                Files.createLink(dst, src);
            }
            final LocalFile from = file(src);
            final LocalFile to = file(dst);
            from.setIsMounted(true);
            from.moveTo(to);
            assertFalse(Files.exists(src));
            assertFalse(Files.isSymbolicLink(dst));
            assertArrayEquals(next, Files.readAllBytes(dst));
        }
    }

    @Test
    void onlyTwoKnownFilePairsAllowAnUnlockedMove() throws Exception {
        final Path from = Files.createDirectory(dir.resolve("from"));
        final Path into = Files.createDirectory(dir.resolve("into"));
        final Path temps = Files.createDirectory(dir.resolve("temps"));
        LocalFile business = file(Files.write(from.resolve("data"), next));
        LocalFile finished = file(Files.write(temps.resolve("finished"), next));
        LocalFile name = file(into.resolve("dest"));
        business.getType();
        MoveOptions move = new MoveOptions(false, finished, "probe", null, null);
        assertTrue(move.needsTargetLock(business, name));
        assertEquals(AtomicOutcome.DONE, business.linkExclusive(name, "p1").getOutcome());
        assertTrue(move.needsTargetLock(business, name));
        Files.delete(into.resolve("dest"));
        assertEquals(AtomicOutcome.DONE, finished.linkExclusive(name, "p2").getOutcome());
        assertFalse(move.needsTargetLock(business, name));
        assertTrue(move.needsTargetLock(file(from), name));
        verify(business, never()).existsNow();
        verify(finished, never()).existsNow();
    }

    @Test
    void directoryContendersHonorTheCachedLockDecision() throws Exception {
        final Path a = Files.createDirectory(dir.resolve("folderA"));
        final Path b = Files.createDirectory(dir.resolve("folderB"));
        Files.delete(dst);
        final MoveOptions move = options(false);
        source.getType();
        assertEquals(AtomicOutcome.DONE, source.linkExclusive(target, "probe").getOutcome());
        Files.delete(dst);
        assertFalse(move.needsTargetLock(source, target));
        source.refresh();
        assertTrue(move.needsTargetLock(source, target));
        final FileObject first = manager.resolveFile(a.toString());
        final FileObject second = manager.resolveFile(b.toString());
        for (FileObject folder : new FileObject[] {first, second, manager.resolveFile(b.toUri())}) {
            assertTrue(move.needsTargetLock(folder, target));
            assertEquals(FileType.FOLDER, folder.getType());
            assertTrue(move.needsTargetLock(folder, target));
        }
        final CyclicBarrier ready = new CyclicBarrier(2);
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> one = pool.submit(lockedMove(first, move, ready));
            Future<Boolean> two = pool.submit(lockedMove(second, move, ready));
            boolean won = one.get(10, TimeUnit.SECONDS);
            assertNotEquals(won, two.get(10, TimeUnit.SECONDS));
            assertTrue(Files.isDirectory(dst));
            assertTrue(Files.isDirectory(won ? b : a));
            assertFalse(Files.exists(won ? a : b));
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> lockedMove(FileObject folder, MoveOptions move, CyclicBarrier ready) {
        return () -> {
            assertTrue(move.needsTargetLock(folder, target));
            ready.await(10, TimeUnit.SECONDS);
            synchronized (target) {
                try {
                    FileObjectUtils.getAbstractFileObject(folder).moveTo(target, move);
                    return true;
                } catch (MoveFailedException e) {
                    assertTrue(e.isTargetExists());
                    return false;
                }
            }
        };
    }

    @Test
    void ordinaryMoveOfTwoHardLinksRemovesOnlyTheSourceName() throws Exception {
        Files.delete(dst);
        Files.createLink(dst, src);
        doThrow(new FileSystemException("refused")).when(source).doDelete();
        MoveFailedException refused = assertThrows(MoveFailedException.class, () -> source.moveTo(target));
        assertEquals(MovePhase.DELETE_SOURCE, refused.getPhase());
        assertTrue(refused.isCommitConfirmed());
        assertTrue(Files.isSameFile(src, dst));
        doCallRealMethod().when(source).doDelete();
        source.moveTo(target);
        moved();
        verify(source, never()).renameReplacing(any(), any(), any());
    }

    @Test
    void ordinaryMoveReplacesTheLeafSymlinkAndPreservesTheSourceBytes() throws Exception {
        Files.delete(dst);
        Files.createSymbolicLink(dst, src);
        source.moveTo(target);
        moved();
        assertFalse(Files.isSymbolicLink(dst));
        verify(source).renameReplacing(eq(target), any(), any());
    }

    @Test
    void folderCopySetsTheRequestedTimeOnlyWhenSupported() throws Exception {
        final long time = 1600000000000L;
        for (int i = 0; i < 5; i++) {
            Path original = Files.createDirectory(dir.resolve("folder"+i));
            Path result = dir.resolve("result"+i);
            Files.setLastModifiedTime(original, FileTime.fromMillis(time));
            LocalFile from = file(original);
            LocalFile to = file(result);
            from.setIsMounted(true);
            to.setUpdateLastModified(i != 1);
            if (i == 2) {
                LocalFileSystem fs = spy((LocalFileSystem) to.getFileSystem());
                doReturn(false).when(fs).hasCapability(Capability.SET_LAST_MODIFIED_FOLDER);
                doReturn(fs).when(to).getFileSystem();
            }
            FileContent content = spy(to.getContent());
            doReturn(content).when(to).getContent();
            if (i == 3) {
                doThrow(new FileSystemException("refused")).when(content).setLastModifiedTime(time);
            } else if (i == 4) {
                doThrow(new FileSystemException(new SocketException())).when(content).setLastModifiedTime(time);
                UnclearResultException error = assertThrows(UnclearResultException.class, () -> from.moveTo(to));
                assertEquals(MovePhase.COPY, error.getPhase());
                assertNull(error.getTempPath());
                assertTrue(Files.isDirectory(original));
                verify(from, never()).doDelete();
                continue;
            }
            from.moveTo(to);
            assertTrue(Files.isDirectory(result));
            assertFalse(Files.exists(original));
            assertEquals(i == 0, Files.getLastModifiedTime(result).toMillis() == time);
            verify(content, times(i == 0 || i == 3 ? 1 : 0)).setLastModifiedTime(time);
        }
    }

    @Test
    void oldTargetRemovalTracksProviderAcknowledgementBeforeNotifications() throws Exception {
        for (boolean direct : new boolean[] {false, true}) {
            for (boolean after : new boolean[] {false, true}) {
                Files.write(dst, old);
                SocketException loss = new SocketException("controlled delete boundary");
                class Target extends LocalFile {
                    int sent;
                    int notified;
                    Target() {
                        super((LocalFileSystem) target.getFileSystem(), "", (AbstractFileName) target.getName());
                    }
                    @Override protected void doDelete() throws Exception {
                        sent++;
                        if (!after) {
                            throw loss;
                        }
                        super.doDelete();
                    }
                    @Override protected void onChange() throws Exception {
                        if (sent > 0) {
                            notified++;
                            throw loss;
                        }
                        super.onChange();
                    }
                }
                Target to = new Target();
                LocalFile from = file(src);
                doReturn(new AtomicResult(AtomicOutcome.UNSUPPORTED, null)).when(from).renameReplacing(eq(to), any(), any());
                UnclearResultException error = assertThrows(UnclearResultException.class, () -> {
                    if (direct) {
                        from.moveTo(to);
                    } else {
                        CommitStep.commit(from, to, CommitStep.Mode.OVERWRITE, new CommitStep.Options(true, null, null, null));
                    }
                });
                assertEquals(after, error.isOldTargetDeleted());
                assertFalse(error.isCommitConfirmed());
                assertEquals(direct ? MovePhase.RENAME : after ? MovePhase.AFTER_DELETE : MovePhase.COMMIT, error.getPhase());
                assertEquals(direct ? null : from.getName().getPath(), error.getTempPath());
                Throwable cause = error;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                assertSame(loss, cause);
                assertEquals(1, to.sent);
                assertEquals(after ? 1 : 0, to.notified);
                assertEquals(!after, Files.exists(dst));
                assertArrayEquals(next, Files.readAllBytes(src));
                verify(from, never()).takeAway(any());
                verify(from, never()).getContent();
            }
        }
    }
}
