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
import java.net.ServerSocket;

import javax.net.ServerSocketFactory;

/**
 * Makes the server sockets of one client. Each one accepts into a {@link WatchedSocket} in the group of that client.
 */
public class WatchedServerSocketFactory extends ServerSocketFactory {

    private static final int DEFAULT_BACKLOG = 50;

    private final SocketGroup group;

    private final long stallTimeoutMillis;

    /**
     * Constructs a factory.
     *
     * @param group the group of the client.
     * @param stallTimeoutMillis the write stall timeout of the accepted sockets, in milliseconds.
     */
    public WatchedServerSocketFactory(final SocketGroup group, final long stallTimeoutMillis) {
        this.group = group;
        this.stallTimeoutMillis = stallTimeoutMillis;
    }

    @Override
    public ServerSocket createServerSocket() throws IOException {
        return new WatchedServerSocket(group, stallTimeoutMillis);
    }

    @Override
    public ServerSocket createServerSocket(final int port) throws IOException {
        return new WatchedServerSocket(group, stallTimeoutMillis, port, DEFAULT_BACKLOG, null);
    }

    @Override
    public ServerSocket createServerSocket(final int port, final int backlog) throws IOException {
        return new WatchedServerSocket(group, stallTimeoutMillis, port, backlog, null);
    }

    @Override
    public ServerSocket createServerSocket(final int port, final int backlog, final InetAddress address)
            throws IOException {
        return new WatchedServerSocket(group, stallTimeoutMillis, port, backlog, address);
    }
}
