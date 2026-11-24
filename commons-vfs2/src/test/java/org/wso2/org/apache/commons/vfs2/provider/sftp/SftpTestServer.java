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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.sshd.SshServer;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.common.Channel;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.sftp.SftpSubsystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.Session;

/**
 * An embedded SSH server for the tests of the SFTP provider. The user name is the password. The SFTP root is a temp
 * folder. Exec runs {@code id -u}, {@code id -G} and {@code nc -q 0 localhost <port>}, and the answer of exec can be
 * set: a reply, no reply at all, or a refusal of the channel request.
 */
final class SftpTestServer implements Closeable {

    /** How the server answers an exec request. */
    enum Exec {
        ANSWER, NEVER_ANSWER, REFUSE, ENDLESS_OUTPUT, DISCONNECT
    }

    static final String USER = "probe";

    private static final Pattern NETCAT = Pattern.compile("nc -q 0 localhost (\\d+)");

    private final SshServer server;

    private final Path root;

    private final AtomicInteger execRequests = new AtomicInteger();

    private final List<String> commands = new CopyOnWriteArrayList<>();

    private final List<Closeable> open = new CopyOnWriteArrayList<>();

    private final CommandFactory commandFactory = this::command;

    private volatile Exec exec = Exec.ANSWER;

    private volatile String uid = "1000";

    private volatile String groups = "1000 1001";

    // Delays of the server for the commands of the permission check: before the open of a session channel is
    // confirmed, before the exec request is answered, and before each command writes its reply.
    private volatile long openDelayMillis;

    private volatile long requestDelayMillis;

    private volatile long replyDelayMillis;

    // SFTP packet types whose requests the subsystem holds, and for how long. A hold of -1 lasts until the release.
    // The SSH layer answers keep alives on its own threads all the time.
    private final Map<Integer, Long> holds = new ConcurrentHashMap<>();

    private volatile CountDownLatch released = new CountDownLatch(1);

    // The one command whose reply is delayed, or null for every command.
    private volatile String delayedCommand;

    // The commands whose reply the server holds now, in the order it took them.
    private final BlockingQueue<String> held = new LinkedBlockingQueue<>();

    // The folder of the host key. The server owns it and the data root, and removes both at its close.
    private final Path keyDir;

    SftpTestServer() throws IOException {
        this(0);
    }

    /**
     * Starts a server with a given SSH channel window.
     *
     * @param windowSize the window of each channel in bytes, or zero for the default of the server.
     */
    SftpTestServer(final int windowSize) throws IOException {
        this(windowSize, Paths.get(System.getProperty("java.io.tmpdir")));
    }

    // A server whose two folders are made under the given parent. A test that owns the parent sees only these two.
    SftpTestServer(final Path parent) throws IOException {
        this(0, parent);
    }

    private SftpTestServer(final int windowSize, final Path parent) throws IOException {
        root = Files.createTempDirectory(parent, "sftp-test-root");
        Path key = null;
        try {
            key = Files.createTempDirectory(parent, "sftp-test-key");
        } finally {
            if (key == null) {
                delete(root);
            }
        }
        keyDir = key;
        server = SshServer.setUpDefaultServer();
        try {
            start(windowSize);
        } catch (final IOException | RuntimeException e) {
            // A start that fails half way leaves nothing behind. The stop of SSHD 0.8.0 is safe on a server that
            // started half way, and each cleanup step runs also when an earlier one fails.
            try {
                close();
            } catch (final IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void start(final int windowSize) throws IOException {
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(keyDir.resolve("key.ser").toString()));
        server.setPasswordAuthenticator((user, password, session) -> user.equals(password));
        server.setSubsystemFactories(Collections.<NamedFactory<Command>>singletonList(new NamedFactory<Command>() {
            @Override
            public String getName() {
                return "sftp";
            }

            @Override
            public Command create() {
                return new SftpSubsystem() {
                    @Override
                    protected void process(final Buffer buffer) throws IOException {
                        // The buffer starts with the length of the packet, then its type.
                        hold(buffer.array()[buffer.rpos() + 4] & 0xff);
                        super.process(buffer);
                    }
                };
            }
        }));
        server.setFileSystemFactory(session -> new AbstractSftpProviderTestCase.TestFileSystemView(root.toString(),
                session.getUsername()));
        server.setCommandFactory(commandFactory);
        server.setChannelFactories(Collections.<NamedFactory<Channel>>singletonList(new NamedFactory<Channel>() {
            @Override
            public Channel create() {
                return new ChannelSession() {
                    @Override
                    public OpenFuture open(final int recipient, final int rwsize, final int rmpsize,
                            final Buffer buffer) {
                        sleep(openDelayMillis);
                        return super.open(recipient, rwsize, rmpsize, buffer);
                    }
                };
            }

            @Override
            public String getName() {
                return "session";
            }
        }));
        if (windowSize > 0) {
            server.getProperties().put(FactoryManager.WINDOW_SIZE, Integer.toString(windowSize));
        }
        server.start();
    }

    // The session of now of a file system, and the group of its sockets, read by reflection.
    static Session sessionOf(final SftpFileSystem fileSystem) {
        return (Session) linkField(fileSystem, "session");
    }

    static SocketGroup groupOf(final SftpFileSystem fileSystem) {
        return (SocketGroup) linkField(fileSystem, "group");
    }

    private static Object linkField(final SftpFileSystem fileSystem, final String name) {
        try {
            final Field linkField = SftpFileSystem.class.getDeclaredField("link");
            linkField.setAccessible(true);
            final Object link = linkField.get(fileSystem);
            final Field field = link.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(link);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    int getPort() {
        return server.getPort();
    }

    Path getRoot() {
        return root;
    }

    Path getKeyDir() {
        return keyDir;
    }

    /**
     * Waits until the server has taken the command and holds its reply.
     *
     * @return false when that did not happen in the given time.
     */
    boolean awaitHeld(final String command, final long timeoutMillis) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            final long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            final String next = held.poll(left, TimeUnit.NANOSECONDS);
            if (command.equals(next)) {
                return true;
            }
        }
    }

    String uri(final String path) {
        return uri(getPort(), path);
    }

    static String uri(final int port, final String path) {
        return "sftp://" + USER + ":" + USER + "@localhost:" + port + "/" + path;
    }

    static FileSystemOptions options() throws FileSystemException {
        final FileSystemOptions options = new FileSystemOptions();
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        builder.setStrictHostKeyChecking(options, "no");
        builder.setUserInfo(options, new TrustEveryoneUserInfo());
        TestServerOptions.apply(options);
        return options;
    }

    Path write(final String name, final String content) throws IOException {
        final Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    void setExec(final Exec mode) {
        exec = mode;
        // A server with no command factory refuses each exec request.
        server.setCommandFactory(mode == Exec.REFUSE ? null : commandFactory);
    }

    void setDelays(final long openMillis, final long requestMillis, final long replyMillis) {
        openDelayMillis = openMillis;
        requestDelayMillis = requestMillis;
        replyDelayMillis = replyMillis;
        delayedCommand = null;
    }

    void delayReplyOf(final String command, final long replyMillis) {
        delayedCommand = command;
        replyDelayMillis = replyMillis;
    }

    // Holds each SFTP request of the given packet types for the time, or with -1 until releaseHolds.
    void holdRequests(final long millis, final int... types) {
        for (final int type : types) {
            holds.put(type, millis);
        }
    }

    // Ends every hold and lets the held requests go on.
    void releaseHolds() {
        holds.clear();
        final CountDownLatch old = released;
        released = new CountDownLatch(1);
        old.countDown();
    }

    private void hold(final int type) {
        final Long millis = holds.get(type);
        if (millis == null) {
            return;
        }
        if (millis >= 0) {
            sleep(millis);
            return;
        }
        try {
            released.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void setIds(final String userId, final String groupIds) {
        uid = userId;
        groups = groupIds;
    }

    int getExecRequests() {
        return execRequests.get();
    }

    List<String> getCommands() {
        return commands;
    }

    int getActiveSessions() {
        return server.getActiveSessions().size();
    }

    @Override
    public void close() throws IOException {
        releaseHolds();
        final List<Exception> failures = new ArrayList<>();
        for (final Closeable closeable : open) {
            try {
                closeable.close();
            } catch (final IOException | RuntimeException e) {
                failures.add(e);
            }
        }
        try {
            server.stop(true);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final RuntimeException e) {
            failures.add(e);
        }
        for (final Path folder : new Path[] {root, keyDir}) {
            try {
                delete(folder);
            } catch (final IOException | RuntimeException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            final IOException error = new IOException("The test server did not close cleanly.", failures.get(0));
            for (final Exception other : failures.subList(1, failures.size())) {
                error.addSuppressed(other);
            }
            throw error;
        }
    }

    // Removes a folder that this server made, with all that is in it.
    private static void delete(final Path folder) throws IOException {
        if (folder == null || !Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void sleep(final long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Command command(final String text) {
        execRequests.incrementAndGet();
        commands.add(text);
        sleep(requestDelayMillis);
        return new Command() {
            private ExitCallback callback;
            private OutputStream out;
            private InputStream in;

            @Override
            public void setInputStream(final InputStream stream) {
                in = stream;
            }

            @Override
            public void setOutputStream(final OutputStream stream) {
                out = stream;
            }

            @Override
            public void setErrorStream(final OutputStream stream) {
                // Not used.
            }

            @Override
            public void setExitCallback(final ExitCallback exitCallback) {
                callback = exitCallback;
            }

            @Override
            public void start(final Environment env) throws IOException {
                final Matcher netcat = NETCAT.matcher(text);
                if (netcat.matches()) {
                    final Socket socket = new Socket("localhost", Integer.parseInt(netcat.group(1)));
                    open.add(socket);
                    pump(socket.getInputStream(), out, null);
                    pump(in, socket.getOutputStream(), callback);
                    return;
                }
                if (exec == Exec.NEVER_ANSWER) {
                    return;
                }
                if (exec == Exec.DISCONNECT) {
                    // Apache SSHD 0.8.0 ends the whole session when a command fails to start.
                    throw new IOException("The test server ends the session at this exec request.");
                }
                if (exec == Exec.ENDLESS_OUTPUT) {
                    final OutputStream endless = out;
                    final Thread writer = new Thread(() -> {
                        try {
                            while (true) {
                                endless.write("1000 ".getBytes(StandardCharsets.UTF_8));
                                endless.flush();
                                Thread.sleep(5);
                            }
                        } catch (final IOException | InterruptedException e) {
                            // The client closed the channel.
                        }
                    }, "sftp-test-endless");
                    writer.setDaemon(true);
                    writer.start();
                    return;
                }
                // The server accepts the request at once. The answer comes after the delay, from a thread of its own.
                final long delay = delayedCommand == null || delayedCommand.equals(text) ? replyDelayMillis : 0;
                if (delay > 0) {
                    held.add(text);
                }
                final PrintStream print = new PrintStream(out, true, "UTF-8");
                final ExitCallback exit = callback;
                final Thread answer = new Thread(() -> {
                    sleep(delay);
                    if (text.equals("id -u")) {
                        print.println(uid);
                    } else if (text.equals("id -G")) {
                        print.println(groups);
                    } else {
                        exit.onExit(127);
                        return;
                    }
                    exit.onExit(0);
                }, "sftp-test-answer");
                answer.setDaemon(true);
                answer.start();
            }

            @Override
            public void destroy() {
                // Nothing to free.
            }
        };
    }

    // Copies until the end of the input, then ends the command when a callback is given.
    private static void pump(final InputStream from, final OutputStream to, final ExitCallback callback) {
        final Thread thread = new Thread(() -> {
            final byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = from.read(buffer)) >= 0) {
                    to.write(buffer, 0, read);
                    to.flush();
                }
            } catch (final IOException e) {
                // The other side ended.
            } finally {
                try {
                    to.close();
                } catch (final IOException e) {
                    // Nothing to do.
                }
                if (callback != null) {
                    callback.onExit(0);
                }
            }
        }, "sftp-test-nc");
        thread.setDaemon(true);
        thread.start();
    }
}
