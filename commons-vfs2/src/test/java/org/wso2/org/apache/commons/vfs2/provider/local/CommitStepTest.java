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
import java.util.Collections;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.CommitResult;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

public class CommitStepTest {
    @TempDir Path dir;
    private final byte[] previous = {1, 2};
    private final byte[] next = {3, 4, 5};
    private StandardFileSystemManager manager;
    private LocalFile temp;
    private LocalFile target;
    private FileContent targetContent;
    private Path tempPath;
    private Path targetPath;

    @BeforeEach
    void start() throws Exception {
        manager = new StandardFileSystemManager();
        manager.init();
        tempPath = Files.write(dir.resolve("finished"), next);
        targetPath = Files.write(dir.resolve("target"), previous);
        temp = spy((LocalFile) FileObjectUtils.getAbstractFileObject(manager.resolveFile(tempPath.toUri())));
        observeTarget();
    }

    private void observeTarget() throws Exception {
        target = spy((LocalFile) FileObjectUtils.getAbstractFileObject(manager.resolveFile(targetPath.toUri())));
        targetContent = spy(target.getContent());
        doReturn(targetContent).when(target).getContent();
        clearInvocations(target);
    }

    @AfterEach
    void stop() {
        manager.close();
    }

    private CommitResult commit(CommitStep.Mode mode, boolean allowDelete) throws Exception {
        try {
            return CommitStep.commit(temp, target, mode, new CommitStep.Options(allowDelete, "probe", null, null));
        } finally {
            receipt("stream", "start");
            verify(target, never()).getOutputStream();
            verify(target, never()).getOutputStream(anyBoolean());
            verify(targetContent, never()).getOutputStream();
            verify(targetContent, never()).getOutputStream(anyBoolean());
            receipt("stream", "pass");
        }
    }

    // Surefire keeps these receipts with the testcase. A check after a failed assertion has no receipt.
    private void receipt(String claim, String status) {
        System.out.println("COMMIT_CHECK " + claim + " " + status);
    }

    private void replace(AtomicOutcome outcome) throws Exception {
        doReturn(new AtomicResult(outcome, null)).when(temp).renameReplacing(eq(target), any(), any());
    }

    private void oldState() throws Exception {
        assertArrayEquals(previous, Files.readAllBytes(targetPath));
        assertArrayEquals(next, Files.readAllBytes(tempPath));
        verify(target, never()).getContent();
    }

    @Test
    void failureBeforeFirstProviderCallLeavesBothFiles() throws Exception {
        doThrow(new IllegalStateException("Stopped before the first call")).when(temp).getFileSystem();
        receipt("outcome", "start");
        assertThrows(IllegalStateException.class, () -> commit(CommitStep.Mode.OVERWRITE, true));
        verify(temp, never()).renameReplacing(any(), any(), any());
        receipt("outcome", "pass");
        receipt("state", "start");
        oldState();
        receipt("state", "pass");
    }

    @Test
    void stoppedReplaceTestReturnsItsDefinitiveRefusal() throws Exception {
        AtomicResult stopped = new AtomicResult(AtomicOutcome.REFUSED, "probe refused");
        doReturn(stopped).when(temp).renameReplacing(eq(target), any(), any());
        receipt("outcome", "start");
        CommitResult result = commit(CommitStep.Mode.OVERWRITE, true);
        assertEquals(CommitResult.Outcome.APPLICATION_ERROR, result.getOutcome());
        assertSame(stopped, result.getAtomicResult());
        verify(target, never()).delete();
        receipt("outcome", "pass");
        receipt("state", "start");
        oldState();
        receipt("state", "pass");
    }

    @Test
    void refusalKeepsOldTarget() throws Exception {
        replace(AtomicOutcome.REFUSED);
        receipt("outcome", "start");
        assertEquals(CommitResult.Outcome.APPLICATION_ERROR, commit(CommitStep.Mode.OVERWRITE, true).getOutcome());
        verify(target, never()).delete();
        verify(temp, never()).takeAway(any());
        receipt("outcome", "pass");
        receipt("state", "start");
        oldState();
        receipt("state", "pass");
    }

    @Test
    void lostReplyAfterRenameMakesNoFurtherCall() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new FileSystemException(new SocketException("reply lost"));
        }).when(temp).renameReplacing(eq(target), any(), any());
        receipt("outcome", "start");
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> commit(CommitStep.Mode.OVERWRITE, true));
        assertEquals(MovePhase.COMMIT, error.getPhase());
        assertEquals(temp.getName().getPath(), error.getTempPath());
        assertFalse(error.isCommitConfirmed());
        receipt("outcome", "pass");
        receipt("state", "start");
        assertFalse(Files.exists(tempPath));
        assertArrayEquals(next, Files.readAllBytes(targetPath));
        receipt("state", "pass");
        verify(temp, never()).existsNow();
        verify(target, never()).existsNow();
        verify(target, never()).delete();
        verify(temp, never()).close();
        verify(temp, never()).takeAway(any());
    }

    @Test
    void interruptBeforeMutationKeepsBothFiles() throws Exception {
        doThrow(new FileSystemException(new SocketException())).when(temp).renameReplacing(eq(target), any(), any());
        receipt("outcome", "start");
        assertThrows(UnclearResultException.class, () -> commit(CommitStep.Mode.OVERWRITE, true));
        receipt("outcome", "pass");
        receipt("state", "start");
        oldState();
        receipt("state", "pass");
    }

    @Test
    void transportInProbePreservesItsKnownOutcome() throws Exception {
        AtomicResult probe = new AtomicResult(AtomicOutcome.REFUSED, "probe", Collections.singletonList("probeA"), true);
        doReturn(probe).when(temp).renameReplacing(eq(target), any(), any());
        receipt("outcome", "start");
        CommitResult result = commit(CommitStep.Mode.OVERWRITE, true);
        assertSame(probe, result.getAtomicResult());
        assertTrue(result.isConnectionLost());
        assertEquals(probe.getLeftovers(), result.getLeftovers());
        verify(target, never()).delete();
        receipt("outcome", "pass");
        receipt("state", "start");
        oldState();
        receipt("state", "pass");
    }

    @Test
    void lostLookMakesNoDelete() throws Exception {
        replace(AtomicOutcome.UNSUPPORTED);
        doThrow(new FileSystemException(new SocketException())).when(target).existsNow();
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> commit(CommitStep.Mode.OVERWRITE, true));
        assertEquals(MovePhase.COMMIT, error.getPhase());
        assertFalse(error.isOldTargetDeleted());
        verify(target, never()).delete();
        oldState();
    }

    @Test
    void refusedDeleteKeepsOldTarget() throws Exception {
        replace(AtomicOutcome.UNSUPPORTED);
        FileSystemException denied = new FileSystemException("denied");
        denied.setFault(Fault.APPLICATION);
        doThrow(denied).when(target).delete();
        CommitResult result = commit(CommitStep.Mode.OVERWRITE, true);
        assertEquals(CommitResult.Outcome.APPLICATION_ERROR, result.getOutcome());
        assertFalse(result.isOldTargetDeleted());
        verify(temp, never()).takeAway(any());
        oldState();
    }

    @Test
    void cutAfterDeleteCarriesTheDeletedFlag() throws Exception {
        replace(AtomicOutcome.UNSUPPORTED);
        doThrow(new FileSystemException(new SocketException())).when(temp).takeAway(target);
        receipt("outcome", "start");
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> commit(CommitStep.Mode.OVERWRITE, true));
        assertEquals(MovePhase.AFTER_DELETE, error.getPhase());
        assertTrue(error.isOldTargetDeleted());
        receipt("outcome", "pass");
        receipt("state", "start");
        assertFalse(Files.exists(targetPath));
        assertArrayEquals(next, Files.readAllBytes(tempPath));
        receipt("state", "pass");
    }

    @Test
    void refusedRenameAfterDeleteLeavesTheFinishedTemp() throws Exception {
        replace(AtomicOutcome.UNSUPPORTED);
        doReturn(new AtomicResult(AtomicOutcome.REFUSED, "refused")).when(temp).takeAway(target);
        receipt("outcome", "start");
        CommitResult result = commit(CommitStep.Mode.OVERWRITE, true);
        assertEquals(CommitResult.Outcome.APPLICATION_ERROR, result.getOutcome());
        assertTrue(result.isOldTargetDeleted());
        receipt("outcome", "pass");
        receipt("state", "start");
        assertFalse(Files.exists(targetPath));
        assertArrayEquals(next, Files.readAllBytes(tempPath));
        receipt("state", "pass");
    }

    @Test
    void freshLookFindsAnOldTargetInACachedAbsentObject() throws Exception {
        Files.delete(targetPath);
        target.refresh();
        assertFalse(target.exists());
        Files.write(targetPath, previous);
        replace(AtomicOutcome.UNSUPPORTED);
        CommitResult result = commit(CommitStep.Mode.OVERWRITE, true);
        assertEquals(CommitResult.Outcome.COMMITTED, result.getOutcome());
        assertTrue(result.isOldTargetDeleted());
        assertArrayEquals(next, Files.readAllBytes(targetPath));
        assertFalse(Files.exists(tempPath));
    }

    @Test
    void noDeleteOptionSendsNoProbes() throws Exception {
        replace(AtomicOutcome.UNSUPPORTED);
        CommitResult result = CommitStep.commit(temp, target, CommitStep.Mode.OVERWRITE,
                new CommitStep.Options(false, "probe", target, temp));
        assertEquals(CommitResult.Outcome.REPLACE_NOT_POSSIBLE, result.getOutcome());
        verify(temp).renameReplacing(target, null, null);
        verify(target, never()).existsNow();
        oldState();
    }

    @Test
    void existingTargetRefusesExclusivePublish() throws Exception {
        assertEquals(CommitResult.Outcome.TARGET_EXISTS, commit(CommitStep.Mode.CREATE_NEW, false).getOutcome());
        oldState();
    }

    @Test
    void lostLinkReplyCanLeaveBothNames() throws Exception {
        Files.delete(targetPath);
        doAnswer(call -> {
            Files.createLink(targetPath, tempPath);
            throw new FileSystemException(new SocketException());
        }).when(temp).publishExclusive(target, "probe");
        receipt("outcome", "start");
        UnclearResultException error = assertThrows(UnclearResultException.class, () -> commit(CommitStep.Mode.CREATE_NEW, true));
        assertEquals(MovePhase.COMMIT, error.getPhase());
        receipt("outcome", "pass");
        receipt("state", "start");
        assertTrue(Files.isSameFile(targetPath, tempPath));
        receipt("state", "pass");
        verify(temp, never()).delete();
    }

    @Test
    void completedCommitKeepsExternalStreamAndInvalidatesParentListing() throws Exception {
        final org.wso2.org.apache.commons.vfs2.FileObject folder = manager.resolveFile(dir.toUri());
        assertEquals(2, folder.getChildren().length);
        try (java.io.InputStream external = target.getContent().getInputStream()) {
            assertEquals(previous[0], external.read());
            assertEquals(CommitResult.Outcome.COMMITTED, commit(CommitStep.Mode.OVERWRITE, true).getOutcome());
            assertEquals(previous[1], external.read());
            assertEquals(1, folder.getChildren().length);
            assertEquals(next.length, target.getContent().getSize());
            verify(target, never()).close();
            verify(temp, never()).close();
        }
    }

    @Test
    void lostCleanupReplyDoesNotUndoConfirmedLink() throws Exception {
        lostCleanupKeepsPublicationVisible(true);
    }

    @Test
    void acknowledgedLinkWithUnknownRemovalInvalidatesBothParentListings() throws Exception {
        lostCleanupKeepsPublicationVisible(false);
    }

    private void lostCleanupKeepsPublicationVisible(boolean tempRemains) throws Exception {
        Files.delete(targetPath);
        Path into = Files.createDirectory(dir.resolve("into"));
        targetPath = into.resolve("target");
        observeTarget();
        assertFalse(target.exists());
        org.wso2.org.apache.commons.vfs2.FileObject oldParent = temp.getParent();
        org.wso2.org.apache.commons.vfs2.FileObject newParent = target.getParent();
        assertEquals(2, oldParent.getChildren().length);
        assertEquals(0, newParent.getChildren().length);
        doAnswer(call -> {
            Files.createLink(targetPath, tempPath);
            if (!tempRemains) {
                Files.delete(tempPath);
            }
            return new AtomicResult(AtomicOutcome.DONE, "cleanup", Collections.singletonList(tempPath.toString()), true);
        }).when(temp).publishExclusive(target, "probe");
        try (java.io.InputStream input = temp.getContent().getInputStream()) {
            receipt("outcome", "start");
            CommitResult result = commit(CommitStep.Mode.CREATE_NEW, true);
            assertEquals(CommitResult.Outcome.COMMITTED, result.getOutcome());
            assertEquals(CommitResult.Method.LINK, result.getMethod());
            assertTrue(result.isConnectionLost());
            assertEquals(Collections.singletonList(tempPath.toString()), result.getLeftovers());
            receipt("outcome", "pass");
            receipt("state", "start");
            assertArrayEquals(next, Files.readAllBytes(targetPath));
            verify(temp, never()).delete();
            verify(target, never()).existsNow();
            assertEquals(next[0], input.read());
            assertTrue(target.exists());
            assertEquals(1, newParent.getChildren().length);
            assertEquals(tempRemains ? 2 : 1, oldParent.getChildren().length);
            assertEquals(tempRemains, temp.exists());
            receipt("state", "pass");
        }
    }
}
