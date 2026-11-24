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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.net.util.TrustManagerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * The local check of an idle FTP client reads its control connection for at most 1 ms. It finds a server that sent a
 * reply and closed, or that closed with no reply. It leaves a quiet connection usable, also with TLS, and it sends
 * nothing.
 */
public class FtpIdleProbeTest {

    @TempDir
    File home;

    private StandardFileSystemManager manager;

    // The socket groups of the clients, so that a test can see that a close left no socket open.
    private final List<SocketGroup> groups = new CopyOnWriteArrayList<>();

    private ControlServer control;

    private ScriptedFtpServer server;

    /**
     * A plain FTP control server. It answers the commands of a login and of FEAT, and the test decides when it speaks
     * on its own and when it closes.
     */
    private static final class ControlServer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());

        ControlServer() throws IOException {
            final Thread acceptor = new Thread(this::accept, "control server");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    final Socket socket = listener.accept();
                    sockets.add(socket);
                    final Thread session = new Thread(() -> serve(socket), "control session");
                    session.setDaemon(true);
                    session.start();
                } catch (final IOException e) {
                    return;
                }
            }
        }

        private void serve(final Socket socket) {
            try {
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                send(socket, "220 ready");
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line);
                    final String verb = line.split(" ")[0].toUpperCase(Locale.ROOT);
                    switch (verb) {
                        case "USER":
                            send(socket, "331 password please");
                            break;
                        case "PASS":
                            send(socket, "230 logged in");
                            break;
                        case "PWD":
                            send(socket, "257 \"/\" is the current folder");
                            break;
                        case "SYST":
                            send(socket, "215 UNIX Type: L8");
                            break;
                        case "FEAT":
                            send(socket, "211 End");
                            break;
                        default:
                            send(socket, "200 done");
                    }
                }
            } catch (final IOException e) {
                // The test closed the connection.
            }
        }

        static synchronized void send(final Socket socket, final String reply) throws IOException {
            final OutputStream out = socket.getOutputStream();
            out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        Socket last() {
            return sockets.get(sockets.size() - 1);
        }

        @Override
        public void close() throws IOException {
            listener.close();
            for (final Socket socket : sockets) {
                socket.close();
            }
        }
    }

    @BeforeEach
    public void start() throws Exception {
        manager = new StandardFileSystemManager() {
            @Override
            public SocketGroup newSocketGroup() {
                final SocketGroup group = super.newSocketGroup();
                groups.add(group);
                return group;
            }
        };
        // The subclass lives in another package, so the configuration is named here.
        manager.setConfiguration(StandardFileSystemManager.class.getResource("providers.xml"));
        manager.init();
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        if (control != null) {
            control.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // A file system with one idle client on the control server.
    private FtpFileSystem idleFileSystem() throws Exception {
        control = new ControlServer();
        final String uri = "ftp://user:pass@127.0.0.1:" + control.listener.getLocalPort() + "/";
        final FtpFileSystem fileSystem = (FtpFileSystem) manager.resolveFile(uri).getFileSystem();
        fileSystem.putClient(fileSystem.getClient());
        return fileSystem;
    }

    // The check finds the close within a short wait, because the close of the server reaches the client at once.
    private static boolean aliveAfterTheServerClosed(final FtpFileSystem fileSystem) throws InterruptedException {
        final long end = System.nanoTime() + 2_000_000_000L;
        boolean alive = true;
        while (alive && System.nanoTime() < end) {
            alive = fileSystem.isAliveLocally();
            Thread.sleep(10);
        }
        return alive;
    }

    @Test
    public void replyAndCloseOfTheServerMakeTheIdleClientNotAlive() throws Exception {
        final FtpFileSystem fileSystem = idleFileSystem();
        final FtpClient idle = fileSystem.getClient();
        fileSystem.putClient(idle);
        final int sent = control.commands.size();
        ControlServer.send(control.last(), "421 Service not available, closing control connection.");
        control.last().close();

        assertFalse(aliveAfterTheServerClosed(fileSystem));
        assertEquals(sent, control.commands.size(), "The check sends nothing");
        final FtpClient next = fileSystem.getClient();
        assertNotSame(idle, next, "The closed client is not handed out again");
        fileSystem.putClient(next);
    }

    @Test
    public void closeOfTheServerWithNoReplyMakesTheIdleClientNotAlive() throws Exception {
        final FtpFileSystem fileSystem = idleFileSystem();
        final int sent = control.commands.size();
        control.last().close();

        assertFalse(aliveAfterTheServerClosed(fileSystem));
        assertEquals(sent, control.commands.size(), "The check sends nothing");
        // The client stays in the slot until the next request. A close of the file system still closes its socket.
        manager.closeFileSystem(fileSystem);
        for (final SocketGroup group : groups) {
            assertTrue(group.getSockets().isEmpty(), "No socket stays open after the close");
        }
    }

    // The hand out of an idle client reads it too, so a path that never calls the check, as a poll of the inbound,
    // gets a new client and not the one that the server closed.
    @Test
    public void handOutOfAnIdleClientThatTheServerClosedGivesANewClient() throws Exception {
        final FtpFileSystem fileSystem = idleFileSystem();
        final FtpClient idle = fileSystem.getClient();
        fileSystem.putClient(idle);
        control.last().close();
        // The close reaches the client at once on the loopback, so the next hand out sees it.
        Thread.sleep(200);

        final FtpClient next = fileSystem.getClient();
        assertNotSame(idle, next, "The closed client is not handed out");
        assertEquals(2, control.sockets.size(), "The new client has its own connection");
        fileSystem.putClient(next);
    }

    @Test
    public void quietServerKeepsTheIdleClientAliveAndUsable() throws Exception {
        final FtpFileSystem fileSystem = idleFileSystem();
        final FtpClient idle = fileSystem.getClient();
        fileSystem.putClient(idle);
        final int sent = control.commands.size();

        for (int round = 0; round < 3; round++) {
            assertTrue(fileSystem.isAliveLocally());
        }
        assertEquals(sent, control.commands.size(), "The check sends nothing");
        final FtpClient same = fileSystem.getClient();
        assertSame(idle, same);
        // The read left the reply stream in step: a command still gets its own reply.
        assertFalse(same.hasFeature("MDTM"));
        assertTrue(control.commands.get(control.commands.size() - 1).startsWith("FEAT"));
        fileSystem.putClient(same);
        assertEquals(1, control.sockets.size());
    }

    // A read that times out on the TLS socket leaves the connection usable: after the check the same client lists
    // the folder over the same control connection.
    @Test
    public void quietTlsServerKeepsTheIdleClientAliveAndUsable() throws Exception {
        new File(home, "d").mkdir();
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = new FileSystemOptions();
        final FtpsFileSystemConfigBuilder builder = FtpsFileSystemConfigBuilder.getInstance();
        builder.setFtpsMode(options, FtpsMode.EXPLICIT);
        builder.setPassiveMode(options, true);
        builder.setTrustManager(options, TrustManagerUtils.getAcceptAllTrustManager());
        final FtpFileSystem fileSystem = (FtpFileSystem) manager.resolveFile(server.uri("ftps", "/"), options)
                .getFileSystem();
        final FtpClient idle = fileSystem.getClient();
        fileSystem.putClient(idle);
        final long commands = server.trace().size();

        for (int round = 0; round < 3; round++) {
            assertTrue(fileSystem.isAliveLocally());
        }
        assertEquals(commands, server.trace().size(), "The check sends nothing");
        final FtpClient same = fileSystem.getClient();
        assertSame(idle, same);
        assertEquals(1, same.listFiles("/").length);
        fileSystem.putClient(same);
        assertEquals(1, server.getConnects());
    }
}
