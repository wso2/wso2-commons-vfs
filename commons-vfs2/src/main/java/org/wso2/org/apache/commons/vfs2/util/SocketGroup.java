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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The raw sockets of one client, with one call that closes them all.
 * <p>
 * An abort sends no command and waits for no reply. It takes no lock and writes no log line, so any thread may call
 * it and it waits on nothing. A group belongs to one client and is not used again after an abort.
 * </p>
 */
public class SocketGroup {

    private static final String NO_REASON = "The connection was aborted";

    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    private final Set<ServerSocket> listeners = ConcurrentHashMap.newKeySet();

    private final Set<SocketGroup> children = ConcurrentHashMap.newKeySet();

    private final AtomicReference<String> abortReason = new AtomicReference<>();

    /**
     * Adds a socket. A socket that is added to an aborted group is closed at once.
     *
     * @param socket the socket, before it connects.
     */
    public void add(final Socket socket) {
        sockets.add(socket);
        // The abort is checked after the add. So an abort at the same moment closes the socket here or in its own walk.
        if (isAborted()) {
            close(socket);
            sockets.remove(socket);
        }
        changed();
    }

    /**
     * Removes a socket. A socket that is not in the group changes nothing.
     *
     * @param socket the socket.
     */
    public void remove(final Socket socket) {
        if (sockets.remove(socket)) {
            changed();
        }
    }

    /**
     * Adds a listening socket, before it can wait for a connection. A listener that is added to an aborted group is
     * closed at once.
     *
     * @param listener the listening socket.
     */
    public void addListener(final ServerSocket listener) {
        listeners.add(listener);
        if (isAborted()) {
            close(listener);
            listeners.remove(listener);
        }
        changed();
    }

    /**
     * Removes a listening socket. A listener that is not in the group changes nothing.
     *
     * @param listener the listening socket.
     */
    public void removeListener(final ServerSocket listener) {
        if (listeners.remove(listener)) {
            changed();
        }
    }

    /**
     * Adds a child group. A child that is added to an aborted group is aborted at once.
     *
     * @param child the group of a connection that this client owns, before that connection connects.
     */
    public void addChild(final SocketGroup child) {
        children.add(child);
        final String reason = abortReason.get();
        if (reason != null) {
            child.abort(reason);
            children.remove(child);
        }
        changed();
    }

    /**
     * Removes a child group. A child that is not in the group changes nothing.
     *
     * @param child the child group.
     */
    public void removeChild(final SocketGroup child) {
        if (children.remove(child)) {
            changed();
        }
    }

    /**
     * Closes every socket and every listening socket of the group and aborts every child group. The group holds
     * nothing when the call returns. A second call, also from another thread at the same moment, closes what is
     * still there.
     *
     * @param reason why the group is aborted. The first reason is kept.
     */
    public void abort(final String reason) {
        claim(reason);
        final String kept = abortReason.get();
        for (final Socket socket : sockets) {
            close(socket);
            sockets.remove(socket);
        }
        for (final ServerSocket listener : listeners) {
            close(listener);
            listeners.remove(listener);
        }
        for (final SocketGroup child : children) {
            child.abort(kept);
            children.remove(child);
        }
        changed();
    }

    // Sets the reason when the group has none, and closes nothing. It tells the caller whether its reason was the
    // first. The watchdog asks, because only the abort that was first gets its warning.
    boolean claim(final String reason) {
        return abortReason.compareAndSet(null, reason == null ? NO_REASON : reason);
    }

    /**
     * Tells whether the group was aborted.
     *
     * @return true after an abort.
     */
    public boolean isAborted() {
        return abortReason.get() != null;
    }

    /**
     * Gets the reason of the abort.
     *
     * @return the reason, or null when the group was not aborted.
     */
    public String reason() {
        return abortReason.get();
    }

    /**
     * Tells whether the group holds no socket, no listening socket and no child group.
     *
     * @return true when nothing is registered.
     */
    public boolean isEmpty() {
        return sockets.isEmpty() && listeners.isEmpty() && children.isEmpty();
    }

    /**
     * Gets the sockets of the group as they are now. A listening socket is not one of them.
     *
     * @return a copy of the sockets.
     */
    public List<Socket> getSockets() {
        return new ArrayList<>(sockets);
    }

    /**
     * Called after a socket, a listening socket or a child was added or removed and after an abort. The owner of the
     * group overrides it to keep or drop the group. It must not block.
     */
    protected void changed() {
        // Nothing for a group with no owner.
    }

    // An error of the close is dropped. The socket is given up in any case, the walk must reach the next socket, and
    // an abort must not wait on a log.
    private static void close(final Closeable socket) {
        try {
            socket.close();
        } catch (final IOException | RuntimeException e) {
            // Nothing to do for a socket that is given up.
        }
    }
}
