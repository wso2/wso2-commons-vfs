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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Makes the sockets of one client. Each socket is a {@link WatchedSocket} in the group of that client.
 * <p>
 * A library that takes a socket factory no longer bounds the connect itself. So a socket that this factory connects
 * gets the connect timeout here.
 * </p>
 */
public class WatchedSocketFactory extends SocketFactory {

    private final SocketGroup group;

    private final int connectTimeoutMillis;

    private final long stallTimeoutMillis;

    private final Proxy proxy;

    /**
     * Constructs a factory.
     *
     * @param group the group of the client.
     * @param connectTimeoutMillis the connect timeout in milliseconds. Zero means no bound.
     * @param stallTimeoutMillis the write stall timeout in milliseconds. Zero turns the watchdog off.
     */
    public WatchedSocketFactory(final SocketGroup group, final int connectTimeoutMillis,
            final long stallTimeoutMillis) {
        this(group, connectTimeoutMillis, stallTimeoutMillis, null);
    }

    /**
     * Constructs a factory whose sockets connect through a proxy.
     *
     * @param group the group of the client.
     * @param connectTimeoutMillis the connect timeout in milliseconds. Zero means no bound.
     * @param stallTimeoutMillis the write stall timeout in milliseconds. Zero turns the watchdog off.
     * @param proxy the proxy, or null for none.
     */
    public WatchedSocketFactory(final SocketGroup group, final int connectTimeoutMillis,
            final long stallTimeoutMillis, final Proxy proxy) {
        this.group = group;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.proxy = proxy;
    }

    @Override
    public Socket createSocket() throws IOException {
        return proxy == null ? new WatchedSocket(group, stallTimeoutMillis)
                : new WatchedSocket(group, stallTimeoutMillis, proxy);
    }

    @Override
    public Socket createSocket(final String host, final int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(final String host, final int port, final InetAddress localHost, final int localPort)
            throws IOException {
        return connect(new InetSocketAddress(host, port), new InetSocketAddress(localHost, localPort));
    }

    @Override
    public Socket createSocket(final InetAddress host, final int port) throws IOException {
        return connect(new InetSocketAddress(host, port), null);
    }

    @Override
    public Socket createSocket(final InetAddress address, final int port, final InetAddress localAddress,
            final int localPort) throws IOException {
        return connect(new InetSocketAddress(address, port), new InetSocketAddress(localAddress, localPort));
    }

    private Socket connect(final SocketAddress remote, final SocketAddress local) throws IOException {
        final Socket socket = createSocket();
        try {
            if (local != null) {
                socket.bind(local);
            }
            socket.connect(remote, connectTimeoutMillis);
            return socket;
        } catch (final IOException | RuntimeException | Error e) {
            // The close takes the socket out of its group.
            try {
                socket.close();
            } catch (final IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
}
