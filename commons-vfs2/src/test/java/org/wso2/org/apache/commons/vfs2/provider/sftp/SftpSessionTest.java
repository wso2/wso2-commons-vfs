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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.apache.mina.core.service.IoAcceptor;
import org.apache.sshd.SshServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

/**
 * Sessions and channels: a dead idle channel is not handed out, a closed file system opens no session, and a channel
 * is put back or discarded by how the call on it ended.
 */
public class SftpSessionTest {

    private SftpTestServer server;

    private TestRelay relay;

    @BeforeEach
    public void start() throws Exception {
        server = new SftpTestServer();
        server.write("in/small.txt", "small");
        relay = new TestRelay(server.getPort());
    }

    @AfterEach
    public void stop() throws Exception {
        relay.close();
        server.close();
    }

    private static StandardFileSystemManager manager() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        return manager;
    }

    private String uri(final String path) {
        return SftpTestServer.uri(relay.getPort(), path);
    }

    private static SftpFileSystem fileSystemOf(final FileObject file) {
        return (SftpFileSystem) file.getFileSystem();
    }

    private static Object field(final SftpFileSystem fileSystem, final String name) throws Exception {
        final Field field = SftpFileSystem.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(fileSystem);
    }

    private static void awaitTrue(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail(what);
            }
            Thread.sleep(20);
        }
    }

    private static String read(final FileObject file) throws IOException {
        try (InputStream in = file.getContent().getInputStream()) {
            final byte[] buffer = new byte[64];
            final int read = in.read(buffer);
            return new String(buffer, 0, read, "UTF-8");
        }
    }

    @Test
    public void deadIdleChannelIsNotHandedOutAndTheNextCallWorksOnANewSession() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertEquals("small", read(file));
            final SftpFileSystem fileSystem = fileSystemOf(file);
            assertTrue(field(fileSystem, "idleChannel") != null, "an idle channel stays after the read");
            final Session before = SftpTestServer.sessionOf(fileSystem);
            relay.cutAll();
            awaitTrue("the session sees the close", () -> !before.isConnected());
            assertFalse(fileSystem.isAliveLocally());
            file.refresh();
            assertEquals("small", read(file));
            assertNotSame(before, SftpTestServer.sessionOf(fileSystem));
            assertEquals(2, relay.getAccepted());
            assertTrue(fileSystem.isAliveLocally());
        }
    }

    private static void setField(final SftpFileSystem fileSystem, final String name, final Object value)
            throws Exception {
        final Field field = SftpFileSystem.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(fileSystem, value);
    }

    private static SftpException cut() {
        return new SftpException(4, "cut", new IOException("cut"));
    }

    @Test
    public void lateDiscardOfTheOldSessionDuringAReconnectLeavesTheNewSessionAlone() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final Session old = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup oldGroup = SftpTestServer.groupOf(fileSystem);
            final ChannelSftp late = fileSystem.getChannel();
            relay.cutAll();
            awaitTrue("the session sees the close", () -> !old.isConnected());
            relay.holdNewConnections(true);
            final CompletableFuture<Boolean> reconnect = CompletableFuture.supplyAsync(() -> {
                try {
                    file.refresh();
                    return file.exists();
                } catch (final FileSystemException e) {
                    throw new IllegalStateException(e);
                }
            });
            awaitTrue("the new connection is held", () -> relay.getAccepted() == 2);
            // The old channel comes back with a failure while the new session connects.
            fileSystem.releaseChannel(late, cut());
            relay.holdNewConnections(false);
            assertTrue(reconnect.get(30, TimeUnit.SECONDS));
            final Session fresh = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup freshGroup = SftpTestServer.groupOf(fileSystem);
            assertNotSame(old, fresh);
            assertNotSame(oldGroup, freshGroup);
            assertTrue(oldGroup.isAborted());
            assertFalse(freshGroup.isAborted());
            assertTrue(fresh.isConnected());
            assertTrue(fileSystem.isAliveLocally());
            assertEquals(0, fileSystem.getHandedOutCount());
            assertEquals(1, fileSystem.getOpenClientCount());
            assertEquals(2, relay.getAccepted());
        }
    }

    @Test
    public void lateDiscardOfTheOldSessionAfterAReconnectLeavesTheNewSessionAlone() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final Session old = SftpTestServer.sessionOf(fileSystem);
            final ChannelSftp late = fileSystem.getChannel();
            relay.cutAll();
            awaitTrue("the session sees the close", () -> !old.isConnected());
            file.refresh();
            assertTrue(file.exists());
            final Session fresh = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup freshGroup = SftpTestServer.groupOf(fileSystem);
            assertNotSame(old, fresh);
            fileSystem.releaseChannel(late, cut());
            assertFalse(freshGroup.isAborted());
            assertTrue(fresh.isConnected());
            assertTrue(fileSystem.isAliveLocally());
            assertEquals(0, fileSystem.getHandedOutCount());
            // The old channel did not go into the pool of the new session.
            file.refresh();
            assertTrue(file.exists());
            assertSame(fresh, SftpTestServer.sessionOf(fileSystem));
            assertEquals(2, relay.getAccepted());
        }
    }

    @Test
    public void staleChannelOfAnOldSessionIsNotPooledWhenItComesBackWithNoError() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final Session old = SftpTestServer.sessionOf(fileSystem);
            final ChannelSftp late = fileSystem.getChannel();
            relay.cutAll();
            awaitTrue("the session sees the close", () -> !old.isConnected());
            file.refresh();
            assertTrue(file.exists());
            fileSystem.releaseChannel(late, null);
            assertFalse(late.isConnected());
            assertTrue(field(fileSystem, "idleChannel") != late);
            assertEquals(0, fileSystem.getHandedOutCount());
            assertTrue(fileSystem.isAliveLocally());
        }
    }

    @Test
    public void channelThatComesBackAfterTheCloseBeganIsNotPooled() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final ChannelSftp channel = fileSystem.getChannel();
            // The close marks the file system first and frees the pool after. A return falls in between.
            setField(fileSystem, "isFileSystemClosed", true);
            fileSystem.releaseChannel(channel, null);
            assertFalse(channel.isConnected());
            assertEquals(null, field(fileSystem, "idleChannel"));
            assertEquals(0, fileSystem.getHandedOutCount());
        }
    }

    @Test
    public void idleChannelIsNotHandedOutAfterTheCloseBegan() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final ChannelSftp idle = (ChannelSftp) field(fileSystem, "idleChannel");
            assertTrue(idle != null && idle.isConnected());
            setField(fileSystem, "isFileSystemClosed", true);
            assertThrows(FileSystemException.class, fileSystem::getChannel);
            assertFalse(idle.isConnected());
            assertEquals(0, fileSystem.getHandedOutCount());
            assertEquals(1, relay.getAccepted());
        }
    }

    @Test
    public void failedOpenOfAChannelDiscardsItsSessionAndTheNextOperationWorks() throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, Duration.ofMillis(1000));
        try (StandardFileSystemManager manager = manager()) {
            final String test = "failedOpenOfAChannelDiscardsItsSessionAndTheNextOperationWorks";
            final FileObject file = SetupConnect.withRetry(test, () -> {
                final FileObject resolved = manager.resolveFile(uri("in/small.txt"), options);
                assertTrue(resolved.exists());
                return resolved;
            });
            // A setup retry adds connections, so the count of the test starts after the setup.
            final int accepted = relay.getAccepted();
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final Session old = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup oldGroup = SftpTestServer.groupOf(fileSystem);
            final ChannelSftp held = fileSystem.getChannel();
            assertEquals(1, fileSystem.getHandedOutCount());
            server.setDelays(3000, 0, 0);
            final long start = System.nanoTime();
            assertThrows(FileSystemException.class, fileSystem::getChannel);
            final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 900 && elapsed < 2900, "elapsed " + elapsed);
            // The failed open was never counted, and its session was discarded.
            assertEquals(1, fileSystem.getHandedOutCount());
            assertTrue(oldGroup.isAborted());
            assertFalse(old.isConnected());
            server.setDelays(0, 0, 0);
            fileSystem.releaseChannel(held, null);
            assertEquals(0, fileSystem.getHandedOutCount());
            file.refresh();
            assertTrue(file.exists());
            final Session fresh = SftpTestServer.sessionOf(fileSystem);
            assertNotSame(old, fresh);
            assertTrue(fresh.isConnected());
            assertFalse(SftpTestServer.groupOf(fileSystem).isAborted());
            assertEquals(0, fileSystem.getHandedOutCount());
            // The failed open discarded its session, and the next operation made exactly one new connection.
            assertEquals(accepted + 1, relay.getAccepted());
        }
    }

    private static int channelOpenTimeout(final FileSystemOptions options) throws Exception {
        final Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(session.openChannel("sftp")).thenReturn(channel);
        final SftpFileSystem fileSystem = new SftpFileSystem(new GenericFileName("sftp", "localhost", 22, 22, "user",
                "pass", "/", FileType.FOLDER) {
        }, session, new SocketGroup(), options) {
        };
        fileSystem.getChannel();
        final ArgumentCaptor<Integer> timeout = ArgumentCaptor.forClass(Integer.class);
        verify(channel).connect(timeout.capture());
        return timeout.getValue();
    }

    @Test
    public void openOfAChannelUsesTheConnectTimeoutInEffect() throws Exception {
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        assertEquals(30000, channelOpenTimeout(new FileSystemOptions()));
        final FileSystemOptions zero = new FileSystemOptions();
        builder.setConnectTimeout(zero, Duration.ZERO);
        assertEquals(0, channelOpenTimeout(zero));
        final FileSystemOptions set = new FileSystemOptions();
        builder.setConnectTimeout(set, Duration.ofMillis(15000));
        assertEquals(15000, channelOpenTimeout(set));
    }

    @Test
    public void fixtureRemovesOnlyItsOwnFoldersAtItsClose() throws Exception {
        final SftpTestServer first = new SftpTestServer();
        final SftpTestServer second = new SftpTestServer();
        final Path secondRoot = second.getRoot();
        final Path secondKey = second.getKeyDir();
        try {
            first.write("in/big.bin", new String(new char[1024 * 1024]).replace('\0', 'x'));
            second.write("in/small.txt", "small");
            final Path firstRoot = first.getRoot();
            final Path firstKey = first.getKeyDir();
            assertTrue(Files.isRegularFile(firstRoot.resolve("in/big.bin")));
            assertTrue(Files.isDirectory(firstKey));
            first.close();
            assertFalse(Files.exists(firstRoot));
            assertFalse(Files.exists(firstKey));
            // The other fixture keeps its folders and still serves.
            assertTrue(Files.isRegularFile(secondRoot.resolve("in/small.txt")));
            assertTrue(Files.isDirectory(secondKey));
            try (StandardFileSystemManager manager = manager()) {
                assertEquals("small", read(manager.resolveFile(second.uri("in/small.txt"), SftpTestServer.options())));
            }
        } finally {
            second.close();
        }
        assertFalse(Files.exists(secondRoot));
        assertFalse(Files.exists(secondKey));
        // The known hosts of the fixtures are a file of the test JVM, never one of the user.
        final Path userSsh = Paths.get(System.getProperty("user.home"), ".ssh");
        assertFalse(TestServerOptions.knownHosts().toPath().toAbsolutePath().startsWith(userSsh));
    }

    @Test
    public void fixtureThatFailsToBindStopsItsServerKeepsTheErrorAndRemovesItsFolders() throws Exception {
        // The failed fixture makes its folders under a parent of its own, so the folders of other fixtures, in this
        // JVM or in another one, do not count.
        final Path parent = Files.createTempDirectory("sftp-bind-test");
        try (ServerSocket reserved = new ServerSocket(0)) {
            // The server of the fixture is made to bind a port that is taken, so its start fails after the acceptor
            // was made.
            final SshServer real = spy(SshServer.setUpDefaultServer());
            doAnswer(invocation -> {
                invocation.callRealMethod();
                real.setPort(reserved.getLocalPort());
                return null;
            }).when(real).setPort(0);
            // The stop clears the field of the acceptor, so the acceptor is taken when the stop starts.
            final Field acceptorField = SshServer.class.getDeclaredField("acceptor");
            acceptorField.setAccessible(true);
            final AtomicReference<IoAcceptor> stopped = new AtomicReference<>();
            doAnswer(invocation -> {
                stopped.set((IoAcceptor) acceptorField.get(real));
                return invocation.callRealMethod();
            }).when(real).stop(true);
            try (MockedStatic<SshServer> servers = mockStatic(SshServer.class, CALLS_REAL_METHODS)) {
                servers.when(SshServer::setUpDefaultServer).thenReturn(real);
                final IOException error = assertThrows(IOException.class, () -> new SftpTestServer(parent));
                assertTrue(String.valueOf(error.getMessage()).contains("binding"), String.valueOf(error));
            }
            assertNotNull(stopped.get(), "the stop found the acceptor of the failed start");
            assertTrue(stopped.get().isDisposed(), "the acceptor of the failed start is disposed");
        }
        try (Stream<Path> left = Files.list(parent)) {
            assertEquals(0, left.count(), "the failed fixture left no folder");
        }
        Files.delete(parent);
        // The fixture of this test class still serves.
        try (StandardFileSystemManager manager = manager()) {
            assertEquals("small", read(manager.resolveFile(uri("in/small.txt"), SftpTestServer.options())));
        }
    }

    @Test
    public void closedFileSystemThrowsAndOpensNoSession() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            manager.closeFileSystem(file.getFileSystem());
            file.refresh();
            assertThrows(FileSystemException.class, file::exists);
            Thread.sleep(300);
            assertEquals(1, relay.getAccepted());
            awaitTrue("no session is left on the server", () -> server.getActiveSessions() == 0);
        }
    }

    @Test
    public void fileSystemClosedWhileAnotherThreadRunsOperationsLeavesNoSessionManyTimes() throws Exception {
        final Random random = new Random(5);
        final int rounds = 60;
        for (int round = 0; round < rounds; round++) {
            try (StandardFileSystemManager manager = manager()) {
                final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
                final CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < 20; i++) {
                        try {
                            file.refresh();
                            file.exists();
                        } catch (final FileSystemException e) {
                            return;
                        }
                    }
                });
                Thread.sleep(random.nextInt(50));
                manager.closeFileSystem(file.getFileSystem());
                worker.get(30, TimeUnit.SECONDS);
                file.refresh();
                assertThrows(FileSystemException.class, file::exists);
            }
        }
        assertEquals(rounds, relay.getAccepted());
        awaitTrue("no session is left on the server", () -> server.getActiveSessions() == 0);
    }

    @Test
    public void channelGoesBackAfterAReplyOfTheServer() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final ChannelSftp channel = fileSystem.getChannel();
            assertEquals(1, fileSystem.getHandedOutCount());
            final SftpException reply = assertThrows(SftpException.class, () -> channel.stat("no-such-file"));
            fileSystem.releaseChannel(channel, reply);
            assertEquals(0, fileSystem.getHandedOutCount());
            assertSame(channel, fileSystem.getChannel());
            fileSystem.releaseChannel(channel, null);
            assertEquals(1, fileSystem.getOpenClientCount());
        }
    }

    @Test
    public void channelIsDiscardedWithItsSessionAfterAFailureWithACause() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final SocketGroup group = SftpTestServer.groupOf(fileSystem);
            final Session session = SftpTestServer.sessionOf(fileSystem);
            final ChannelSftp channel = fileSystem.getChannel();
            fileSystem.releaseChannel(channel, new SftpException(4, "cut", new IOException("cut")));
            assertTrue(group.isAborted());
            assertFalse(session.isConnected());
            assertFalse(fileSystem.isAliveLocally());
            assertEquals(0, fileSystem.getOpenClientCount());
            assertEquals(0, fileSystem.getHandedOutCount());
            file.refresh();
            assertTrue(file.exists());
            assertNotSame(group, SftpTestServer.groupOf(fileSystem));
            assertEquals(2, relay.getAccepted());
        }
    }

    @Test
    public void channelIsDiscardedAfterAnyOtherExceptionAndWhenTheThreadIsInterrupted() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            fileSystem.releaseChannel(fileSystem.getChannel(), new IllegalStateException("other"));
            assertFalse(fileSystem.isAliveLocally());
            file.refresh();
            assertTrue(file.exists());
            final ChannelSftp channel = fileSystem.getChannel();
            Thread.currentThread().interrupt();
            try {
                fileSystem.releaseChannel(channel, null);
            } finally {
                Thread.interrupted();
            }
            assertFalse(fileSystem.isAliveLocally());
            assertEquals(0, fileSystem.getHandedOutCount());
        }
    }

    @Test
    public void openStreamCountsAsHandedOutUntilItIsClosed() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final InputStream in = file.getContent().getInputStream();
            assertEquals(1, fileSystem.getHandedOutCount());
            in.close();
            assertEquals(0, fileSystem.getHandedOutCount());
            assertTrue(fileSystem.isAliveLocally());
            assertEquals(0, manager.getHandedOutCount());
        }
    }

    @Test
    public void streamThatNotedAnExceptionDiscardsItsChannel() throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setSessionTimeout(options, Duration.ofMillis(1000));
        SftpFileSystemConfigBuilder.getInstance().setKeepAliveCountMax(options, 0);
        server.write("in/big.bin", new String(new char[2 * 1024 * 1024]).replace('\0', 'x'));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = SetupConnect.withRetry("streamThatNotedAnExceptionDiscardsItsChannel",
                    () -> manager.resolveFile(uri("in/big.bin"), options));
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final InputStream in = SetupConnect.withRetry("streamThatNotedAnExceptionDiscardsItsChannel", () -> {
                final InputStream opened = file.getContent().getInputStream();
                try {
                    assertTrue(opened.read(new byte[1024]) > 0);
                } catch (final IOException e) {
                    throw SetupConnect.closed(opened, e);
                }
                return opened;
            });
            relay.pause();
            assertThrows(IOException.class, () -> {
                final byte[] buffer = new byte[64 * 1024];
                while (in.read(buffer) >= 0) {
                    // Read on until the bound ends the read.
                }
            });
            try {
                in.close();
            } catch (final IOException e) {
                // The close may fail too.
            }
            relay.resume();
            assertFalse(fileSystem.isAliveLocally());
            assertEquals(0, fileSystem.getHandedOutCount());
            assertTrue(field(fileSystem, "idleChannel") == null);
        }
    }

    @Test
    public void abortClosesTheSocketsOfTheSessionAndTakesNoMonitor() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = fileSystemOf(file);
            final CompletableFuture<Void> holder = new CompletableFuture<>();
            final CompletableFuture<Void> held = CompletableFuture.runAsync(() -> {
                synchronized (fileSystem) {
                    holder.complete(null);
                    try {
                        Thread.sleep(3000);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holder.get(5, TimeUnit.SECONDS);
            final long start = System.nanoTime();
            fileSystem.abort();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
            assertFalse(fileSystem.isAliveLocally());
            held.get(10, TimeUnit.SECONDS);
            file.refresh();
            assertTrue(file.exists());
        }
    }
}
