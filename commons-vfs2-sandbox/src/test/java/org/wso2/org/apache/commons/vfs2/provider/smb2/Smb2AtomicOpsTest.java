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
package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.wso2.org.apache.commons.vfs2.FileListener;
import org.wso2.org.apache.commons.vfs2.cache.DefaultFilesCache;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext;
import org.junit.jupiter.api.function.Executable;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject.EntryRelation;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;

/**
 * The calls of AtomicOps on SMB2, part 3 section 6.5, on the doubles of the client. One test per status row.
 */
public class Smb2AtomicOpsTest {

    private static final EnumSet<AccessMask> LINK_ACCESS = EnumSet.of(AccessMask.DELETE,
            AccessMask.FILE_READ_ATTRIBUTES);

    private Client client;

    private Client spare;

    private Smb2FileSystem fileSystem;

    @BeforeEach
    public void open() throws Exception {
        client = new Client();
        spare = new Client();
        fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, spare.wrapper));
    }

    private Smb2FileObject file(final String path) throws FileSystemException {
        return new Smb2FileObject(Smb2Doubles.name(path), fileSystem, Smb2Doubles.rootName());
    }

    private static SMBApiException status(final NtStatus status) {
        return new SMBApiException(status.getValue(), SMB2MessageCommandCode.SMB2_CREATE, status.name(), null);
    }

    @Test
    public void publicTypeReportsProviderFaultAndStatus() throws Exception {
        final SMBApiException denied = status(NtStatus.STATUS_ACCESS_DENIED);
        client.metadataOpenFails(denied);
        final FileSystemException result = assertThrows(FileSystemException.class, () -> file("in/a.csv").getType());
        assertEquals(Fault.APPLICATION, result.getFault());
        assertTrue(result.getMessage().contains("STATUS_ACCESS_DENIED"));
        assertSame(denied, result.getCause().getCause().getCause());
    }

    private static SMBRuntimeException transport() {
        return new SMBRuntimeException(new TransportException("the connection is gone"));
    }

    private File created(final String path) {
        final File file = mock(File.class);
        doReturn(file).when(client.share).openFile(eq(path), any(), any(), any(),
                eq(SMB2CreateDisposition.FILE_CREATE), any());
        return file;
    }

    private DiskEntry linkHandle(final String path) {
        final DiskEntry entry = mock(DiskEntry.class);
        doReturn(entry).when(client.share).open(eq(path), eq(LINK_ACCESS), any(), eq(SMB2ShareAccess.ALL),
                eq(SMB2CreateDisposition.FILE_OPEN), isNull());
        return entry;
    }

    private DiskEntry renameHandle(final String path) {
        final DiskEntry entry = mock(DiskEntry.class);
        doReturn(entry).when(client.share).open(eq(path), eq(EnumSet.of(AccessMask.MAXIMUM_ALLOWED)), any(),
                eq(EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE)), eq(SMB2CreateDisposition.FILE_OPEN), any());
        return entry;
    }

    private boolean clientKept() throws FileSystemException {
        return fileSystem.getClient() == client.wrapper;
    }

    // An unconfirmed change throws UnclearResultException with the phase and the temp path of the public call. It
    // keeps the original cause, has the fault TRANSPORT and no deleted old target, and the client is discarded.
    private UnclearResultException unclear(final MovePhase phase, final String tempPath, final Throwable cause,
            final Executable call) throws FileSystemException {
        final UnclearResultException error = assertThrows(UnclearResultException.class, call);
        assertEquals(phase, error.getPhase());
        assertEquals(tempPath, error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(cause, error.getCause());
        assertTrue(client.group.isAborted());
        return error;
    }

    // The open of a folder for a rename: the open of a directory with its own access and share flags.
    private Directory folderHandle(final String path) {
        final Directory entry = mock(Directory.class);
        doReturn(entry).when(client.share).openDirectory(eq(path), eq(EnumSet.of(AccessMask.GENERIC_ALL)),
                eq(EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL)), eq(EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ)),
                eq(SMB2CreateDisposition.FILE_OPEN), eq(EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)));
        return entry;
    }

    @Test
    public void rootIsItsOwnFolderInBothQueryFormsAndTakesNoClient() throws Exception {
        final Smb2FileObject root = file("");
        assertEquals(null, root.getName().getParent());
        final Smb2FileObject file = file("in/a.csv");
        assertEquals(Answer.YES, root.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, root.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.YES, root.atomicCapabilities(file).getReplacingRename());
        assertEquals(Answer.NOT_KNOWN, file.atomicCapabilities(root).getExclusivePublish());
        assertEquals(Answer.YES, file.atomicCapabilities(root).getReplacingRename());
        verifyNoInteractions(client.share);
    }

    @Test
    public void capabilitiesBeforeAnyCall() throws Exception {
        final Smb2FileObject lock = file("in/a.lock");
        assertEquals(Answer.YES, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.YES, lock.atomicCapabilities().getReplacingRename());
    }

    // createExclusive.

    @Test
    public void exclusiveCreateOpensWithFileCreateWritesAndCloses() throws Exception {
        final File handle = created("in\\a.lock");
        final AtomicResult result = file("in/a.lock").createExclusive(new byte[] {1, 2}, "s", "p");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        verify(client.share).openFile("in\\a.lock", EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
        verify(handle).write(new byte[] {1, 2}, 0);
        verify(handle).close();
        assertTrue(clientKept());
    }

    @Test
    public void exclusiveCreateReadsTheStatusOfTheOpen() throws Exception {
        final NtStatus[] statuses = {NtStatus.STATUS_OBJECT_NAME_COLLISION, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_ACCESS_DENIED};
        final AtomicOutcome[] outcomes = {AtomicOutcome.EXISTS, AtomicOutcome.GONE, AtomicOutcome.REFUSED};
        for (int i = 0; i < statuses.length; i++) {
            open();
            doThrow(status(statuses[i])).when(client.share).openFile(anyString(), any(), any(), any(), any(), any());
            final AtomicResult result = file("in/a.lock").createExclusive(new byte[] {1}, "s", "p");
            assertEquals(outcomes[i], result.getOutcome());
            assertEquals(statuses[i].name(), result.getDetail());
            assertTrue(clientKept());
        }
    }

    @Test
    public void exclusiveCreateWithATransportErrorDiscardsTheClient() throws Exception {
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(client.share).openFile(anyString(), any(), any(), any(), any(), any());
        // The create may have worked. The call throws UnclearResultException in the phase COMMIT. There is no scratch
        // file on SMB2, so the temp path is null.
        unclear(MovePhase.COMMIT, null, cause, () -> file("in/a.lock").createExclusive(new byte[] {1}, "s", "p"));
        assertFalse(clientKept());
    }

    @Test
    public void refusedWriteRemovesTheOwnFileAndATransportErrorLeavesIt() throws Exception {
        final File refused = created("in\\a.lock");
        doThrow(status(NtStatus.STATUS_DISK_FULL)).when(refused).write(any(byte[].class), anyLong());
        final AtomicResult result = file("in/a.lock").createExclusive(new byte[] {1}, "s", "p");
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        verify(client.share).rm("in\\a.lock");
        verify(refused).close();

        open();
        final File cut = created("in\\a.lock");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(cut).write(any(byte[].class), anyLong());
        unclear(MovePhase.COMMIT, null, cause, () -> file("in/a.lock").createExclusive(new byte[] {1}, "s", "p"));
        verify(client.share, never()).rm(anyString());
    }

    // linkExclusive and publishExclusive.

    @Test
    public void linkOpensWithTheOpenOfTheLockTestAndLeavesTheSource() throws Exception {
        final DiskEntry entry = linkHandle("in\\a.csv");
        final Smb2FileObject source = file("in/a.csv");
        final Smb2FileObject target = file("out/a.csv");
        assertEquals(AtomicOutcome.DONE, source.linkExclusive(target, "p").getOutcome());
        verify(entry).createHardlink("out\\a.csv", false);
        verify(entry).close();
        verify(client.share, never()).rm(anyString());
        verify(entry, never()).rename(anyString(), anyBoolean());
        assertEquals(Answer.YES, source.atomicCapabilities(target).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, target.atomicCapabilities(source).getExclusivePublish());
    }

    @Test
    public void linkReadsTheTypedStatuses() throws Exception {
        final NtStatus[] statuses = {NtStatus.STATUS_OBJECT_NAME_COLLISION, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_SHARING_VIOLATION, NtStatus.STATUS_DELETE_PENDING};
        final AtomicOutcome[] outcomes = {AtomicOutcome.EXISTS, AtomicOutcome.GONE, AtomicOutcome.GONE,
            AtomicOutcome.BUSY, AtomicOutcome.BUSY};
        for (int i = 0; i < statuses.length; i++) {
            open();
            final DiskEntry entry = linkHandle("in\\a.csv");
            doThrow(status(statuses[i])).when(entry).createHardlink(anyString(), anyBoolean());
            final Smb2FileObject source = file("in/a.csv");
            final Smb2FileObject target = file("out/a.csv");
            assertEquals(outcomes[i], source.linkExclusive(target, "p").getOutcome(), statuses[i].name());
            verify(entry).close();
            assertEquals(Answer.NOT_KNOWN, source.atomicCapabilities(target).getExclusivePublish());
        }
    }

    @Test
    public void otherStatusMarksThePairNoLinkAndTheMarkSendsNothing() throws Exception {
        final DiskEntry entry = linkHandle("in\\a.csv");
        doThrow(status(NtStatus.STATUS_ACCESS_DENIED)).when(entry).createHardlink(anyString(), anyBoolean());
        final Smb2FileObject source = file("in/a.csv");
        final Smb2FileObject target = file("out/a.csv");
        final AtomicResult first = source.linkExclusive(target, "p");
        assertEquals(AtomicOutcome.UNSUPPORTED, first.getOutcome());
        assertEquals("STATUS_ACCESS_DENIED", first.getDetail());
        assertEquals(Answer.NO, source.atomicCapabilities(target).getExclusivePublish());
        assertEquals(AtomicOutcome.UNSUPPORTED, source.linkExclusive(target, "p").getOutcome());
        assertEquals(AtomicOutcome.UNSUPPORTED, source.publishExclusive(target, "p").getOutcome());
        verify(client.share).open(eq("in\\a.csv"), eq(LINK_ACCESS), any(), any(), any(), any());
        // Another pair is not marked.
        assertEquals(Answer.NOT_KNOWN, source.atomicCapabilities(file("x/a.csv")).getExclusivePublish());
    }

    @Test
    public void pairThatLinkedOnceIsRefusedForAnotherStatusAndNeverMarked() throws Exception {
        final DiskEntry entry = linkHandle("in\\a.csv");
        final Smb2FileObject source = file("in/a.csv");
        final Smb2FileObject target = file("out/a.csv");
        assertEquals(AtomicOutcome.DONE, source.linkExclusive(target, "p").getOutcome());
        doThrow(status(NtStatus.STATUS_ACCESS_DENIED)).when(entry).createHardlink(anyString(), anyBoolean());
        assertEquals(AtomicOutcome.REFUSED, source.linkExclusive(target, "p").getOutcome());
        assertEquals(Answer.YES, source.atomicCapabilities(target).getExclusivePublish());
    }

    @Test
    public void markEndsAfterTenMinutesOnTheMonotonicClockAndTheFirstMarkWarnsOnce() throws Exception {
        final long now = 5_000_000_000L;
        assertTrue(fileSystem.markNoLink("a", now));
        assertTrue(fileSystem.isMarkedNoLink("a", now + TimeUnit.MINUTES.toNanos(10) - 1));
        assertFalse(fileSystem.isMarkedNoLink("a", now + TimeUnit.MINUTES.toNanos(10)));
        // A refusal after the mark ended marks it for 10 more minutes at once, with no second WARN.
        assertFalse(fileSystem.markNoLink("a", now + TimeUnit.MINUTES.toNanos(10)));
        assertTrue(fileSystem.isMarkedNoLink("a", now + TimeUnit.MINUTES.toNanos(20) - 1));
        fileSystem.markLinks("a");
        assertFalse(fileSystem.isMarkedNoLink("a", now));
        assertFalse(fileSystem.markNoLink("a", now));
        assertTrue(fileSystem.isLinked("a"));
    }

    @Test
    public void transportErrorOnTheCloseAfterTheLinkIsDoneWithTheConnectionLost() throws Exception {
        final DiskEntry entry = linkHandle("in\\a.csv");
        doThrow(transport()).when(entry).close();
        final AtomicResult result = file("in/a.csv").linkExclusive(file("out/a.csv"), "p");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertFalse(clientKept());
    }

    @Test
    public void transportErrorOnTheLinkDiscardsTheClientAndThrows() throws Exception {
        final DiskEntry entry = linkHandle("in\\a.csv");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(entry).createHardlink(anyString(), anyBoolean());
        unclear(MovePhase.RENAME, null, cause, () -> file("in/a.csv").linkExclusive(file("out/a.csv"), "p"));
        assertFalse(clientKept());
    }

    @Test
    public void transportErrorOnThePublishLinkIsUnclearWithTheTempPath() throws Exception {
        final DiskEntry entry = linkHandle("in\\.part\\a.tmp");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(entry).createHardlink(anyString(), anyBoolean());
        final Smb2FileObject temp = file("in/.part/a.tmp");
        unclear(MovePhase.COMMIT, temp.getName().getPath(), cause, () -> temp.publishExclusive(file("in/t.bin"), "p"));
        verify(client.share, never()).rm(anyString());
    }

    @Test
    public void publishRemovesTheTempNameOnlyAfterDone() throws Exception {
        final DiskEntry entry = linkHandle("in\\.part\\a.tmp");
        final Smb2FileObject temp = file("in/.part/a.tmp");
        final Smb2FileObject target = file("in/t.bin");
        assertEquals(AtomicOutcome.DONE, temp.publishExclusive(target, "p").getOutcome());
        verify(client.share).rm("in\\.part\\a.tmp");

        open();
        final DiskEntry exists = linkHandle("in\\.part\\a.tmp");
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_COLLISION)).when(exists).createHardlink(anyString(), anyBoolean());
        assertEquals(AtomicOutcome.EXISTS, file("in/.part/a.tmp").publishExclusive(file("in/t.bin"), "p")
                .getOutcome());
        verify(client.share, never()).rm(anyString());
        verify(entry).createHardlink("in\\t.bin", false);
    }

    @Test
    public void publishWhoseRemovalFailsIsDoneWithALeftover() throws Exception {
        linkHandle("in\\.part\\a.tmp");
        doThrow(status(NtStatus.STATUS_ACCESS_DENIED)).when(client.share).rm("in\\.part\\a.tmp");
        final AtomicResult result = file("in/.part/a.tmp").publishExclusive(file("in/t.bin"), "p");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        assertEquals(1, result.getLeftovers().size());
        assertFalse(result.isConnectionLost());
    }

    // renameReplacing and takeAway.

    @Test
    public void replacingRenameSendsTheReplaceFlagAndNeverUnsupported() throws Exception {
        final DiskEntry entry = renameHandle("in\\t.tmp");
        assertEquals(AtomicOutcome.DONE, file("in/t.tmp").renameReplacing(file("in/t.csv"), file("in/pa"),
                file("in/pb")).getOutcome());
        verify(entry).rename("in\\t.csv", true);
        final NtStatus[] statuses = {NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_SHARING_VIOLATION, NtStatus.STATUS_NOT_SAME_DEVICE, NtStatus.STATUS_ACCESS_DENIED};
        final AtomicOutcome[] outcomes = {AtomicOutcome.GONE, AtomicOutcome.GONE, AtomicOutcome.BUSY,
            AtomicOutcome.REFUSED, AtomicOutcome.REFUSED};
        for (int i = 0; i < statuses.length; i++) {
            open();
            final DiskEntry refused = renameHandle("in\\t.tmp");
            doThrow(status(statuses[i])).when(refused).rename(anyString(), anyBoolean());
            final AtomicResult result = file("in/t.tmp").renameReplacing(file("in/t.csv"), null, null);
            assertEquals(outcomes[i], result.getOutcome(), statuses[i].name());
            assertEquals(statuses[i].name(), result.getDetail());
        }
    }

    @Test
    public void transportErrorOnTheReplacingRenameIsUnclearWithTheTempPath() throws Exception {
        final DiskEntry entry = renameHandle("in\\t.tmp");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(entry).rename(anyString(), anyBoolean());
        final Smb2FileObject temp = file("in/t.tmp");
        unclear(MovePhase.COMMIT, temp.getName().getPath(), cause,
                () -> temp.renameReplacing(file("in/t.csv"), null, null));
    }

    @Test
    public void closeErrorAfterARenameThatReturnedIsDoneAndDiscardsTheClient() throws Exception {
        final DiskEntry entry = renameHandle("in\\t.tmp");
        doThrow(transport()).when(entry).close();
        final AtomicResult result = file("in/t.tmp").renameReplacing(file("in/t.csv"), null, null);
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertFalse(clientKept());
    }

    // Only the SMB server and connection are doubles. The public move uses the provider's actual rename,
    // handle cleanup, client pool and file events. A folder selects takeAway on a no-replace move.
    private final class PublicRename {
        final Smb2FileObject source = file("in/src");
        final Smb2FileObject target = file("in/dst");
        final FileListener listener = mock(FileListener.class);
        final MoveOptions options;
        final DiskEntry entry;

        PublicRename(final boolean replace) throws Exception {
            final DefaultFileSystemManager manager = new DefaultFileSystemManager();
            manager.setFilesCache(new DefaultFilesCache());
            final VfsComponentContext context = mock(VfsComponentContext.class);
            when(context.getFileSystemManager()).thenReturn(manager);
            fileSystem.setContext(context);
            fileSystem.addListener(source, listener);
            fileSystem.addListener(target, listener);
            client.info(Smb2ClientWrapperTest.info(!replace));
            doThrow(status(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)).when(client.share).open(eq("in\\dst"),
                    eq(Smb2Doubles.METADATA), isNull(), eq(SMB2ShareAccess.ALL), eq(SMB2CreateDisposition.FILE_OPEN),
                    isNull());
            entry = replace ? renameHandle("in\\src") : folderHandle("in\\src");
            options = new MoveOptions(replace, file("in/stage"), null, file("in/probeA"), file("in/probeB"));
        }

        void move() throws FileSystemException {
            source.moveTo(target, options);
        }

        void verifyHandleAndClient(final boolean lost) throws Exception {
            verify(entry).rename("in\\dst", options.isReplaceExisting());
            verify(entry).close();
            verifyNoMoreInteractions(entry);
            verifyNoInteractions(spare.share);
            assertEquals(lost, client.group.isAborted());
            assertEquals(lost ? 1 : 0, client.closes.get());
            assertSame(lost ? spare.wrapper : client.wrapper, fileSystem.getClient());
        }
    }

    @ParameterizedTest
    @CsvSource({"true, false", "true, true", "false, false", "false, true"})
    public void confirmedPublicRenameCompletesAndNotifiesEvenWhenCloseLosesTheClient(
            final boolean replace, final boolean closeLosesClient) throws Exception {
        final PublicRename call = new PublicRename(replace);
        if (closeLosesClient) {
            doThrow(transport()).when(call.entry).close();
        }
        call.move();
        verify(call.listener).fileCreated(argThat(event -> event.getFile() == call.target));
        verify(call.listener).fileDeleted(argThat(event -> event.getFile() == call.source));
        verifyNoMoreInteractions(call.listener);
        call.verifyHandleAndClient(closeLosesClient);
    }

    @Test
    public void refusedPublicRenameFollowedByCloseLossStaysDefinite() throws Exception {
        final PublicRename call = new PublicRename(true);
        doThrow(status(NtStatus.STATUS_ACCESS_DENIED)).when(call.entry).rename(anyString(), anyBoolean());
        doThrow(transport()).when(call.entry).close();
        final MoveFailedException error = assertThrows(MoveFailedException.class, call::move);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertEquals(MovePhase.RENAME, error.getPhase());
        assertFalse(error.isCommitConfirmed());
        assertTrue(error.isConnectionLost());
        verifyNoInteractions(call.listener);
        call.verifyHandleAndClient(true);
    }

    @Test
    public void missingPublicRenameReplyStaysUnclear() throws Exception {
        final PublicRename call = new PublicRename(true);
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(call.entry).rename(anyString(), anyBoolean());
        final UnclearResultException error = unclear(MovePhase.RENAME, null, cause, call::move);
        assertFalse(error.isCommitConfirmed());
        verifyNoInteractions(call.listener);
        call.verifyHandleAndClient(true);
    }

    @Test
    public void takeAwayReadsTheOpenAndTheRename() throws Exception {
        final DiskEntry entry = renameHandle("in\\a.lock");
        assertEquals(AtomicOutcome.DONE, file("in/a.lock").takeAway(file("in/a.x1.lock")).getOutcome());
        verify(entry).rename("in\\a.x1.lock", false);

        open();
        doThrow(status(NtStatus.STATUS_SHARING_VIOLATION)).when(client.share).open(eq("in\\a.lock"),
                eq(EnumSet.of(AccessMask.MAXIMUM_ALLOWED)), any(), any(), any(), any());
        assertEquals(AtomicOutcome.BUSY, file("in/a.lock").takeAway(file("in/a.x1.lock")).getOutcome());

        open();
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)).when(client.share).open(eq("in\\a.lock"),
                eq(EnumSet.of(AccessMask.MAXIMUM_ALLOWED)), any(), any(), any(), any());
        assertEquals(AtomicOutcome.GONE, file("in/a.lock").takeAway(file("in/a.x1.lock")).getOutcome());

        open();
        final DiskEntry gone = renameHandle("in\\a.lock");
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)).when(gone).rename(anyString(), anyBoolean());
        assertEquals(AtomicOutcome.GONE, file("in/a.lock").takeAway(file("in/a.x1.lock")).getOutcome());

        open();
        final DiskEntry taken = renameHandle("in\\a.lock");
        doThrow(status(NtStatus.STATUS_OBJECT_NAME_COLLISION)).when(taken).rename(anyString(), anyBoolean());
        assertEquals(AtomicOutcome.EXISTS, file("in/a.lock").takeAway(file("in/a.x1.lock")).getOutcome());

        open();
        final DiskEntry cut = renameHandle("in\\a.lock");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(cut).rename(anyString(), anyBoolean());
        unclear(MovePhase.RENAME, null, cause, () -> file("in/a.lock").takeAway(file("in/a.x1.lock")));
    }

    @Test
    public void reusedObjectReadsTheTypeOfTheSourceAtTheRename() throws Exception {
        // The object read a folder. Another client put a file in its place. The rename opens a file.
        client.info(Smb2ClientWrapperTest.info(true));
        final Smb2FileObject cachedFolder = file("in/d");
        assertEquals(FileType.FOLDER, cachedFolder.getType());
        client.info(Smb2ClientWrapperTest.info(false));
        final DiskEntry asFile = renameHandle("in\\d");
        assertEquals(AtomicOutcome.DONE, cachedFolder.takeAway(file("in/d.x1")).getOutcome());
        verify(asFile).rename("in\\d.x1", false);
        verify(client.share, never()).openDirectory(anyString(), any(), any(), any(), any(), any());
        assertEquals(FileType.FOLDER, cachedFolder.getType());
        assertEquals(AtomicOutcome.DONE, cachedFolder.renameReplacing(file("in/e"), null, null).getOutcome());
        verify(asFile).rename("in\\e", true);

        // The object read a file. Another client put a folder in its place. The rename opens a directory.
        open();
        client.info(Smb2ClientWrapperTest.info(false));
        final Smb2FileObject cachedFile = file("in/f");
        assertEquals(FileType.FILE, cachedFile.getType());
        client.info(Smb2ClientWrapperTest.info(true));
        final Directory asFolder = folderHandle("in\\f");
        assertEquals(AtomicOutcome.DONE, cachedFile.takeAway(file("in/f.x1")).getOutcome());
        verify(asFolder).rename("in\\f.x1", false);
        assertEquals(AtomicOutcome.DONE, cachedFile.renameReplacing(file("in/g"), null, null).getOutcome());
        verify(asFolder).rename("in\\g", true);
        verify(client.share, never()).open(eq("in\\f"), eq(EnumSet.of(AccessMask.MAXIMUM_ALLOWED)), any(), any(),
                any(), any());
    }

    @Test
    public void pendingDeleteAtTheTypeReadIsRefusedWithNoRename() throws Exception {
        final DiskEntry entry = renameHandle("in\\a.lock");
        doThrow(status(NtStatus.STATUS_DELETE_PENDING)).when(client.share).open(eq("in\\a.lock"),
                eq(EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA)), isNull(),
                eq(SMB2ShareAccess.ALL), eq(SMB2CreateDisposition.FILE_OPEN), isNull());
        final AtomicResult take = file("in/a.lock").takeAway(file("in/a.x1.lock"));
        assertEquals(AtomicOutcome.REFUSED, take.getOutcome());
        assertEquals(NtStatus.STATUS_DELETE_PENDING.name(), take.getDetail());
        final AtomicResult replace = file("in/a.lock").renameReplacing(file("in/a.csv"), null, null);
        assertEquals(AtomicOutcome.REFUSED, replace.getOutcome());
        assertEquals(NtStatus.STATUS_DELETE_PENDING.name(), replace.getDetail());
        verify(entry, never()).rename(anyString(), anyBoolean());
        assertTrue(clientKept());
    }

    @Test
    public void typeReadThatLosesTheConnectionSendsNoRename() throws Exception {
        final DiskEntry entry = renameHandle("in\\a.lock");
        final SMBRuntimeException cause = transport();
        doThrow(cause).when(client.share).open(eq("in\\a.lock"), eq(EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES,
                AccessMask.FILE_READ_EA)), isNull(), eq(SMB2ShareAccess.ALL), eq(SMB2CreateDisposition.FILE_OPEN),
                isNull());
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> file("in/a.lock").takeAway(file("in/a.x1.lock")));
        assertFalse(error instanceof UnclearResultException);
        assertEquals(Fault.TRANSPORT, error.getFault());
        verify(entry, never()).rename(anyString(), anyBoolean());
        assertFalse(clientKept());
    }

    @Test
    public void folderRenameOpensADirectoryAndASharingConflictIsBusy() throws Exception {
        client.info(Smb2ClientWrapperTest.info(true));
        final Directory away = folderHandle("in\\d");
        assertEquals(AtomicOutcome.DONE, file("in/d").takeAway(file("in/d.x1")).getOutcome());
        verify(away).rename("in\\d.x1", false);
        verify(away).close();

        open();
        client.info(Smb2ClientWrapperTest.info(true));
        final Directory replaced = folderHandle("in\\d");
        assertEquals(AtomicOutcome.DONE, file("in/d").renameReplacing(file("in/e"), null, null).getOutcome());
        verify(replaced).rename("in\\e", true);
        // A folder never gets the open of a file.
        verify(client.share, never()).open(eq("in\\d"), eq(EnumSet.of(AccessMask.MAXIMUM_ALLOWED)), any(), any(),
                any(), any());

        open();
        client.info(Smb2ClientWrapperTest.info(true));
        doThrow(status(NtStatus.STATUS_SHARING_VIOLATION)).when(client.share).openDirectory(eq("in\\d"), any(),
                any(), any(), any(), any());
        final AtomicResult busy = file("in/d").takeAway(file("in/d.x1"));
        assertEquals(AtomicOutcome.BUSY, busy.getOutcome());
        assertEquals(NtStatus.STATUS_SHARING_VIOLATION.name(), busy.getDetail());
        assertTrue(clientKept());
    }

    // The looks.

    @Test
    public void lookIsFalseOnlyForTheThreeNotFoundStatusesAndADeletePendingIsThere() throws Exception {
        client.info(Smb2ClientWrapperTest.info(false));
        assertTrue(file("in/a.csv").existsNow());
        for (final NtStatus missing : new NtStatus[] {NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NO_SUCH_FILE}) {
            open();
            client.metadataOpenFails(status(missing));
            assertFalse(file("in/a.csv").existsNow(), missing.name());
            assertTrue(clientKept());
        }
        open();
        client.metadataOpenFails(status(NtStatus.STATUS_DELETE_PENDING));
        assertTrue(file("in/a.csv").existsNow());
        assertEquals(Fault.BUSY, assertThrows(FileSystemException.class, () -> file("in/a.csv").statNow())
                .getFault());
    }

    @Test
    public void lookBelowAFolderWithNoAccessThrowsAndATransportErrorDiscards() throws Exception {
        client.metadataOpenFails(status(NtStatus.STATUS_ACCESS_DENIED));
        final FileSystemException denied = assertThrows(FileSystemException.class,
                () -> file("noaccess/a.csv").existsNow());
        assertEquals(Fault.APPLICATION, denied.getFault());
        assertTrue(clientKept());

        open();
        client.metadataOpenFails(transport());
        final FileSystemException lost = assertThrows(FileSystemException.class, () -> file("in/a.csv").existsNow());
        assertEquals(Fault.TRANSPORT, lost.getFault());
        assertFalse(lost instanceof UnclearResultException);
        assertFalse(clientKept());
    }

    @Test
    public void folderLookAndStatReadTheOneQuery() throws Exception {
        client.info(Smb2ClientWrapperTest.info(true));
        assertTrue(file("in").folderExistsNow());
        open();
        client.info(Smb2ClientWrapperTest.info(false));
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, () -> file("in/a.csv")
                .folderExistsNow()).getFault());
        open();
        final FileAllInformation info = mock(FileAllInformation.class);
        final FileStandardInformation standard = mock(FileStandardInformation.class);
        when(standard.getEndOfFile()).thenReturn(42L);
        final FileBasicInformation basic = mock(FileBasicInformation.class);
        when(basic.getLastWriteTime()).thenReturn(FileTime.ofEpochMillis(1700000000000L));
        when(basic.getChangeTime()).thenReturn(FileTime.ofEpochMillis(1800000000000L));
        when(info.getStandardInformation()).thenReturn(standard);
        when(info.getBasicInformation()).thenReturn(basic);
        client.info(info);
        final StatNow found = file("in/a.csv").statNow();
        assertTrue(found.isThere());
        assertEquals(42, found.getSize());
        // The last write time, not the change time.
        assertEquals(1700000000000L, found.getLastModifiedMillis());
        assertFalse(found.isFromListing());
        open();
        client.metadataOpenFails(status(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND));
        assertFalse(file("in/a.csv").statNow().isThere());
        assertFalse(file("in").folderExistsNow());
    }

    @Test
    public void publicIdentityKeepsSmbCaseUncertaintyWithoutBorrowingAClient() throws Exception {
        Smb2FileObject source = file("in/Report");
        assertEquals(EntryRelation.SAME, AbstractFileObject.compareEntries(source, file("in/Report")));
        assertEquals(EntryRelation.UNKNOWN, AbstractFileObject.compareEntries(source, file("in/report")));
        assertEquals(EntryRelation.DISTINCT, AbstractFileObject.compareEntries(source, file("in/other")));
        verifyNoInteractions(client.share, spare.share);
    }

    @Test
    public void caseOnlyDirectRenameAndStagingGuardsRespectTheShare() throws Exception {
        Smb2FileObject source = spy(file("in/Name"));
        Smb2FileObject otherCase = file("in/name");
        Smb2FileObject temp = file("in/stage");
        MoveOptions options = new MoveOptions(true, temp, null, null, null);
        assertEquals("UNKNOWN", String.valueOf(source.entryRelation(otherCase)));
        assertEquals("DISTINCT", String.valueOf(source.entryRelation(temp)));
        String compact = Smb2Doubles.ROOT.replace("127.0.0.1", "127.1");
        Smb2FileNameParser parser = Smb2FileNameParser.getInstance();
        FileName aliasRoot = parser.parseUri(null, null, compact);
        Smb2FileSystem aliasView = new Smb2FileSystem(aliasRoot, new FileSystemOptions(), new Factory(spare.wrapper));
        AbstractFileName aliasName = (AbstractFileName) parser.parseUri(null, null, compact + "in/Name");
        assertEquals("SAME", String.valueOf(source.entryRelation(new Smb2FileObject(aliasName, aliasView, aliasRoot))));
        FileName otherRoot = Smb2FileNameParser.getInstance().parseUri(null, null,
                Smb2Doubles.ROOT.replace("/data/", "/archive/"));
        Smb2FileSystem otherShare = new Smb2FileSystem(otherRoot, new FileSystemOptions(),
                new Factory(spare.wrapper));
        assertEquals("DISTINCT", String.valueOf(source.entryRelation(new Smb2FileObject(Smb2Doubles.name("in/Name"),
                otherShare, otherRoot))));
        doReturn(null).when(source).getParent();
        doReturn(FileType.FILE).when(source).getType();
        doReturn(new AtomicResult(AtomicOutcome.DONE, null)).when(source).renameReplacing(eq(otherCase), any(), any());
        source.moveTo(otherCase, options);
        verify(source).renameReplacing(eq(otherCase), any(), any());
        for (AtomicOutcome outcome : new AtomicOutcome[] {AtomicOutcome.REFUSED, AtomicOutcome.UNSUPPORTED}) {
            doReturn(new AtomicResult(outcome, null)).when(source).renameReplacing(eq(otherCase), any(), any());
            MoveFailedException e = assertThrows(MoveFailedException.class, () -> source.moveTo(otherCase, options));
            assertFalse(e.isTargetExists());
            assertFalse(e.isCommitConfirmed());
        }
        verify(source, never()).getContent();
        verify(source, never()).takeAway(any());
    }
}
