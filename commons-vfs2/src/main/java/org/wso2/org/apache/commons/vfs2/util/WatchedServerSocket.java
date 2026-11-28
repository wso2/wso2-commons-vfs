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
package org.wso2.org.apache.commons.vfs2.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * A server socket that accepts into a {@link WatchedSocket}. It serves the data connection of FTP active mode,
 * which the server opens.
 * <p>
 * It is in the group of its client from its start, so an abort also ends a thread that waits in
 * {@link #accept()}. It takes itself out of the group when it is closed. Its native socket exists before the group
 * knows it, for the reason that {@link WatchedSocket} gives. The group knows it before its port listens, so no peer
 * connects to a port that an abort has already passed.
 * </p>
 */
public class WatchedServerSocket extends ServerSocket {

    private static final int MAX_PORT = 0xFFFF;

    private final SocketGroup group;

    private final long stallTimeoutMillis;

    /**
     * Constructs a server socket that is not bound.
     *
     * @param group the group of the client.
     * @param stallTimeoutMillis the write stall timeout of the accepted sockets, in milliseconds.
     * @throws IOException when the socket can not be opened.
     */
    public WatchedServerSocket(final SocketGroup group, final long stallTimeoutMillis) throws IOException {
        this.group = group;
        this.stallTimeoutMillis = stallTimeoutMillis;
        enter();
    }

    /**
     * Constructs a bound server socket. The socket enters the group first and is bound after. A socket of a group
     * that was aborted by then is returned closed, and its port was never bound.
     *
     * @param group the group of the client.
     * @param stallTimeoutMillis the write stall timeout of the accepted sockets, in milliseconds.
     * @param port the local port, or zero for any free port.
     * @param backlog the length of the queue of incoming connections. A value under one gives the default of the
     *            JDK.
     * @param address the local address, or null for any.
     * @throws IOException when the socket can not be opened or bound.
     */
    public WatchedServerSocket(final SocketGroup group, final long stallTimeoutMillis, final int port,
            final int backlog, final InetAddress address) throws IOException {
        this.group = group;
        this.stallTimeoutMillis = stallTimeoutMillis;
        // The constructor of the JDK that binds is not used, because it lets the port listen before the group knows
        // the socket. Its check of the port is done here, with its text.
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalArgumentException("Port value out of range: " + port);
        }
        enter();
        if (isClosed()) {
            // The group was aborted before the bind.
            return;
        }
        try {
            bind(new InetSocketAddress(address, port), backlog);
        } catch (final IOException | RuntimeException | Error e) {
            closeAfter(e);
            throw e;
        }
    }

    private void enter() throws IOException {
        try {
            // The read of an option makes the native socket and changes nothing. No other thread knows this object
            // yet.
            super.getSoTimeout();
            group.addListener(this);
        } catch (final IOException | RuntimeException | Error e) {
            closeAfter(e);
            throw e;
        }
    }

    // Closes the socket after a start that failed. The caller throws the error of the start.
    private void closeAfter(final Throwable error) {
        try {
            close();
        } catch (final IOException closeError) {
            error.addSuppressed(closeError);
        }
    }

    @Override
    public Socket accept() throws IOException {
        if (isClosed()) {
            throw new SocketException("Socket is closed");
        }
        if (!isBound()) {
            throw new SocketException("Socket is not bound yet");
        }
        // No other thread knows the socket until it is registered. The accept puts the native socket in.
        final WatchedSocket socket = new WatchedSocket(group, stallTimeoutMillis, false);
        try {
            implAccept(socket);
            // The socket enters the group after the accept. An aborted group closes it at once.
            socket.register();
        } catch (final IOException | RuntimeException | Error e) {
            try {
                socket.close();
            } catch (final IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        return socket;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            group.removeListener(this);
        }
    }
}
