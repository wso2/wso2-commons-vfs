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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP relay in front of a port, for the faults of the tests. It can stop moving bytes while it keeps every
 * connection open, which makes a peer that is silent and still connected. It can hold a new connection before it
 * reaches the target, cut every connection, and count the connections it holds.
 */
final class TestRelay implements Closeable {

    private final ServerSocket listener;

    private final int targetPort;

    private final List<Socket> sockets = new CopyOnWriteArrayList<>();

    private final AtomicInteger accepted = new AtomicInteger();

    private final Object gate = new Object();

    private volatile boolean paused;

    private volatile boolean holdNew;

    private volatile boolean closed;

    TestRelay(final int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final Thread acceptor = new Thread(this::accept, "test-relay-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int getPort() {
        return listener.getLocalPort();
    }

    // Stops moving bytes in both directions. Every connection stays open.
    void pause() {
        paused = true;
    }

    void resume() {
        paused = false;
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    // A new connection is accepted and then held: it reaches the target only after the release.
    void holdNewConnections(final boolean hold) {
        holdNew = hold;
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    // Closes every connection that the relay holds. New connections are still taken.
    void cutAll() {
        for (final Socket socket : sockets) {
            closeQuietly(socket);
        }
        sockets.clear();
    }

    int getAccepted() {
        return accepted.get();
    }

    // The client connections that are open now.
    int getOpenClients() {
        int count = 0;
        for (final Socket socket : sockets) {
            if (socket.getLocalPort() == getPort() && !socket.isClosed()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(listener);
        cutAll();
        resume();
    }

    private void accept() {
        while (!closed) {
            final Socket client;
            try {
                client = listener.accept();
            } catch (final IOException e) {
                return;
            }
            accepted.incrementAndGet();
            sockets.add(client);
            final Thread connector = new Thread(() -> connect(client), "test-relay-connect");
            connector.setDaemon(true);
            connector.start();
        }
    }

    private void connect(final Socket client) {
        try {
            synchronized (gate) {
                while (holdNew && !closed) {
                    gate.wait();
                }
            }
            if (client.isClosed()) {
                return;
            }
            final Socket target = new Socket(InetAddress.getLoopbackAddress(), targetPort);
            sockets.add(target);
            pump(client, target);
            pump(target, client);
        } catch (final IOException | InterruptedException e) {
            closeQuietly(client);
        }
    }

    private void pump(final Socket from, final Socket to) throws IOException {
        final InputStream in = from.getInputStream();
        final OutputStream out = to.getOutputStream();
        final Thread thread = new Thread(() -> {
            final byte[] buffer = new byte[4096];
            try {
                while (true) {
                    waitWhilePaused();
                    final int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    waitWhilePaused();
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (final IOException | InterruptedException e) {
                // One side ended.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "test-relay-pump");
        thread.setDaemon(true);
        thread.start();
    }

    private void waitWhilePaused() throws InterruptedException, SocketException {
        synchronized (gate) {
            while (paused && !closed) {
                gate.wait();
            }
        }
        if (closed) {
            throw new SocketException("The relay is closed");
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException e) {
            // Nothing to do.
        }
    }
}
