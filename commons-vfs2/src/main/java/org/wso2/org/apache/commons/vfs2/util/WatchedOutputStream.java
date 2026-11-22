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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An output stream whose writes the watchdog can see.
 * <p>
 * It cuts each write into slices and marks the time while one slice is inside the stream below. A flush is marked
 * the same way. The mark is zero between two calls, so a slow caller is never taken for a stalled write. One thread
 * at a time writes to it.
 * </p>
 * <p>
 * A stream that has no socket of its own also marks its close, because the stream below can send its buffered bytes
 * there. That close has a mark of its own, so another thread may close the stream while a send is inside it. The send
 * keeps its mark and its watch until it ends. One call owns the close, and a send that comes after it is refused.
 * </p>
 */
public class WatchedOutputStream extends OutputStream {

    /**
     * The most bytes of one slice.
     */
    public static final int SLICE_BYTES = 16384;

    private final SocketGroup group;

    private final long stallTimeoutMillis;

    private final WatchedSocket socket;

    private final String host;

    // Set by the first close of a stream that has no socket of its own. That call owns the close of the stream below.
    private final AtomicBoolean closing = new AtomicBoolean();

    private volatile OutputStream out;

    // The start of the send in progress: one slice of a write, or a flush.
    private volatile long writeSince;

    // The start of the close in progress, of a stream that has no socket of its own.
    private volatile long closeSince;

    private volatile long bytesWritten;

    // True when the socket is closed, or when the close of the stream below has ended.
    private volatile boolean closed;

    /**
     * Constructs a watched stream on a write path that has no socket of its own. The caller registers it with
     * {@link ProgressWatchdog#register(WatchedOutputStream)}.
     *
     * @param out the stream below.
     * @param group the group that is aborted when a write stalls.
     * @param stallTimeoutMillis the stall timeout in milliseconds. Zero means that the stream is not watched.
     * @param host the host for the log line of the watchdog, with no credentials.
     */
    public WatchedOutputStream(final OutputStream out, final SocketGroup group, final long stallTimeoutMillis,
            final String host) {
        this.out = out;
        this.group = group;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.socket = null;
        this.host = host;
    }

    WatchedOutputStream(final WatchedSocket socket, final SocketGroup group, final long stallTimeoutMillis) {
        this.group = group;
        this.stallTimeoutMillis = stallTimeoutMillis;
        this.socket = socket;
        this.host = null;
    }

    // The socket gives its real stream when it is connected.
    void bind(final OutputStream real) {
        out = real;
    }

    @Override
    public void write(final int value) throws IOException {
        write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int length) throws IOException {
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        int position = offset;
        int left = length;
        while (left > 0) {
            final int slice = Math.min(left, SLICE_BYTES);
            final long start = begin();
            try {
                out.write(buffer, position, slice);
            } catch (final IOException e) {
                throw stalledOrSame(e, start);
            } finally {
                end();
            }
            // The bytes are added after the mark is cleared. So the watcher never takes the bytes of a send for
            // bytes that were there before it.
            bytesWritten += slice;
            position += slice;
            left -= slice;
        }
    }

    @Override
    public void flush() throws IOException {
        final long start = begin();
        try {
            out.flush();
        } catch (final IOException e) {
            throw stalledOrSame(e, start);
        } finally {
            end();
        }
    }

    // The mark is set before the close is checked, and a close sets its flag before it reads the mark. So a close at
    // the same moment sees this mark and leaves the watch, or this call sees the close and stays out. No send enters
    // the stream below without a watch.
    private long begin() throws IOException {
        if (closing.get()) {
            throw new IOException("The stream is closed");
        }
        final long start = ProgressWatchdog.now();
        writeSince = start;
        if (closing.get()) {
            end();
            throw new IOException("The stream is closed");
        }
        return start;
    }

    // The mark is cleared before the close is checked, and a close sets its flag before it reads the mark. So the
    // close or this call takes the stream out of the watchdog, whichever ends last.
    private void end() {
        writeSince = 0;
        if (closed) {
            ProgressWatchdog.unregister(this);
        }
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            // The close of a raw socket sends nothing and does not wait. It also ends a write that is blocked in the
            // socket, and the socket takes this stream out of the watchdog.
            socket.close();
            return;
        }
        if (!closing.compareAndSet(false, true)) {
            // Another call owns the close.
            return;
        }
        // The stream below can send its buffered bytes in its close. So the close is watched until it ends, with its
        // own mark. The mark of a send that is still inside the stream below is not touched.
        final long start = ProgressWatchdog.now();
        closeSince = start;
        try {
            out.close();
        } catch (final IOException e) {
            throw stalledOrSame(e, start);
        } finally {
            closeSince = 0;
            closed = true;
            if (writeSince == 0) {
                ProgressWatchdog.unregister(this);
            }
        }
    }

    // The reason is the one of the abort that was first. The time is the time of the call that fails here, which can
    // be a later call than the one that the watchdog saw.
    private IOException stalledOrSame(final IOException error, final long start) {
        if (group.isAborted()) {
            return new StalledTransferException(group.reason(),
                    TimeUnit.NANOSECONDS.toMillis(ProgressWatchdog.now() - start), bytesWritten, error);
        }
        return error;
    }

    /**
     * Gets the time at which the send in progress began. A send is one slice of a write, or a flush.
     *
     * @return the time on the clock of the watchdog, or zero when no send is in progress.
     */
    public long getWriteSince() {
        return writeSince;
    }

    /**
     * Gets the bytes that were written so far.
     *
     * @return the byte count.
     */
    public long getBytesWritten() {
        return bytesWritten;
    }

    /**
     * Gets the stall timeout.
     *
     * @return the stall timeout in milliseconds.
     */
    public long getStallTimeoutMillis() {
        return stallTimeoutMillis;
    }

    /**
     * Gets the group that is aborted when a write stalls.
     *
     * @return the group.
     */
    public SocketGroup getGroup() {
        return group;
    }

    // The start of the close in progress, or zero.
    long closeSince() {
        return closeSince;
    }

    // True when nothing is left to watch: the socket is closed, or the close of the stream below has ended and no
    // send is inside it.
    boolean isDone() {
        if (socket != null) {
            return closed || socket.isClosed();
        }
        return closed && writeSince == 0;
    }

    void markClosed() {
        closed = true;
    }

    // The host as it was given, with no lookup, because the watcher must not wait on a name server.
    String host() {
        if (socket == null) {
            return host;
        }
        final SocketAddress remote = socket.getRemoteSocketAddress();
        return remote instanceof InetSocketAddress ? ((InetSocketAddress) remote).getHostString()
                : "a host that is not connected yet";
    }
}
