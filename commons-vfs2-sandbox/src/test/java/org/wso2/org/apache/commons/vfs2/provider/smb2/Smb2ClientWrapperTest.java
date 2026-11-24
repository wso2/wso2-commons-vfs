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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import com.hierynomus.msdfsc.DFSException;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.Directory;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * The config of each client, the local check, the abort, the rename open and the faults of the SMB2 client.
 */
public class Smb2ClientWrapperTest {

    private static final String PATH = "in\\a.txt";

    private static final Smb2FileSystemConfigBuilder BUILDER = Smb2FileSystemConfigBuilder.getInstance();

    // The exception that smbj throws for a call on a share that was closed, with no cause and no status.
    private static SMBRuntimeException closedShare() {
        return new SMBRuntimeException("DiskShare has already been closed");
    }

    // The exception that DiskShare makes of a not found status that the DFS resolver set itself.
    private static SMBApiException resolverNotFound(final NtStatus status) {
        final DFSException resolver = new DFSException(status.getValue(), "DFS request failed for path \\\\host\\data");
        return new SMBApiException(resolver.getStatusCode(), SMB2MessageCommandCode.SMB2_CREATE,
                "Cannot resolve path " + PATH, resolver);
    }

    private static SMBApiException serverStatus(final NtStatus status) {
        return new SMBApiException(status.getValue(), SMB2MessageCommandCode.SMB2_CREATE, "status of the server", null);
    }

    @Test
    public void noStaticConfigIsLeftInTheClient() {
        for (final Field field : Smb2ClientWrapper.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertFalse(SmbConfig.class.isAssignableFrom(field.getType()), field.getName());
            }
        }
    }

    @Test
    public void twoConfigsBuiltAtTheSameMomentEachHaveTheirOwnOptions() throws Exception {
        final FileSystemOptions encrypted = new FileSystemOptions();
        BUILDER.setEncryptionEnabled(encrypted, true);
        BUILDER.setReadTimeout(encrypted, Duration.ofMillis(10000));
        final FileSystemOptions plain = new FileSystemOptions();
        BUILDER.setEncryptionEnabled(plain, false);
        BUILDER.setReadTimeout(plain, Duration.ofMillis(60000));
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final List<String> wrong = new ArrayList<>();
        for (int round = 0; round < 200; round++) {
            final AtomicReference<SmbConfig> first = new AtomicReference<>();
            final AtomicReference<SmbConfig> second = new AtomicReference<>();
            final Thread a = new Thread(() -> first.set(build(barrier, encrypted)));
            final Thread b = new Thread(() -> second.set(build(barrier, plain)));
            a.start();
            b.start();
            a.join(10000);
            b.join(10000);
            if (!first.get().isEncryptData() || first.get().getReadTimeout() != 10000) {
                wrong.add("round " + round + " encrypted " + first.get().isEncryptData() + " "
                        + first.get().getReadTimeout());
            }
            if (second.get().isEncryptData() || second.get().getReadTimeout() != 60000) {
                wrong.add("round " + round + " plain " + second.get().isEncryptData() + " "
                        + second.get().getReadTimeout());
            }
        }
        assertEquals(new ArrayList<>(), wrong);
    }

    private static SmbConfig build(final CyclicBarrier barrier, final FileSystemOptions options) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            return Smb2ClientWrapper.buildConfig(options, new SocketGroup(), () -> { });
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void configWithNothingSetHasTheDefaultsOfSmbjAndNoSocketTimeout() throws Exception {
        final SocketGroup group = new SocketGroup();
        final SmbConfig config = Smb2ClientWrapper.buildConfig(new FileSystemOptions(), group, () -> { });
        assertEquals(60000, config.getReadTimeout());
        assertEquals(60000, config.getWriteTimeout());
        assertEquals(60000, config.getTransactTimeout());
        assertEquals(0, config.getSoTimeout());
        assertTrue(config.isDfsEnabled());
        assertTrue(config.isUseMultiProtocolNegotiate());
        assertFalse(config.isEncryptData());
        // Every socket of the client comes from its own factory and is in its own group.
        final Socket socket = config.getSocketFactory().createSocket();
        try {
            assertEquals(Arrays.asList(socket), group.getSockets());
        } finally {
            socket.close();
        }
        assertTrue(group.getSockets().isEmpty());
    }

    @Test
    public void configTakesTheThreeRequestTimeoutsOfItsOptions() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setReadTimeout(options, Duration.ofMillis(20000));
        BUILDER.setWriteTimeout(options, Duration.ofMillis(25000));
        BUILDER.setTransactTimeout(options, Duration.ofMillis(30000));
        final SmbConfig config = Smb2ClientWrapper.buildConfig(options, new SocketGroup(), () -> { });
        assertEquals(20000, config.getReadTimeout());
        assertEquals(25000, config.getWriteTimeout());
        assertEquals(30000, config.getTransactTimeout());
        assertEquals(0, config.getSoTimeout());
    }

    @Test
    public void builderReadsNoSystemProperty() {
        System.setProperty("vfs.readTimeout", "PT1S");
        System.setProperty("vfs.writeStallTimeout", "PT1S");
        try {
            assertEquals(Duration.ofSeconds(60), BUILDER.getReadTimeout(new FileSystemOptions()));
            assertEquals(Duration.ofSeconds(60), BUILDER.getWriteStallTimeout(new FileSystemOptions()));
        } finally {
            System.clearProperty("vfs.readTimeout");
            System.clearProperty("vfs.writeStallTimeout");
        }
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setWriteStallTimeout(options, Duration.ZERO);
        assertEquals(Duration.ZERO, BUILDER.getWriteStallTimeout(options));
    }

    @Test
    public void connectOfTheFactoryPassesFiveSecondsToTheSocket() throws Exception {
        final SmbConfig config = Smb2ClientWrapper.buildConfig(new FileSystemOptions(), new SocketGroup(), () -> { });
        final AtomicInteger timeout = new AtomicInteger(-1);
        // The socket notes the timeout that the factory gives its connect, and refuses at once, as a route that is
        // not there does. The wait itself is measured on a test bed, with a peer that drops the connect.
        final Socket socket = new Socket() {
            @Override
            public void connect(final SocketAddress endpoint, final int millis) throws IOException {
                timeout.set(millis);
                throw new ConnectException("refused");
            }
        };
        final SocketFactory factory = spy(config.getSocketFactory());
        doReturn(socket).when(factory).createSocket();
        assertThrows(ConnectException.class, () -> factory.createSocket("192.0.2.1", 445));
        assertEquals(5000, timeout.get());
        assertTrue(socket.isClosed());
    }

    @Test
    public void localCheckReadsTheSocketTheGroupTheShareAndTheBrokenFlag() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        assertTrue(client.wrapper.isAliveLocally());
        when(client.share.isConnected()).thenReturn(false);
        assertFalse(client.wrapper.isAliveLocally());
        when(client.share.isConnected()).thenReturn(true);
        client.wrapper.markBroken();
        assertFalse(client.wrapper.isAliveLocally());

        final Smb2Doubles.Client closed = new Smb2Doubles.Client();
        closed.socket.close();
        assertFalse(closed.wrapper.isAliveLocally());

        final Smb2Doubles.Client aborted = new Smb2Doubles.Client();
        aborted.wrapper.abort();
        assertFalse(aborted.wrapper.isAliveLocally());

        final Smb2Doubles.Client lost = new Smb2Doubles.Client();
        lost.wrapper.connectionLost();
        assertFalse(lost.wrapper.isAliveLocally());
        assertTrue(lost.socket.isClosed());
    }

    @Test
    public void abortClosesEverySocketOfTheGroupWithNoRequest() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final Socket socket = new Socket();
        client.group.add(socket);
        client.wrapper.abort();
        assertTrue(socket.isClosed());
        assertTrue(client.group.isAborted());
        verify(client.share, never()).close();
        verify(client.connection, never()).close();
    }

    @Test
    public void closedShareWithNoCauseIsATransportErrorAndKeepsItsShape() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final SMBRuntimeException closed = closedShare();
        // While the share is open and the client is not broken, the exception says nothing about the connection.
        assertEquals(Fault.APPLICATION, client.wrapper.faultOf(closed));
        when(client.share.isConnected()).thenReturn(false);
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(closed));
        when(client.share.isConnected()).thenReturn(true);
        client.wrapper.markBroken();
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(closed));
        // No cause is invented.
        assertNull(closed.getCause());
    }

    @Test
    public void notFoundOfTheDfsResolverIsATransportErrorAndAReplyOfTheServerIsMissing() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final SMBApiException pathOfResolver = resolverNotFound(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND);
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(pathOfResolver));
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(resolverNotFound(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)));
        // The cause is kept as smbj made it.
        assertTrue(pathOfResolver.getCause() instanceof DFSException);
        assertEquals(Fault.MISSING, client.wrapper.faultOf(serverStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)));
        assertEquals(Fault.MISSING, client.wrapper.faultOf(serverStatus(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND)));
    }

    @Test
    public void otherShapesFollowTheTableOfFaults() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final DFSException brokenReferral = new DFSException(new TransportException("connection reset"));
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(new SMBApiException(NtStatus.STATUS_OTHER.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "Cannot resolve path " + PATH, brokenReferral)));
        assertEquals(Fault.TRANSPORT,
                client.wrapper.faultOf(new SMBRuntimeException(new TransportException("transport is disconnected"))));
        assertEquals(Fault.APPLICATION, client.wrapper.faultOf(serverStatus(NtStatus.STATUS_ACCESS_DENIED)));
        assertEquals(Fault.BUSY, client.wrapper.faultOf(serverStatus(NtStatus.STATUS_SHARING_VIOLATION)));
        assertEquals(Fault.TRANSPORT, client.wrapper.faultOf(serverStatus(NtStatus.STATUS_NETWORK_NAME_DELETED)));
        final FileSystemException decided = new FileSystemException("decided",
                serverStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND));
        decided.setFault(Fault.BUSY);
        assertEquals(Fault.BUSY, client.wrapper.faultOf(decided));
    }

    @Test
    public void fileInfoIsNullOnlyForAFileThatTheServerSaysIsMissing() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        client.metadataOpenFails(serverStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND));
        assertNull(client.wrapper.getFileInfo(PATH));

        expectFault(client, resolverNotFound(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND), Fault.TRANSPORT);
        expectFault(client, serverStatus(NtStatus.STATUS_ACCESS_DENIED), Fault.APPLICATION);
        expectFault(client, serverStatus(NtStatus.STATUS_DELETE_PENDING), Fault.BUSY);
        expectFault(client, new SMBRuntimeException(new TransportException("timeout")), Fault.TRANSPORT);
        when(client.share.isConnected()).thenReturn(false);
        expectFault(client, closedShare(), Fault.TRANSPORT);
    }

    private static void expectFault(final Smb2Doubles.Client client, final RuntimeException error, final Fault fault) {
        client.metadataOpenFails(error);
        final FileSystemException thrown = assertThrows(FileSystemException.class,
                () -> client.wrapper.getFileInfo(PATH));
        assertEquals(fault, thrown.getFault());
        assertSame(error, thrown.getCause());
    }

    @Test
    public void deleteKeepsTheCauseAndSetsTheFaultOfTheLookAndOfTheRemove() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final SMBApiException missing = serverStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND);
        client.metadataOpenFails(missing);
        FileSystemException thrown = assertThrows(FileSystemException.class, () -> client.wrapper.delete(PATH));
        assertSame(missing, thrown.getCause());
        assertEquals(Fault.MISSING, thrown.getFault());

        final Smb2Doubles.Client present = new Smb2Doubles.Client();
        final FileAllInformation file = info(false);
        present.info(file);
        final SMBApiException denied = serverStatus(NtStatus.STATUS_ACCESS_DENIED);
        doThrow(denied).when(present.share).rm(PATH);
        thrown = assertThrows(FileSystemException.class, () -> present.wrapper.delete(PATH));
        assertSame(denied, thrown.getCause());
        assertEquals(Fault.APPLICATION, thrown.getFault());

        final SMBRuntimeException closed = closedShare();
        doThrow(closed).when(present.share).rm(PATH);
        when(present.share.isConnected()).thenReturn(false);
        thrown = assertThrows(FileSystemException.class, () -> present.wrapper.delete(PATH));
        assertSame(closed, thrown.getCause());
        assertEquals(Fault.TRANSPORT, thrown.getFault());

        final Smb2Doubles.Client folder = new Smb2Doubles.Client();
        folder.info(info(true));
        folder.wrapper.delete(PATH);
        verify(folder.share).rmdir(PATH, true);
        verify(folder.share, never()).rm(anyString());
    }

    static FileAllInformation info(final boolean folder) {
        final FileAllInformation info = mock(FileAllInformation.class);
        final FileStandardInformation standard = mock(FileStandardInformation.class);
        when(standard.isDirectory()).thenReturn(folder);
        when(info.getStandardInformation()).thenReturn(standard);
        return info;
    }

    @Test
    public void renameOpensAFileWithNoCreateAndNoSharedDelete() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        client.wrapper.openForRename(PATH, false);
        verify(client.share).open(PATH, EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));

        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setDiskShareAccessMask(options, new ArrayList<>(Arrays.asList("GENERIC_READ", "DELETE")));
        final Smb2Doubles.Client masked = new Smb2Doubles.Client(options, null);
        masked.wrapper.openForRename(PATH, false);
        verify(masked.share).open(PATH, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    // Windows Server 2025 refuses a folder create that asks for GENERIC_ALL. The open takes the configured mask, and
    // GENERIC_ALL only when no mask is configured.
    @Test
    public void folderCreateOpensWithTheConfiguredMaskAndGenericAllOnlyByDefault() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final Directory created = mock(Directory.class);
        doReturn(created).when(client.share).openDirectory(anyString(), any(), any(), any(), any(), any());
        client.wrapper.createFolder(PATH);
        verify(client.share).openDirectory(PATH, EnumSet.of(AccessMask.GENERIC_ALL),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN_IF, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
        verify(created).close();

        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setDiskShareAccessMask(options, new ArrayList<>(Arrays.asList("MAXIMUM_ALLOWED")));
        final Smb2Doubles.Client masked = new Smb2Doubles.Client(options, null);
        final Directory maskedFolder = mock(Directory.class);
        doReturn(maskedFolder).when(masked.share).openDirectory(anyString(), any(), any(), any(), any(), any());
        masked.wrapper.createFolder(PATH);
        verify(masked.share).openDirectory(PATH, EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN_IF, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
        verify(masked.share, never()).openDirectory(anyString(), eq(EnumSet.of(AccessMask.GENERIC_ALL)), any(), any(),
                any(), any());
        verify(maskedFolder).close();
    }

    @Test
    public void renameOpensAFolderWithTheMaskAndTheShareOfTheFolderWrite() throws Exception {
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        client.wrapper.openForRename(PATH, true);
        verify(client.share).openDirectory(PATH, EnumSet.of(AccessMask.GENERIC_ALL),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
    }
}
