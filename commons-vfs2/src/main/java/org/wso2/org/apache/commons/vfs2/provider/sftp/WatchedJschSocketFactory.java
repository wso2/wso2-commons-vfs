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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocketFactory;

import com.jcraft.jsch.SocketFactory;

/**
 * Makes the sockets of one JSch session. Each socket is a watched socket in the group of that session, and JSch
 * writes through the watched stream of the socket.
 * <p>
 * JSch bounds the TCP connect only when it makes the socket itself. So this factory connects each socket with the
 * connect timeout. JSch also gives this factory to an HTTP or SOCKS5 proxy, so the socket to the proxy is in the group
 * too.
 * </p>
 */
public class WatchedJschSocketFactory implements SocketFactory {

    private final WatchedSocketFactory sockets;

    private final SocketGroup group;

    private final int connectTimeoutMillis;

    private final long stallTimeoutMillis;

    // The end of the setup on the clock of System.nanoTime, or 0 for none, and the thread that sets the session up.
    // While the setup lasts, each read of that thread waits at most the time that is left.
    private final long setupDeadline;

    private final Thread setupThread;

    private volatile boolean setupEnded;

    private final List<SetupInput> inputs = new CopyOnWriteArrayList<>();

    /**
     * Constructs a factory.
     *
     * @param group the group of the session.
     * @param connectTimeoutMillis the connect timeout in milliseconds. Zero means no bound.
     * @param stallTimeoutMillis the write stall timeout in milliseconds. Zero turns the watchdog off.
     */
    public WatchedJschSocketFactory(final SocketGroup group, final int connectTimeoutMillis,
            final long stallTimeoutMillis) {
        this(group, connectTimeoutMillis, stallTimeoutMillis, 0);
    }

    /**
     * Constructs a factory whose setup ends at a deadline. The thread that constructs it is the thread of the setup.
     *
     * @param setupDeadline the deadline on the clock of {@link System#nanoTime()}, or 0 for none.
     */
    WatchedJschSocketFactory(final SocketGroup group, final int connectTimeoutMillis, final long stallTimeoutMillis,
            final long setupDeadline) {
        this.sockets = new WatchedSocketFactory(group, connectTimeoutMillis, stallTimeoutMillis);
        this.group = group;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.setupDeadline = setupDeadline;
        this.setupThread = Thread.currentThread();
    }

    /**
     * Makes a socket in the group before it connects, and connects it with the connect timeout. A socket whose
     * connect fails is closed and leaves the group.
     */
    @Override
    public Socket createSocket(final String host, final int port) throws IOException {
        if (setupDeadline != 0 && !setupEnded) {
            final int bounded = SftpClientFactory.setupBound(connectTimeoutMillis, setupDeadline);
            return new WatchedSocketFactory(group, bounded, stallTimeoutMillis).createSocket(host, port);
        }
        return sockets.createSocket(host, port);
    }

    @Override
    public InputStream getInputStream(final Socket socket) throws IOException {
        if (setupDeadline == 0) {
            return socket.getInputStream();
        }
        final SetupInput input = new SetupInput(socket);
        inputs.add(input);
        return input;
    }

    /**
     * Ends the setup. Later reads keep the read timeout that JSch set for the session.
     */
    void endSetup() {
        setupEnded = true;
        for (final SetupInput input : inputs) {
            input.restore();
        }
    }

    // The input of a socket during the setup. Before each read of the thread of the setup it cuts the read timeout
    // that JSch asked for to the time left. JSch sets the read timeout of the session at the end of the login.
    private final class SetupInput extends FilterInputStream {

        private final Socket socket;

        private int requested = -1;

        private int applied = -1;

        SetupInput(final Socket socket) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
        }

        @Override
        public int read() throws IOException {
            bound();
            return super.read();
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            bound();
            return super.read(buffer, offset, length);
        }

        private void bound() throws IOException {
            if (setupEnded || Thread.currentThread() != setupThread) {
                return;
            }
            final int left = SftpClientFactory.setupBound(0, setupDeadline);
            final int current = socket.getSoTimeout();
            if (current != applied) {
                requested = current;
            }
            applied = requested == 0 ? left : Math.min(requested, left);
            socket.setSoTimeout(applied);
        }

        // Gives back the read timeout that JSch asked for, unless JSch set another one since.
        void restore() {
            try {
                if (applied >= 0 && requested >= 0 && socket.getSoTimeout() == applied) {
                    socket.setSoTimeout(requested);
                }
            } catch (final SocketException e) {
                // The socket is closed, so no read waits on it.
            }
        }
    }

    @Override
    public OutputStream getOutputStream(final Socket socket) throws IOException {
        return socket.getOutputStream();
    }
}
