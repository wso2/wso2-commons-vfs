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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.net.SocketFactory;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.PacketHandlers;
import com.hierynomus.protocol.transport.PacketReceiver;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smb.SMBPacket;
import com.hierynomus.smb.SMBPacketData;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransport;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * The local check while a writer holds the transport lock, the reader watch after a fatal error of the connection, the
 * listing that owns its handle, and the boundary between the return of a client and the close of the file system.
 */
public class Smb2RecoveryTest {

    private static final String FOLDER = "in";

    // The local check while a writer is blocked.

    /**
     * A file system with its own manager and provider, so the check of the manager reaches it.
     */
    private static final class Provider extends Smb2FileProvider {

        private final Smb2FileSystem fileSystem;

        Provider(final Smb2FileSystem fileSystem) {
            this.fileSystem = fileSystem;
        }

        @Override
        protected FileSystem doCreateFileSystem(final FileName name, final FileSystemOptions options) {
            return fileSystem;
        }
    }

    @Test
    public void localChecksReturnWhileAWriterHoldsTheTransportLockWithNoBound() throws Exception {
        final Client idle = new Client();
        final Client out = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(idle.wrapper, out.wrapper));
        final DefaultFileSystemManager manager = new DefaultFileSystemManager();
        manager.addProvider("smb2", new Provider(fileSystem));
        manager.init();
        manager.resolveFile(Smb2Doubles.ROOT + "in/a.txt");
        assertSame(idle.wrapper, fileSystem.getClient());
        assertSame(out.wrapper, fileSystem.getClient());
        fileSystem.putClient(idle.wrapper);

        // The connection of smbj answers only under the read lock of its real transport. A writer that is blocked in
        // a socket write holds the write lock with no bound when the watchdog is off.
        final DirectTcpTransport<SMBPacketData<?>, SMBPacket<?, ?>> transport = new DirectTcpTransport<>(
                SocketFactory.getDefault(), 0, new PacketHandlers<SMBPacketData<?>, SMBPacket<?, ?>>(null, null, null));
        final Field lockField = DirectTcpTransport.class.getDeclaredField("lock");
        lockField.setAccessible(true);
        final ReentrantReadWriteLock lock = (ReentrantReadWriteLock) lockField.get(transport);
        when(out.connection.isConnected()).thenAnswer(call -> transport.isConnected());
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread writer = new Thread(() -> {
            lock.writeLock().lock();
            try {
                held.countDown();
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.writeLock().unlock();
            }
        }, "blocked-writer");
        writer.start();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            // Control: the check of the connection itself waits behind the writer.
            final Thread control = new Thread(out.connection::isConnected, "connection-check");
            control.setDaemon(true);
            control.start();
            control.join(500);
            assertTrue(control.isAlive(), "Connection.isConnected() returned while the write lock was held");

            final AtomicReference<String> answers = new AtomicReference<>();
            final Thread checker = new Thread(() -> answers.set(fileSystem.isAliveLocally() + " "
                    + manager.isAliveLocally() + " " + out.wrapper.isAliveLocally()), "local-check");
            checker.start();
            checker.join(1000);
            assertFalse(checker.isAlive(), "the local check waited behind the writer");
            assertTrue(writer.isAlive() && lock.isWriteLocked());
            assertEquals("true true true", answers.get());

            // Control: an abort while the writer still holds the lock makes the checks answer false at once.
            fileSystem.abort();
            final Thread afterAbort = new Thread(() -> answers.set(fileSystem.isAliveLocally() + " "
                    + manager.isAliveLocally()), "local-check-after-abort");
            afterAbort.start();
            afterAbort.join(1000);
            assertFalse(afterAbort.isAlive());
            assertEquals("false false", answers.get());
        } finally {
            release.countDown();
            writer.join(10000);
        }
    }

    @Test
    public void localCheckOfTheFileSystemSeesEveryClientWhileClientsMoveBetweenTheSlotAndABorrower()
            throws Exception {
        final Client first = Client.stubOnly();
        final Client second = Client.stubOnly();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper, second.wrapper));
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> moverError = new AtomicReference<>();
        final Thread mover = new Thread(() -> {
            try {
                while (!stop.get()) {
                    fileSystem.putClient(fileSystem.getClient());
                }
            } catch (final Throwable e) {
                moverError.set(e);
            }
        }, "mover");
        mover.start();
        try {
            for (int i = 0; i < 20000; i++) {
                assertTrue(fileSystem.isAliveLocally());
            }
        } finally {
            stop.set(true);
            mover.join(10000);
        }
        assertFalse(mover.isAlive());
        assertNull(moverError.get());
        // A dead client is seen in both places. The mover is stopped first, because a getClient would replace a
        // dead idle client at once.
        first.socket.close();
        assertFalse(fileSystem.isAliveLocally(), "a dead idle client");
        assertSame(second.wrapper, fileSystem.getClient());
        assertTrue(fileSystem.isAliveLocally());
        second.socket.close();
        assertFalse(fileSystem.isAliveLocally(), "a dead client that is out");
    }

    // The reader watch.

    @Test
    public void fatalErrorOfTheReaderMarksTheClientAndClosesItsSocketsBeforeSmbjSeesTheError() throws Exception {
        final Client client = new Client();
        final List<String> order = new ArrayList<>();
        final AtomicReference<Throwable> seen = new AtomicReference<>();
        final PacketReceiver<SMBPacketData<?>> smbj = new PacketReceiver<SMBPacketData<?>>() {
            @Override
            public void handle(final SMBPacketData<?> packet) {
                order.add("packet aborted=" + client.group.isAborted());
            }

            @Override
            public void handleError(final Throwable error) {
                order.add("error aborted=" + client.group.isAborted() + " socket closed=" + client.socket.isClosed()
                        + " alive=" + client.wrapper.isAliveLocally());
                seen.set(error);
            }
        };
        final PacketReceiver<SMBPacketData<?>> watched = Smb2ClientWrapper.ReaderWatch.watch(smbj,
                client.wrapper::connectionLost);
        watched.handle(mock(SMBPacketData.class));
        assertTrue(client.wrapper.isAliveLocally());
        final TransportException end = new TransportException("EOF while reading packet");
        watched.handleError(end);
        assertEquals(Arrays.asList("packet aborted=false", "error aborted=true socket closed=true alive=false"), order);
        assertSame(end, seen.get());
    }

    @Test
    public void everyConfigCarriesTheReaderWatch() throws Exception {
        final AtomicBoolean lost = new AtomicBoolean();
        final SmbConfig config = Smb2ClientWrapper.buildConfig(new FileSystemOptions(), new SocketGroup(),
                () -> lost.set(true));
        assertTrue(config.getTransportLayerFactory() instanceof Smb2ClientWrapper.ReaderWatch);
        assertTrue(config.getTransportLayerFactory().createTransportLayer(
                new PacketHandlers<SMBPacketData<?>, SMBPacket<?, ?>>(null, null, null), config)
                instanceof DirectTcpTransport);
        assertFalse(lost.get());
    }

    // The closed IPC$ share of the DFS resolver, as smbj reports it after its reader closed the session.
    private static SMBRuntimeException closedPipeShare() {
        return new SMBRuntimeException("PipeShare has already been closed");
    }

    @Test
    public void lossBeforeTheHandoutGivesAFreshClient() throws Exception {
        final Client first = new Client();
        final Client fresh = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper, fresh.wrapper));
        first.wrapper.connectionLost();
        assertSame(fresh.wrapper, fileSystem.getClient());
        assertEquals(1, first.closes.get());
        assertEquals(1, fileSystem.getOpenClientCount());
    }

    @Test
    public void lossAfterTheHandoutDiscardsTheClientAtItsFirstError() throws Exception {
        final Client first = new Client();
        final Client fresh = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper, fresh.wrapper));
        assertSame(first.wrapper, fileSystem.getClient());
        first.wrapper.connectionLost();
        fileSystem.giveBack(first.wrapper, closedPipeShare());
        assertEquals(1, first.closes.get());
        assertEquals(0, fileSystem.getOpenClientCount());
        assertSame(fresh.wrapper, fileSystem.getClient());
    }

    @Test
    public void aClosedPipeShareWithNoLossOfThisClientIsNoTransportError() throws Exception {
        final Client first = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper));
        assertSame(first.wrapper, fileSystem.getClient());
        fileSystem.giveBack(first.wrapper, closedPipeShare());
        assertFalse(first.group.isAborted());
        assertSame(first.wrapper, fileSystem.getClient());
    }

    // The listing.

    private static Directory directory(final Client client, final List<String> order) {
        return directory(client, order, null);
    }

    // The folder handle of the listing. Its close counts and can throw.
    private static Directory directory(final Client client, final List<String> order,
            final RuntimeException closeError) {
        final Directory directory = mock(Directory.class);
        doReturn(directory).when(client.share).openDirectory(eq(FOLDER),
                eq(EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES,
                        AccessMask.FILE_READ_EA)),
                isNull(), eq(SMB2ShareAccess.ALL), eq(SMB2CreateDisposition.FILE_OPEN), isNull());
        doAnswer(call -> {
            order.add("handle-close aborted=" + client.group.isAborted());
            if (closeError != null) {
                throw closeError;
            }
            return null;
        }).when(directory).close();
        return directory;
    }

    private static FileIdBothDirectoryInformation entry(final String name) {
        final FileIdBothDirectoryInformation entry = mock(FileIdBothDirectoryInformation.class);
        when(entry.getFileName()).thenReturn(name);
        return entry;
    }

    @Test
    public void cleanListingOpensListsAndClosesTheHandleOnce() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final Directory directory = directory(client, order);
        final List<FileIdBothDirectoryInformation> entries = Arrays.asList(entry("."), entry(".."), entry("a.txt"),
                entry("b"));
        doReturn(entries).when(directory).list(FileIdBothDirectoryInformation.class, null);
        assertArrayEquals(new String[] {"a.txt", "b"}, client.wrapper.getChildren(FOLDER));
        assertEquals(Arrays.asList("handle-close aborted=false"), order);
    }

    @Test
    public void refusedListingClosesTheHandleNormallyAndKeepsTheClient() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final Directory directory = directory(client, order);
        final SMBApiException denied = new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(),
                SMB2MessageCommandCode.SMB2_QUERY_DIRECTORY, "denied", null);
        doThrow(denied).when(directory).list(FileIdBothDirectoryInformation.class, null);
        assertSame(denied, assertThrows(SMBApiException.class, () -> client.wrapper.getChildren(FOLDER)));
        assertEquals(Arrays.asList("handle-close aborted=false"), order);
    }

    @Test
    public void failedOpenHasNoHandleToClose() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final SMBRuntimeException dead = new SMBRuntimeException(new TransportException("connection reset"));
        doThrow(dead).when(client.share).openDirectory(anyString(), any(), any(), any(), any(), any());
        assertSame(dead, assertThrows(SMBRuntimeException.class, () -> client.wrapper.getChildren(FOLDER)));
        assertTrue(order.isEmpty());
        assertFalse(client.group.isAborted());
    }

    @Test
    public void transportFailureOfTheQueryAbortsBeforeTheHandleIsClosed() throws Exception {
        for (final SMBRuntimeException failure : new SMBRuntimeException[] {
                new SMBRuntimeException(new TransportException("read timeout")),
                new SMBRuntimeException(new InterruptedException())}) {
            final List<String> order = new ArrayList<>();
            final Client client = new Client(null, order);
            final Directory directory = directory(client, order);
            doThrow(failure).when(directory).list(FileIdBothDirectoryInformation.class, null);
            assertSame(failure, assertThrows(SMBRuntimeException.class, () -> client.wrapper.getChildren(FOLDER)));
            assertEquals(Arrays.asList("handle-close aborted=true"), order);
            verify(client.share, never()).list(anyString());
        }
    }

    private static SMBRuntimeException timeout() {
        return new SMBRuntimeException(new TransportException("request timeout"));
    }

    private static SMBApiException denied() {
        return new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(), SMB2MessageCommandCode.SMB2_QUERY_INFO,
                "denied", null);
    }

    // A listing that works or that the server refuses, and then a close of the folder handle that times out before
    // the reader of smbj saw any loss. The outcome of the listing stays, and the client is retired.
    private void listingWithACloseTimeout(final boolean refused) throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final Client other = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, other.wrapper));
        assertSame(client.wrapper, fileSystem.getClient());
        final Directory directory = directory(client, order, timeout());
        Throwable failure = null;
        if (refused) {
            final SMBApiException denied = denied();
            doThrow(denied).when(directory).list(FileIdBothDirectoryInformation.class, null);
            failure = assertThrows(SMBApiException.class, () -> client.wrapper.getChildren(FOLDER));
            assertSame(denied, failure);
        } else {
            doReturn(Arrays.asList(entry("a.txt"))).when(directory).list(FileIdBothDirectoryInformation.class, null);
            assertArrayEquals(new String[] {"a.txt"}, client.wrapper.getChildren(FOLDER));
        }
        assertEquals(Arrays.asList("handle-close aborted=false"), order);
        assertTrue(client.group.isAborted());
        fileSystem.giveBack(client.wrapper, failure);
        assertSame(other.wrapper, fileSystem.getClient());
    }

    @Test
    public void listingThatWorksWithACloseTimeoutKeepsTheListingAndRetiresTheClient() throws Exception {
        listingWithACloseTimeout(false);
    }

    @Test
    public void refusedListingWithACloseTimeoutKeepsTheRefusalAndRetiresTheClient() throws Exception {
        listingWithACloseTimeout(true);
    }

    // The metadata query.

    // The handle of the metadata query notes its close and can throw from it.
    private static void metadataClose(final Client client, final List<String> order,
            final RuntimeException closeError) {
        doAnswer(call -> {
            order.add("metadata-close aborted=" + client.group.isAborted());
            if (closeError != null) {
                throw closeError;
            }
            return null;
        }).when(client.metadata).close();
    }

    @Test
    public void metadataTimeoutAbortsBeforeTheHandleCloseAndRetiresTheClient() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final SMBRuntimeException timeout = timeout();
        doThrow(timeout).when(client.metadata).getFileInformation(FileAllInformation.class);
        metadataClose(client, order, null);
        final FileSystemException thrown = assertThrows(FileSystemException.class,
                () -> client.wrapper.getFileInfo("in\\a.txt"));
        assertEquals(Fault.TRANSPORT, thrown.getFault());
        assertSame(timeout, thrown.getCause());
        assertEquals(Arrays.asList("metadata-close aborted=true"), order);
    }

    @Test
    public void refusedMetadataWithACloseTimeoutKeepsTheRefusalAndRetiresTheClient() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final Client other = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, other.wrapper));
        assertSame(client.wrapper, fileSystem.getClient());
        final SMBApiException denied = denied();
        final SMBRuntimeException closeError = timeout();
        doThrow(denied).when(client.metadata).getFileInformation(FileAllInformation.class);
        metadataClose(client, order, closeError);
        final FileSystemException thrown = assertThrows(FileSystemException.class,
                () -> client.wrapper.getFileInfo("in\\a.txt"));
        assertEquals(Fault.APPLICATION, thrown.getFault());
        assertSame(denied, thrown.getCause());
        assertSame(closeError, denied.getSuppressed()[0]);
        assertEquals(Arrays.asList("metadata-close aborted=false"), order);
        fileSystem.giveBack(client.wrapper, thrown);
        assertSame(other.wrapper, fileSystem.getClient());
    }

    @Test
    public void refusedMetadataWithAGoodCloseKeepsTheClient() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, new Client().wrapper));
        assertSame(client.wrapper, fileSystem.getClient());
        doThrow(denied()).when(client.metadata).getFileInformation(FileAllInformation.class);
        metadataClose(client, order, null);
        final FileSystemException thrown = assertThrows(FileSystemException.class,
                () -> client.wrapper.getFileInfo("in\\a.txt"));
        assertEquals(Fault.APPLICATION, thrown.getFault());
        fileSystem.giveBack(client.wrapper, thrown);
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void metadataThatWorksWithACloseTimeoutKeepsTheAnswerAndRetiresTheClient() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        final FileAllInformation info = Smb2ClientWrapperTest.info(false);
        client.info(info);
        metadataClose(client, order, timeout());
        assertSame(info, client.wrapper.getFileInfo("in\\a.txt"));
        assertTrue(client.group.isAborted());
    }

    @Test
    public void missingFileHasNoMetadataHandleToClose() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        client.metadataOpenFails(new SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "missing", null));
        metadataClose(client, order, null);
        assertNull(client.wrapper.getFileInfo("in\\a.txt"));
        assertTrue(order.isEmpty());
        assertFalse(client.group.isAborted());
    }

    @Test
    public void lookupOfTheDeleteOwnsItsHandleTheSameWay() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client client = new Client(null, order);
        doThrow(timeout()).when(client.metadata).getFileInformation(FileAllInformation.class);
        metadataClose(client, order, null);
        assertEquals(Fault.TRANSPORT,
                assertThrows(FileSystemException.class, () -> client.wrapper.delete("in\\a.txt")).getFault());
        assertEquals(Arrays.asList("metadata-close aborted=true"), order);
        verify(client.share, never()).rm(anyString());
    }

    // The return of a client and the close of the file system.

    @Test
    public void returnsThatRaceTheCloseOfTheFileSystemLeaveNoIdleClientAndTouchNoReplacement() throws Exception {
        // The exact order, a check that sees the file system open and a close before the put, is forced with a
        // debugger in the evidence of the task. This test runs the two calls against each other many times.
        final Client other = Client.stubOnly();
        final Smb2FileSystem replacement = Smb2Doubles.fileSystem(new Factory(other.wrapper));
        for (int round = 0; round < 2000; round++) {
            final Client first = Client.stubOnly();
            final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper));
            assertSame(first.wrapper, fileSystem.getClient());
            final CountDownLatch start = new CountDownLatch(1);
            final Thread returner = new Thread(() -> {
                await(start);
                fileSystem.putClient(first.wrapper);
            }, "returner");
            final Thread closer = new Thread(() -> {
                await(start);
                fileSystem.closeCommunicationLink();
            }, "closer");
            returner.start();
            closer.start();
            start.countDown();
            returner.join(10000);
            closer.join(10000);
            assertEquals(1, first.closes.get(), "round " + round);
            assertEquals(0, fileSystem.getOpenClientCount(), "round " + round);
            assertEquals(0, fileSystem.getHandedOutCount(), "round " + round);
            first.release();
        }
        assertEquals(0, other.closes.get());
        assertEquals(1, replacement.getOpenClientCount());
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    public void clientCreatedWhileTheFileSystemClosesIsClosedWhenItComesBack() throws Exception {
        final Client first = new Client();
        final Client created = new Client();
        final CountDownLatch inCreate = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final Factory factory = new Factory(first.wrapper, created.wrapper) {
            @Override
            public SMBClient create() throws FileSystemException {
                if (creates.get() == 1) {
                    inCreate.countDown();
                    try {
                        finish.await(10, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.create();
            }
        };
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        assertSame(first.wrapper, fileSystem.getClient());
        final AtomicReference<SMBClient> got = new AtomicReference<>();
        final Thread borrower = new Thread(() -> {
            try {
                got.set(fileSystem.getClient());
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }, "borrower");
        borrower.start();
        assertTrue(inCreate.await(10, TimeUnit.SECONDS));
        final Thread closer = new Thread(fileSystem::closeCommunicationLink, "closer");
        closer.start();
        // The close takes the monitor of the file system, which the connect of getClient holds.
        for (int i = 0; i < 100 && closer.getState() != Thread.State.BLOCKED; i++) {
            Thread.sleep(10);
        }
        assertEquals(Thread.State.BLOCKED, closer.getState());
        finish.countDown();
        borrower.join(10000);
        closer.join(10000);
        assertSame(created.wrapper, got.get());
        fileSystem.putClient(created.wrapper);
        fileSystem.putClient(first.wrapper);
        assertEquals(1, created.closes.get());
        assertEquals(1, first.closes.get());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    // The close of the client.

    // A client whose close runs the real code, with a real client of smbj that holds a connection to a DFS target.
    // The close of that connection closes the socket of the target.
    private static Socket[] clientWithATarget(final Client client) throws Exception {
        final Socket target = new Socket();
        final Socket left = new Socket();
        client.group.add(target);
        client.group.add(left);
        final SMBClient delegate = new SMBClient();
        final Connection nested = mock(Connection.class);
        doAnswer(call -> {
            target.close();
            return null;
        }).when(nested).close();
        table(delegate).put("dfs-target:445", nested);
        Smb2Doubles.set(client.wrapper, "smbClient", delegate);
        // The client that the wrapper extends opened no connection, so its table is empty.
        final Field own = SMBClient.class.getDeclaredField("connectionTable");
        own.setAccessible(true);
        own.set(client.wrapper, new ConcurrentHashMap<>());
        doCallRealMethod().when(client.wrapper).close();
        return new Socket[] {target, left};
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Connection> table(final SMBClient client) throws Exception {
        final Field field = SMBClient.class.getDeclaredField("connectionTable");
        field.setAccessible(true);
        return (Map<String, Connection>) field.get(client);
    }

    @Test
    public void closeOfTheFileSystemClosesTheTargetSocketAndEverySocketLeft() throws Exception {
        final Client client = new Client();
        final Client other = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper));
        final Socket[] sockets = clientWithATarget(client);
        fileSystem.closeCommunicationLink();
        assertTrue(sockets[0].isClosed(), "the socket of the DFS target");
        assertTrue(sockets[1].isClosed(), "a socket left in the group");
        assertEquals(0, fileSystem.getOpenClientCount());
        // The close of one client touches no other client.
        assertFalse(other.group.isAborted());
        assertTrue(other.wrapper.isAliveLocally());
    }

    @Test
    public void surplusReturnClosesTheTargetSocketOfTheSurplusClient() throws Exception {
        final Client first = new Client();
        final Client second = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(first.wrapper, second.wrapper));
        assertSame(first.wrapper, fileSystem.getClient());
        assertSame(second.wrapper, fileSystem.getClient());
        final Socket[] sockets = clientWithATarget(second);
        fileSystem.putClient(first.wrapper);
        fileSystem.putClient(second.wrapper);
        assertTrue(sockets[0].isClosed());
        assertTrue(sockets[1].isClosed());
        assertSame(first.wrapper, fileSystem.getClient());
        assertTrue(first.wrapper.isAliveLocally());
    }

    @Test
    public void setupThatFailsHalfWayKeepsNoSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // The server takes the connect and closes it, so the negotiate of smbj fails after the socket exists.
            final Thread acceptor = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.getInputStream().read();
                } catch (final IOException e) {
                    // The test ends either way.
                }
            }, "acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            final SocketGroup group = new SocketGroup();
            final GenericFileName root = (GenericFileName) Smb2FileNameParser.getInstance().parseUri(null, null,
                    "smb2://smbuser:smbpass@127.0.0.1:" + server.getLocalPort() + "/data/");
            assertThrows(FileSystemException.class, () -> new Smb2ClientWrapper(root, new FileSystemOptions(), group));
            assertTrue(group.isAborted());
            assertTrue(group.getSockets().isEmpty());
        }
    }
}
