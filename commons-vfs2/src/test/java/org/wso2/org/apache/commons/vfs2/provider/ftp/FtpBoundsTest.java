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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Bound;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocket;

/**
 * The FTP bounds, the bounds in effect and the connect loop through an HTTP proxy.
 */
public class FtpBoundsTest {

    private static final FtpFileSystemConfigBuilder FTP = FtpFileSystemConfigBuilder.getInstance();

    private static final char[] USER = ScriptedFtpServer.USER.toCharArray();

    private static final char[] PASSWORD = ScriptedFtpServer.PASSWORD.toCharArray();

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    private final List<Runnable> cleanups = new ArrayList<>();

    @BeforeEach
    public void start() throws Exception {
        Files.write(new File(home, "small.txt").toPath(), "small file".getBytes(StandardCharsets.UTF_8));
        server = ScriptedFtpServer.plain(home);
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
        server.close();
        cleanups.forEach(Runnable::run);
    }

    @Test
    public void ftpBoundsAreTheDefaultsWhenNothingIsSet() {
        final EffectiveBounds bounds = EffectiveBounds.of("ftp", new FileSystemOptions());
        assertBound(30000, Source.DEFAULT, bounds.getConnectTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getSocketTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getDataTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getWriteStallTimeout());
        assertNull(bounds.getSessionTimeout());
        assertNull(bounds.getKeepAliveCount());
        assertEquals(bounds, EffectiveBounds.of("ftp", null));
    }

    @Test
    public void ftpBoundsComeFromTheEndpoint() {
        final FileSystemOptions options = new FileSystemOptions();
        FTP.setConnectTimeout(options, Duration.ofMillis(15000));
        FTP.setSoTimeout(options, Duration.ofMillis(20000));
        FTP.setDataTimeout(options, Duration.ofMillis(25000));
        FTP.setWriteStallTimeout(options, Duration.ofMillis(7000));
        final EffectiveBounds bounds = EffectiveBounds.of("ftp", options);
        assertBound(15000, Source.ENDPOINT, bounds.getConnectTimeout());
        assertBound(20000, Source.ENDPOINT, bounds.getSocketTimeout());
        assertBound(25000, Source.ENDPOINT, bounds.getDataTimeout());
        assertBound(7000, Source.ENDPOINT, bounds.getWriteStallTimeout());
    }

    @Test
    public void ftpsBoundsComeFromTheFtpsBuilder() {
        final FileSystemOptions options = new FileSystemOptions();
        FtpsFileSystemConfigBuilder.getInstance().setSoTimeout(options, Duration.ofMillis(21000));
        FtpsFileSystemConfigBuilder.getInstance().setWriteStallTimeout(options, Duration.ofMillis(0));
        final EffectiveBounds ftps = EffectiveBounds.of("ftps", options);
        assertEquals("ftps", ftps.getScheme());
        assertBound(21000, Source.ENDPOINT, ftps.getSocketTimeout());
        assertBound(0, Source.ENDPOINT, ftps.getWriteStallTimeout());
    }

    @Test
    public void liveClientCarriesTheBoundsInEffect() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FTP.setConnectTimeout(options, Duration.ofMillis(15000));
        FTP.setSoTimeout(options, Duration.ofMillis(20000));
        FTP.setDataTimeout(options, Duration.ofMillis(25000));
        for (final FileSystemOptions each : new FileSystemOptions[] {options, new FileSystemOptions()}) {
            final EffectiveBounds bounds = EffectiveBounds.of("ftp", each);
            final SocketGroup group = new SocketGroup();
            final FTPClient client = connect(each, group);
            try {
                assertEquals(bounds.getConnectTimeout().getValue(), client.getConnectTimeout());
                assertEquals(bounds.getConnectTimeout().getValue(), client.getDefaultTimeout());
                assertEquals(bounds.getSocketTimeout().getValue(), client.getSoTimeout());
                assertEquals(bounds.getDataTimeout().getValue(), client.getDataTimeout().toMillis());
                // The control socket comes from the factory of the group.
                assertFalse(group.getSockets().isEmpty());
                assertTrue(group.getSockets().stream().allMatch(socket -> socket instanceof WatchedSocket));
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    public void fileSystemReportsTheBoundsOfItsOptionsAndOfTheUriOption() throws Exception {
        final FileObject file = manager.resolveFile(server.uri("ftp", "/small.txt"), new FileSystemOptions());
        assertTrue(file.exists());
        final AbstractFileSystem fileSystem = (AbstractFileSystem) file.getFileSystem();
        assertEquals(EffectiveBounds.of("ftp", fileSystem.getFileSystemOptions()), fileSystem.getEffectiveBounds());
        // The URI option sets the connect and the socket timeout to one value, in the copy of the options that the
        // clients use.
        // A new manager, so the file system of the URI with the option is made fresh.
        final StandardFileSystemManager other = new StandardFileSystemManager();
        other.init();
        cleanups.add(other::close);
        final FileObject timed = other.resolveFile(server.uri("ftp", "/small.txt?transport.vfs.ConnectTimeout=12000"),
                new FileSystemOptions());
        final FtpFileSystem ftp = (FtpFileSystem) timed.getFileSystem();
        final EffectiveBounds clientBounds = EffectiveBounds.of("ftp", ftp.getClientOptions());
        assertEquals(12000, clientBounds.getConnectTimeout().getValue());
        assertEquals(12000, clientBounds.getSocketTimeout().getValue());
        assertEquals(60000, clientBounds.getDataTimeout().getValue());
    }

    @Test
    public void greetingThatNeverComesEndsAtTheConnectTimeout() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final List<Socket> accepted = acceptAndHold(silent);
            final FileSystemOptions options = new FileSystemOptions();
            FTP.setConnectTimeout(options, Duration.ofMillis(1500));
            final SocketGroup group = new SocketGroup();
            final long start = System.nanoTime();
            final FileSystemException error = assertThrows(FileSystemException.class,
                    () -> FtpClientFactory.createConnection("127.0.0.1", silent.getLocalPort(), USER, PASSWORD, null,
                            options, null, null, null, null, null, null, group, System.nanoTime()));
            final long millis = (System.nanoTime() - start) / 1000000;
            assertTrue(millis >= 1400 && millis < 4000, "took " + millis + " ms");
            assertTrue(hasCause(error, SocketTimeoutException.class), String.valueOf(error));
            assertTrue(Faults.isTransport(error));
            assertTrue(group.isAborted());
            assertTrue(group.isEmpty());
            assertEquals(1, accepted.size());
        }
    }

    @Test
    public void proxyThatClosesAtOnceGetsTheRetryCountInsideTheWindow() throws Exception {
        for (final int retries : new int[] {3, 40}) {
            try (ServerSocket proxy = new ServerSocket(0, 200, InetAddress.getLoopbackAddress())) {
                final List<Long> connects = acceptAndClose(proxy, 0);
                final long start = System.nanoTime();
                assertThrows(FileSystemException.class, () -> connectThroughProxy(proxy.getLocalPort(), retries));
                final long millis = (System.nanoTime() - start) / 1000000;
                Thread.sleep(200);
                assertEquals(retries, connects.size());
                assertTrue(millis < 5000, "took " + millis + " ms");
            }
        }
    }

    @Test
    public void proxyThatFailsAfterTwoSecondsGetsNoConnectAfterTheWindow() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 200, InetAddress.getLoopbackAddress())) {
            final List<Long> connects = acceptAndClose(proxy, 2000);
            final long start = System.nanoTime();
            assertThrows(FileSystemException.class, () -> connectThroughProxy(proxy.getLocalPort(), 100));
            final long millis = (System.nanoTime() - start) / 1000000;
            assertEquals(3, connects.size(), "connects at " + connects);
            for (final long at : connects) {
                assertTrue((at - start) / 1000000 < 5000, "a connect started after the window: " + connects);
            }
            assertTrue(millis < 8000, "took " + millis + " ms");
        }
    }

    @Test
    public void ftpThroughAnHttpProxyFromTheUri() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy()) {
            final FileObject file = manager.resolveFile(server.uri("ftp", "/small.txt?vfs.passive=true&proxyServer="
                    + "127.0.0.1&proxyPort=" + proxy.port()), new FileSystemOptions());
            assertEquals("small file", new String(file.getContent().getByteArray(), StandardCharsets.UTF_8));
            // One tunnel for the control connection, and one for each data connection.
            assertTrue(proxy.targets().contains("127.0.0.1:" + server.getPort()), proxy.targets().toString());
            assertTrue(proxy.targets().size() >= 2, proxy.targets().toString());
        }
    }

    @Test
    public void silentProxyFailsTheConnectAtTheConnectTimeout() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy(Integer.MAX_VALUE, 0, 0, server.getPort())) {
            final FileSystemOptions options = new FileSystemOptions();
            FTP.setConnectTimeout(options, Duration.ofMillis(300));
            final long start = System.nanoTime();
            final FileSystemException error = assertThrows(FileSystemException.class,
                    () -> connectThroughProxy(proxy.port(), 1, options));
            final long millis = (System.nanoTime() - start) / 1000000;
            assertTrue(millis >= 250 && millis < 1300, "took " + millis + " ms");
            assertTrue(hasCause(error, SocketTimeoutException.class), String.valueOf(error));
            assertTrue(Faults.isTransport(error), String.valueOf(error));
            assertEquals(1, proxy.accepted());
        }
    }

    @Test
    public void proxyReplyInSmallPartsEndsAtTheConnectTimeout() throws Exception {
        // 40 bytes of reply, one each 50 ms. Each read gets a byte in time, so only the one budget ends the wait.
        try (TunnelProxy proxy = new TunnelProxy(0, 50, 0, server.getPort())) {
            final FileSystemOptions options = new FileSystemOptions();
            FTP.setConnectTimeout(options, Duration.ofMillis(300));
            final long start = System.nanoTime();
            final FileSystemException error = assertThrows(FileSystemException.class,
                    () -> connectThroughProxy(proxy.port(), 1, options));
            final long millis = (System.nanoTime() - start) / 1000000;
            assertTrue(millis >= 250 && millis < 1300, "took " + millis + " ms");
            assertTrue(hasCause(error, SocketTimeoutException.class), String.valueOf(error));
        }
    }

    @Test
    public void controlAndDataThroughTheProxyKeepTheirOwnTimeoutsAfterTheReply() throws Exception {
        // The connect budget is 300 ms. The reply of PASV and the data both come after 800 ms, inside the socket
        // timeout and the data timeout of 3 s.
        try (TunnelProxy proxy = new TunnelProxy(0, 0, 800, server.getPort())) {
            final FileSystemOptions options = new FileSystemOptions();
            FTP.setConnectTimeout(options, Duration.ofMillis(300));
            FTP.setSoTimeout(options, Duration.ofMillis(3000));
            FTP.setDataTimeout(options, Duration.ofMillis(3000));
            server.delay("PASV", 800, 1);
            final long start = System.nanoTime();
            final FileObject file = manager.resolveFile(server.uri("ftp", "/small.txt?vfs.passive=true&proxyServer="
                    + "127.0.0.1&proxyPort=" + proxy.port()), options);
            assertEquals("small file", new String(file.getContent().getByteArray(), StandardCharsets.UTF_8));
            // One delayed reply of PASV and at least two delayed data connections, the listing and the read.
            final long millis = (System.nanoTime() - start) / 1000000;
            assertTrue(millis >= 2400, "took " + millis + " ms");
            assertTrue(proxy.targets().size() >= 3, proxy.targets().toString());
        }
    }

    @Test
    public void connectThroughTheProxyWorksOnTheNextTryAfterASilentOne() throws Exception {
        try (TunnelProxy proxy = new TunnelProxy(1, 0, 0, server.getPort())) {
            final FileSystemOptions options = new FileSystemOptions();
            FTP.setConnectTimeout(options, Duration.ofMillis(300));
            final SocketGroup group = new SocketGroup();
            final FTPClient client = FtpClientFactory.createConnection("127.0.0.1", server.getPort(), USER, PASSWORD,
                    null, options, "127.0.0.1", String.valueOf(proxy.port()), null, null, null, "2", group,
                    System.nanoTime());
            try {
                assertTrue(client.sendNoOp());
                assertEquals(2, proxy.accepted());
                assertEquals(1, server.getConnects());
            } finally {
                client.disconnect();
            }
        }
    }

    private static void assertBound(final long value, final Source source, final Bound bound) {
        assertEquals(value, bound.getValue());
        assertEquals(source, bound.getSource());
    }

    private FTPClient connect(final FileSystemOptions options, final SocketGroup group) throws FileSystemException {
        return FtpClientFactory.createConnection("127.0.0.1", server.getPort(), USER, PASSWORD, null, options, null,
                null, null, null, null, null, group, System.nanoTime());
    }

    private void connectThroughProxy(final int proxyPort, final int retries) throws FileSystemException {
        connectThroughProxy(proxyPort, retries, new FileSystemOptions());
    }

    private void connectThroughProxy(final int proxyPort, final int retries, final FileSystemOptions options)
            throws FileSystemException {
        FtpClientFactory.createConnection("127.0.0.1", server.getPort(), USER, PASSWORD, null, options,
                "127.0.0.1", String.valueOf(proxyPort), null, null, null, String.valueOf(retries), new SocketGroup(),
                System.nanoTime());
    }

    private static boolean hasCause(final Throwable error, final Class<?> type) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return true;
            }
        }
        return false;
    }

    // Accepts each connection and keeps it open, sending nothing.
    private List<Socket> acceptAndHold(final ServerSocket listener) {
        final List<Socket> accepted = new CopyOnWriteArrayList<>();
        final Thread thread = new Thread(() -> {
            try {
                while (true) {
                    accepted.add(listener.accept());
                }
            } catch (final IOException e) {
                // The listener closed.
            }
        });
        thread.setDaemon(true);
        thread.start();
        cleanups.add(() -> accepted.forEach(FtpBoundsTest::closeQuietly));
        return accepted;
    }

    // Accepts each connection, waits, and closes it. Returns the System.nanoTime of each accept.
    private List<Long> acceptAndClose(final ServerSocket listener, final long waitMillis) {
        final List<Long> times = new CopyOnWriteArrayList<>();
        final Thread thread = new Thread(() -> {
            try {
                while (true) {
                    final Socket socket = listener.accept();
                    times.add(System.nanoTime());
                    final Thread closer = new Thread(() -> {
                        try {
                            Thread.sleep(waitMillis);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        closeQuietly(socket);
                    });
                    closer.setDaemon(true);
                    closer.start();
                }
            } catch (final IOException e) {
                // The listener closed.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return times;
    }

    private static void closeQuietly(final Socket socket) {
        try {
            socket.close();
        } catch (final IOException e) {
            // Nothing to do in a test cleanup.
        }
    }

    /**
     * A small HTTP proxy that only knows CONNECT. It records each target.
     */
    static final class TunnelProxy implements AutoCloseable {

        private final ServerSocket listener;

        private final List<String> targets = Collections.synchronizedList(new ArrayList<>());

        private final List<Socket> held = new CopyOnWriteArrayList<>();

        private final AtomicInteger accepted = new AtomicInteger();

        // The first connections that get no reply at all.
        private final int silent;

        // The wait between two bytes of the reply.
        private final long dripMillis;

        // The wait before the bytes of a data connection go back to the client.
        private final long dataDelayMillis;

        private final int controlPort;

        TunnelProxy() throws IOException {
            this(0, 0, 0, -1);
        }

        TunnelProxy(final int silent, final long dripMillis, final long dataDelayMillis, final int controlPort)
                throws IOException {
            this.silent = silent;
            this.dripMillis = dripMillis;
            this.dataDelayMillis = dataDelayMillis;
            this.controlPort = controlPort;
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            final Thread thread = new Thread(this::serve);
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        List<String> targets() {
            synchronized (targets) {
                return new ArrayList<>(targets);
            }
        }

        int accepted() {
            return accepted.get();
        }

        private void serve() {
            try {
                while (true) {
                    final Socket client = listener.accept();
                    if (accepted.incrementAndGet() <= silent) {
                        held.add(client);
                        continue;
                    }
                    final Thread tunnel = new Thread(() -> tunnel(client));
                    tunnel.setDaemon(true);
                    tunnel.start();
                }
            } catch (final IOException e) {
                // The listener closed.
            }
        }

        private void tunnel(final Socket client) {
            try {
                final InputStream in = client.getInputStream();
                final String request = readLine(in);
                while (!readLine(in).isEmpty()) {
                    // The headers are not needed.
                }
                final String target = request.split(" ")[1];
                targets.add(target);
                final String[] hostPort = target.split(":");
                final Socket server = new Socket(hostPort[0], Integer.parseInt(hostPort[1]));
                final OutputStream out = client.getOutputStream();
                for (final byte b : "HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) {
                    out.write(b);
                    out.flush();
                    if (dripMillis > 0) {
                        Thread.sleep(dripMillis);
                    }
                }
                final boolean data = Integer.parseInt(hostPort[1]) != controlPort;
                final Thread back = new Thread(() -> {
                    if (data && dataDelayMillis > 0) {
                        try {
                            Thread.sleep(dataDelayMillis);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    pipe(server, client);
                });
                back.setDaemon(true);
                back.start();
                pipe(client, server);
            } catch (final IOException e) {
                closeQuietly(client);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                closeQuietly(client);
            }
        }

        // Reads one line of the request, byte by byte, so no byte of the tunnel is taken.
        private static String readLine(final InputStream in) throws IOException {
            final StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    line.append((char) c);
                }
            }
            return line.toString();
        }

        private static void pipe(final Socket from, final Socket to) {
            final byte[] buffer = new byte[8192];
            try {
                final InputStream in = from.getInputStream();
                final OutputStream out = to.getOutputStream();
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            } catch (final IOException e) {
                // The other side closed.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            held.forEach(FtpBoundsTest::closeQuietly);
        }
    }
}
