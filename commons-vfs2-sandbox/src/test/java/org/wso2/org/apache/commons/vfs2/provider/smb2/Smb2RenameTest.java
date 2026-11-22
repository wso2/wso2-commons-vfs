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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskEntry;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;

/**
 * The rename opens its source with no create and closes the handle in a finally block, on the file path and on the
 * folder path.
 */
public class Smb2RenameTest {

    private final List<String> order = new ArrayList<>();

    private Client client;

    private Smb2FileSystem fileSystem;

    private Smb2FileObject source;

    private Smb2FileObject target;

    private DiskEntry handle;

    private void open(final boolean folder) throws Exception {
        client = new Client(null, order);
        fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, new Client().wrapper));
        source = new Smb2FileObject(Smb2Doubles.name("in/g5"), fileSystem, Smb2Doubles.rootName());
        target = new Smb2FileObject(Smb2Doubles.name("out/g5"), fileSystem, Smb2Doubles.rootName());
        client.info(Smb2ClientWrapperTest.info(folder));
        handle = folder ? mock(Directory.class) : mock(DiskEntry.class);
        doAnswer(call -> {
            order.add("handle-close");
            return null;
        }).when(handle).close();
        if (folder) {
            when(client.share.openDirectory(eq("in\\g5"), any(), any(), any(), eq(SMB2CreateDisposition.FILE_OPEN),
                    any())).thenReturn((Directory) handle);
        } else {
            when(client.share.open(eq("in\\g5"), Smb2Doubles.notMetadata(), any(), any(),
                    eq(SMB2CreateDisposition.FILE_OPEN), any())).thenReturn(handle);
        }
    }

    @Test
    public void fileRenameOpensWithNoCreateAndClosesTheHandle() throws Exception {
        open(false);
        source.doRename(target);
        verify(handle).rename("out\\g5");
        assertEquals(1, order.stream().filter("handle-close"::equals).count());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void folderRenameOpensWithNoCreateAndClosesTheHandle() throws Exception {
        open(true);
        source.doRename(target);
        verify(handle).rename("out\\g5");
        assertEquals(1, order.stream().filter("handle-close"::equals).count());
        verify(client.share, never()).open(anyString(), Smb2Doubles.notMetadata(), any(), any(), any(), any());
    }

    @Test
    public void renameThatTheServerRefusesStillClosesTheHandleAndKeepsTheClient() throws Exception {
        for (final boolean folder : new boolean[] {false, true}) {
            order.clear();
            open(folder);
            final SMBApiException denied = new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(),
                    SMB2MessageCommandCode.SMB2_SET_INFO, "denied", null);
            doThrow(denied).when(handle).rename(anyString());
            final FileSystemException thrown = assertThrows(FileSystemException.class, () -> source.doRename(target));
            assertSame(denied, thrown.getCause());
            assertEquals(Fault.APPLICATION, thrown.getFault());
            assertEquals(1, order.stream().filter("handle-close"::equals).count());
            assertFalse(client.group.isAborted());
            assertSame(client.wrapper, fileSystem.getClient());
        }
    }

    @Test
    public void failedCloseAfterARenameThatReturnedIsDoneAndDiscardsTheClient() throws Exception {
        open(false);
        doThrow(new SMBRuntimeException(new TransportException("close timeout"))).when(handle).close();
        source.doRename(target);
        verify(handle).rename("out\\g5");
        assertTrue(client.group.isAborted());
        assertFalse(client.wrapper.isAliveLocally());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void failedCloseAfterARenameThatReturnedDiscardsAlsoWithAnErrorOfTheServer() throws Exception {
        open(false);
        doThrow(new SMBApiException(NtStatus.STATUS_FILE_CLOSED.getValue(), SMB2MessageCommandCode.SMB2_CLOSE,
                "closed", null)).when(handle).close();
        source.doRename(target);
        assertTrue(client.group.isAborted());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void refusedRenameWithACloseThatMeetsADeadConnectionDiscardsTheClient() throws Exception {
        open(false);
        doThrow(new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(), SMB2MessageCommandCode.SMB2_SET_INFO,
                "denied", null)).when(handle).rename(anyString());
        final SMBRuntimeException closeError = new SMBRuntimeException(new TransportException("close timeout"));
        doThrow(closeError).when(handle).close();
        final FileSystemException thrown = assertThrows(FileSystemException.class, () -> source.doRename(target));
        assertEquals(Fault.APPLICATION, thrown.getFault());
        // The close error stays with the refusal, where smbj itself puts it.
        assertSame(closeError, thrown.getCause().getSuppressed()[0]);
        assertTrue(client.group.isAborted());
    }

    @Test
    public void missingSourceFailsTheOpenAndCreatesNothing() throws Exception {
        open(false);
        final SMBApiException missing = new SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "missing", null);
        doThrow(missing).when(client.share).open(anyString(), Smb2Doubles.notMetadata(), any(), any(), any(), any());
        final FileSystemException thrown = assertThrows(FileSystemException.class, () -> source.doRename(target));
        assertSame(missing, thrown.getCause());
        assertEquals(Fault.MISSING, thrown.getFault());
        verify(client.share).open(eq("in\\g5"), Smb2Doubles.notMetadata(), any(), any(),
                eq(SMB2CreateDisposition.FILE_OPEN), any());
        verify(handle, never()).rename(anyString());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void renameTimeoutAbortsBeforeTheHandleCloseAndIsNotSentAgain() throws Exception {
        open(false);
        final List<String> closes = new ArrayList<>();
        doAnswer(call -> {
            closes.add("handle-close aborted=" + client.group.isAborted());
            return null;
        }).when(handle).close();
        doThrow(new SMBRuntimeException(new TransportException("rename timeout"))).when(handle).rename(anyString());
        final FileSystemException thrown = assertThrows(FileSystemException.class, () -> source.doRename(target));
        assertEquals(Fault.TRANSPORT, thrown.getFault());
        // The result of the rename is not known, and the rename is not sent again to learn it.
        verify(handle, times(1)).rename("out\\g5");
        assertEquals(Arrays.asList("handle-close aborted=true"), closes);
        assertEquals(0, fileSystem.getOpenClientCount());
        assertNotSame(client.wrapper, fileSystem.getClient());
    }
}
