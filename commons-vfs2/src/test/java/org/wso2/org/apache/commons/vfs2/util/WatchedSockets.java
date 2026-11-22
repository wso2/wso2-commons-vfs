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
package org.wso2.org.apache.commons.vfs2.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback peers and blocked writers for the tests of the watched sockets. It owns every socket that it opens or
 * accepts, from the moment the socket exists until {@link #close()}.
 */
public final class WatchedSockets implements Closeable {

    private static final int SMALL_BUFFER = 8192;

    private static final long END_MILLIS = 5000;

    // A listener is closed first, so that its acceptor ends and adds no socket after that.
    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();

    private final List<Thread> acceptors = new CopyOnWriteArrayList<>();

    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    private final List<Thread> writers = new CopyOnWriteArrayList<>();

    private volatile boolean closing;

    // A test sets it to hold an acceptor between its accept and the moment the fixture owns the socket.
    volatile Runnable beforeOwn;

    /**
     * A thread that writes until it fails.
     */
    public static final class Writer {

        private final AtomicReference<IOException> error = new AtomicReference<>();

        private final Thread thread;

        private volatile long failedAtNanos;

        private volatile long blockedSince;

        Writer(final Socket socket) {
            thread = new Thread(() -> {
                final byte[] block = new byte[65536];
                try {
                    final OutputStream out = socket.getOutputStream();
                    while (true) {
                        out.write(block);
                    }
                } catch (final IOException e) {
                    // On the clock of the watchdog, so that a test can compare it with a write mark.
                    failedAtNanos = ProgressWatchdog.now();
                    error.set(e);
                }
            }, "test-writer");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Waits for the error of the writer.
         *
         * @param millis how long to wait.
         * @return the error.
         * @throws InterruptedException when the test is interrupted.
         */
        public IOException awaitError(final long millis) throws InterruptedException {
            thread.join(millis);
            if (error.get() == null) {
                throw new AssertionError("The writer did not fail within " + millis + " ms");
            }
            return error.get();
        }

        public boolean hasFailed() {
            return error.get() != null;
        }

        public long failedAtNanos() {
            return failedAtNanos;
        }

        /**
         * Gets the start of the socket write in which the writer was seen blocked, read while it was blocked.
         *
         * @return the write mark of that slice.
         */
        public long blockedSince() {
            return blockedSince;
        }
    }

    /**
     * Opens a server that accepts and never reads.
     *
     * @return the server socket.
     * @throws IOException when it can not be opened.
     */
    public ServerSocket silentServer() throws IOException {
        final ServerSocket server = new ServerSocket();
        servers.add(server);
        server.setReceiveBufferSize(SMALL_BUFFER);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        final Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    final Socket accepted = server.accept();
                    try {
                        final Runnable pause = beforeOwn;
                        if (pause != null) {
                            pause.run();
                        }
                    } finally {
                        own(accepted);
                    }
                }
            } catch (final IOException e) {
                // The server was closed.
            }
        }, "test-acceptor");
        acceptor.setDaemon(true);
        acceptors.add(acceptor);
        acceptor.start();
        return server;
    }

    /**
     * Connects a client that never reads. The fixture owns it before it connects.
     *
     * @param port the port of a listening socket on the loopback address.
     * @return the connected client.
     * @throws IOException when it can not connect.
     */
    public Socket silentClient(final int port) throws IOException {
        final Socket client = new Socket();
        own(client);
        client.setReceiveBufferSize(SMALL_BUFFER);
        client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
        return client;
    }

    /**
     * Starts a writer on a connected socket and waits until it is blocked inside one socket write. The same write
     * mark was then read twice, with no byte written in between.
     *
     * @param socket a connected watched socket.
     * @return the writer.
     * @throws Exception when the writer does not block or fails before the test saw it blocked.
     */
    public Writer blockedWriter(final Socket socket) throws Exception {
        own(socket);
        final WatchedOutputStream out = (WatchedOutputStream) socket.getOutputStream();
        final Writer writer = new Writer(socket);
        writers.add(writer.thread);
        final long end = System.nanoTime() + 20_000_000_000L;
        long bytes = -1;
        long since = 0;
        while (System.nanoTime() < end) {
            final long nowBytes = out.getBytesWritten();
            final long nowSince = out.getWriteSince();
            if (nowSince != 0 && nowSince == since && nowBytes == bytes) {
                writer.blockedSince = since;
                return writer;
            }
            if (writer.hasFailed()) {
                throw new IllegalStateException("The writer failed before the test saw it blocked", writer.error.get());
            }
            bytes = nowBytes;
            since = nowSince;
            Thread.sleep(20);
        }
        throw new IllegalStateException("The writer did not block");
    }

    /**
     * Sets a small send buffer, so that a write blocks soon.
     *
     * @param socket a socket that is not connected yet.
     * @return the same socket.
     * @throws IOException when the option can not be set.
     */
    public static Socket small(final Socket socket) throws IOException {
        socket.setSendBufferSize(SMALL_BUFFER);
        return socket;
    }

    /**
     * Gets the sockets that the fixture owns now.
     *
     * @return a copy of the list.
     */
    public List<Socket> owned() {
        return new ArrayList<>(sockets);
    }

    /**
     * Closes every listener, waits for the acceptors, and closes every socket after that. So a socket that was
     * accepted while the close began is closed too.
     *
     * @throws IllegalStateException when a thread or a socket of the fixture is left.
     */
    @Override
    public void close() {
        closing = true;
        final List<String> left = new ArrayList<>();
        for (final ServerSocket server : servers) {
            closeQuietly(server);
        }
        for (final Thread acceptor : acceptors) {
            if (!ended(acceptor)) {
                left.add("an acceptor");
            }
        }
        for (final Socket socket : sockets) {
            closeQuietly(socket);
        }
        for (final Thread writer : writers) {
            if (!ended(writer)) {
                left.add("a writer");
            }
        }
        for (final Socket socket : sockets) {
            if (!socket.isClosed()) {
                left.add("an open socket");
            }
        }
        if (!left.isEmpty()) {
            throw new IllegalStateException("The fixture left " + left);
        }
    }

    private void own(final Socket socket) {
        sockets.add(socket);
        // A closer that already passed this socket leaves its close to the publishing thread.
        if (closing) {
            closeQuietly(socket);
        }
    }

    private static boolean ended(final Thread thread) {
        try {
            thread.join(END_MILLIS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static void closeQuietly(final Closeable one) {
        try {
            one.close();
        } catch (final IOException e) {
            // Best effort in a test.
        }
    }
}
