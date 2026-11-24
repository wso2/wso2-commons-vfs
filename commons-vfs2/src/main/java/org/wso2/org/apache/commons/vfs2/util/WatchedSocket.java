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
import java.io.OutputStream;
import java.net.Proxy;
import java.net.Socket;

/**
 * A plain socket whose writes the watchdog can see and whose group can close it.
 * <p>
 * It is in its group before it connects. It takes itself out of the group and of the watchdog when it is closed.
 * </p>
 * <p>
 * Its native socket exists before the group knows it. The JDK makes the native socket of a plain socket at the first
 * use only, and up to JDK 17 a close before that closes nothing. A connect or a bind that had already passed its
 * closed check would then make the native socket and connect behind the abort. So the close of the group always
 * finds a native socket to close.
 * </p>
 */
public class WatchedSocket extends Socket {

    private final SocketGroup group;

    private final WatchedOutputStream watched;

    /**
     * Constructs a socket that is not connected and registers it.
     *
     * @param group the group of the client that owns the socket.
     * @param stallTimeoutMillis the write stall timeout in milliseconds. Zero means that the watchdog does not
     *        watch the socket. The group still closes it.
     * @throws IOException when the native socket can not be made.
     */
    public WatchedSocket(final SocketGroup group, final long stallTimeoutMillis) throws IOException {
        this(group, stallTimeoutMillis, true);
    }

    /**
     * Constructs a socket that connects through a proxy, and registers it.
     *
     * @param group the group of the client that owns the socket.
     * @param stallTimeoutMillis the write stall timeout in milliseconds.
     * @param proxy the proxy.
     * @throws IOException when the native socket can not be made.
     */
    public WatchedSocket(final SocketGroup group, final long stallTimeoutMillis, final Proxy proxy)
            throws IOException {
        super(proxy);
        this.group = group;
        this.watched = new WatchedOutputStream(this, group, stallTimeoutMillis);
        open();
    }

    // A server socket passes false. Its accept puts the native socket in and registers after that.
    WatchedSocket(final SocketGroup group, final long stallTimeoutMillis, final boolean open) throws IOException {
        this.group = group;
        this.watched = new WatchedOutputStream(this, group, stallTimeoutMillis);
        if (open) {
            open();
        }
    }

    private void open() throws IOException {
        try {
            // The read of an option makes the native socket and changes nothing. No other thread knows this object
            // yet, so no close can come in between.
            super.getSoTimeout();
            register();
        } catch (final IOException | RuntimeException | Error e) {
            // The close gives the native socket back and takes the socket out of the watchdog and of the group.
            try {
                close();
            } catch (final IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    // The group checks the abort after the add. An aborted group closes this socket at once.
    final void register() {
        ProgressWatchdog.register(watched);
        group.add(this);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        watched.bind(super.getOutputStream());
        return watched;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            watched.markClosed();
            ProgressWatchdog.unregister(watched);
            group.remove(this);
        }
    }
}
