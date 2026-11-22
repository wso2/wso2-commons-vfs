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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.util.ProgressWatchdog;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedOutputStream;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

/**
 * Stream based proxy for JSch.
 *
 * <p>
 * Use a command on the proxy that will forward the SSH stream to the target host and port.
 * </p>
 * <p>
 * The session to the target has no socket of its own here. Its bytes go through a channel of the proxy session. So
 * the input of that channel is read with a bound, and the output is watched by the watchdog. The proxy session has
 * a socket group of its own, which is a child of the group of the session to the target. An abort of that group
 * reaches the raw socket of the proxy session.
 * </p>
 *
 * @since 2.1
 */
public class SftpStreamProxy implements Proxy {
    /**
     * Command format using bash built-in TCP stream.
     */
    public static final String BASH_TCP_COMMAND = "/bin/bash -c 'exec 3<>/dev/tcp/%s/%d; cat <&3 & cat >&3; kill $!";

    /**
     * Command format using netcat command.
     */
    public static final String NETCAT_COMMAND = "nc -q 0 %s %d";

    private volatile ChannelExec channel;

    /**
     * Command pattern to execute on the proxy host.
     * <p>
     * When run, the command output should be forwarded to the target host and port, and its input should be forwarded from the target host and port.
     * </p>
     * <p>
     * The command will be created for each host/port pair by using {@linkplain String#format(String, Object...)} with two objects: the target host name
     * ({@linkplain String}) and the target port ({@linkplain Integer}).
     * </p>
     * <p>
     * Here are two examples (that can be easily used by using the static members of this class):
     * </p>
     * <ul>
     * <li>{@code nc -q 0 %s %d} to use the netcat command ({@linkplain #NETCAT_COMMAND})</li>
     * <li>{@code /bin/bash -c 'exec 3<>/dev/tcp/%s/%d; cat <&3 & cat >&3; kill $!} will use bash built-in TCP stream, which can be useful when there is no
     * netcat available. ({@linkplain #BASH_TCP_COMMAND})</li>
     * </ul>
     */
    private final String commandFormat;

    /**
     * Hostname used to connect to the proxy host.
     */
    private final String proxyHost;

    /**
     * The options for connection.
     */
    private final FileSystemOptions proxyOptions;

    /**
     * The password to be used for connection.
     */
    private final String proxyPassword;

    /**
     * Port used to connect to the proxy host.
     */
    private final int proxyPort;

    /**
     * User name used to connect to the proxy host.
     */
    private final String proxyUser;

    /**
     * The group of the session to the target, which owns the group of the proxy session.
     */
    private final SocketGroup owner;

    /**
     * The read timeout of the session to the target, in milliseconds. Zero means no bound.
     */
    private final int readTimeoutMillis;

    private final long stallTimeoutMillis;

    // The end of the setup of the session to the target on the clock of System.nanoTime, or 0 for none.
    private final long setupDeadline;

    private volatile Session session;

    private volatile SocketGroup group;

    private volatile BoundedInput input;

    private volatile WatchedOutputStream output;

    /**
     * Creates a stream proxy.
     *
     * @param commandFormat A format string that will be used to create the command to execute on the proxy host using
     *            {@linkplain String#format(String, Object...)}. Two parameters are given to the format command, the
     *            target host name (String) and port (Integer).
     *
     * @param proxyUser The proxy user
     * @param proxyPassword The proxy password
     * @param proxyHost The proxy host
     * @param proxyPort The port to connect to on the proxy
     * @param proxyOptions Options used when connecting to the proxy
     * @param owner The socket group of the session to the target, which owns the group of the proxy session
     * @param readTimeoutMillis The read timeout of the session to the target in milliseconds. Zero means no bound
     * @param stallTimeoutMillis The write stall timeout in milliseconds. Zero turns the watchdog off
     */
    public SftpStreamProxy(final String commandFormat, final String proxyUser, final String proxyHost,
            final int proxyPort, final String proxyPassword, final FileSystemOptions proxyOptions,
            final SocketGroup owner, final int readTimeoutMillis, final long stallTimeoutMillis) {
        this(commandFormat, proxyUser, proxyHost, proxyPort, proxyPassword, proxyOptions, owner, readTimeoutMillis,
                stallTimeoutMillis, 0);
    }

    // The same with a deadline at which the setup ends: the proxy session, the channel to the target and the reads
    // until the session to the target is connected. Zero means none.
    SftpStreamProxy(final String commandFormat, final String proxyUser, final String proxyHost, final int proxyPort,
            final String proxyPassword, final FileSystemOptions proxyOptions, final SocketGroup owner,
            final int readTimeoutMillis, final long stallTimeoutMillis, final long setupDeadline) {
        this.setupDeadline = setupDeadline;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.proxyUser = proxyUser;
        this.proxyPassword = proxyPassword;
        this.commandFormat = commandFormat;
        this.proxyOptions = proxyOptions;
        this.owner = owner;
        this.readTimeoutMillis = readTimeoutMillis;
        this.stallTimeoutMillis = stallTimeoutMillis;
    }

    /**
     * Creates a stream proxy with no read bound and no watch of its output, as before the bounds. Its proxy session is
     * a child of a group of its own, which no file system manager knows.
     *
     * @param commandFormat A format string that will be used to create the command to execute on the proxy host using
     *            {@linkplain String#format(String, Object...)}. Two parameters are given to the format command, the
     *            target host name (String) and port (Integer).
     *
     * @param proxyUser The proxy user
     * @param proxyPassword The proxy password
     * @param proxyHost The proxy host
     * @param proxyPort The port to connect to on the proxy
     * @param proxyOptions Options used when connecting to the proxy
     */
    public SftpStreamProxy(final String commandFormat, final String proxyUser, final String proxyHost,
            final int proxyPort, final String proxyPassword, final FileSystemOptions proxyOptions) {
        this(commandFormat, proxyUser, proxyHost, proxyPort, proxyPassword, proxyOptions, new SocketGroup(), 0, 0);
    }

    /**
     * Closes the channel and the proxy session, takes the output out of the watchdog and the group of the proxy
     * session out of the group of its owner. A second call changes nothing.
     */
    @Override
    public void close() {
        final WatchedOutputStream watched = output;
        if (watched != null) {
            ProgressWatchdog.unregister(watched);
        }
        final ChannelExec exec = channel;
        if (exec != null) {
            exec.disconnect();
        }
        final Session proxySession = session;
        if (proxySession != null) {
            proxySession.disconnect();
        }
        final SocketGroup child = group;
        if (child != null) {
            owner.removeChild(child);
        }
    }

    /**
     * Connects the proxy session and opens the channel to the target. The input of the channel is bounded by the
     * given timeout until the session to the target is connected.
     */
    @Override
    public void connect(final SocketFactory socketFactory, final String targetHost, final int targetPort,
            final int timeout) throws Exception {
        final SocketGroup child = new SocketGroup();
        group = child;
        // The child is in the group of the owner before any socket of it connects. An owner that was aborted
        // aborts it here, and its first socket is then closed at once.
        owner.addChild(child);
        try {
            session = SftpClientFactory.createConnection(proxyHost, proxyPort, proxyUser.toCharArray(),
                    proxyPassword.toCharArray(), proxyOptions, child, setupDeadline);
            final ChannelExec exec = (ChannelExec) session.openChannel("exec");
            channel = exec;
            exec.setCommand(String.format(commandFormat, targetHost, targetPort));
            final BoundedInput bounded = new BoundedInput(exec.getInputStream(), () -> hasEnded(exec), timeout);
            bounded.setSetupDeadline(setupDeadline);
            input = bounded;
            final WatchedOutputStream watched = new WatchedOutputStream(exec.getOutputStream(), child,
                    stallTimeoutMillis, proxyHost);
            output = watched;
            ProgressWatchdog.register(watched);
            if (setupDeadline != 0 && Thread.currentThread().isInterrupted()) {
                // JSch can take the interrupt in its waits for the channel. A setup with a deadline that was
                // interrupted ends here.
                throw new InterruptedIOException("The setup of the SFTP session through the stream proxy was"
                        + " interrupted.");
            }
            exec.connect(channelWait(timeout, setupDeadline));
        } catch (final Exception | Error e) {
            // The cleanup ends only what this connect made. The thread keeps its interrupt.
            final boolean interrupted = Thread.currentThread().isInterrupted();
            child.abort("The connect through the SFTP stream proxy " + proxyHost + " failed");
            close();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    // JSch waits once with this value for the open of the channel and once more for the reply to the request. In a
    // setup with a deadline each wait gets at most half of the time that is left, so both end by the deadline. A
    // shorter timeout of the endpoint stays.
    static int channelWait(final int timeout, final long setupDeadline) throws SocketTimeoutException {
        if (setupDeadline == 0) {
            return timeout;
        }
        final int half = Math.max(1, SftpClientFactory.setupBound(0, setupDeadline) / 2);
        return timeout == 0 ? half : Math.min(timeout, half);
    }

    /**
     * Switches the bound of the input from the connect timeout to the read timeout of the session to the target.
     * The factory calls it when that session is connected.
     */
    void sessionConnected() {
        final BoundedInput bounded = input;
        if (bounded != null) {
            bounded.setSetupDeadline(0);
            bounded.setTimeout(readTimeoutMillis);
        }
    }

    // The group of the proxy session, for the tests of this package.
    SocketGroup getGroup() {
        return group;
    }

    // The end of the channel, its close or the end of the proxy session. Local state only.
    private static boolean hasEnded(final Channel exec) {
        if (exec.isEOF() || exec.isClosed()) {
            return true;
        }
        try {
            return !exec.getSession().isConnected();
        } catch (final JSchException e) {
            return true;
        }
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public Socket getSocket() {
        return null;
    }

    /**
     * The input of the channel to the target, read with a bound.
     * <p>
     * A read that finds no byte waits for one until the timeout, on the monotonic clock, and then throws
     * {@link SocketTimeoutException}, as a socket with a read timeout does. JSch counts that timeout against the keep
     * alive count of the session to the target. A read returns the bytes that are there at once, and never more than
     * are there. After the end of the channel, its close or the end of the proxy session, it gives the bytes that are
     * left and then the end of the stream. A timeout of zero means no bound.
     * </p>
     * <p>
     * JSch writes into the pipe on the thread of the proxy session and this stream is its only reader. It takes no
     * byte ahead and makes no thread. It looks at the pipe in steps of a few milliseconds.
     * </p>
     * <p>
     * The pipe of a JSch channel holds 32 KB. A writer that finds it full waits up to 1 s, and only a reader that
     * waits on the empty pipe wakes it. This stream never waits on the pipe, so it wakes the writer after each read.
     * Without that the session moves about 32 KB per second.
     * </p>
     */
    static final class BoundedInput extends InputStream {

        private static final long POLL_MILLIS = 5;

        private final InputStream pipe;

        private final BooleanSupplier ended;

        private volatile int timeoutMillis;

        private volatile long setupDeadline;

        private volatile boolean closed;

        BoundedInput(final InputStream pipe, final BooleanSupplier ended, final int timeoutMillis) {
            this.pipe = pipe;
            this.ended = ended;
            this.timeoutMillis = timeoutMillis;
        }

        void setTimeout(final int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        // A read during the setup ends at this deadline on the clock of System.nanoTime. Zero means none.
        void setSetupDeadline(final long deadline) {
            this.setupDeadline = deadline;
        }

        int getTimeout() {
            return timeoutMillis;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (offset < 0 || length < 0 || length > buffer.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            int bound = timeoutMillis;
            long start = System.nanoTime();
            while (true) {
                // A read that waits when the session connects goes on under the read timeout of the session, from
                // the moment of the switch.
                final int now = timeoutMillis;
                if (now != bound) {
                    bound = now;
                    start = System.nanoTime();
                }
                if (closed) {
                    return -1;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("The read of the SFTP stream proxy was interrupted.");
                }
                // A setup that passed its deadline takes no more bytes, also bytes that are there already.
                final long setup = setupDeadline;
                if (setup != 0 && System.nanoTime() - setup >= 0) {
                    throw new SocketTimeoutException("The setup of the SFTP session through the stream proxy passed its"
                            + " deadline.");
                }
                // The end is read before the count. JSch puts the bytes of the channel into the pipe before it marks
                // the end, so a byte that came before the end is never missed.
                final boolean end = ended.getAsBoolean();
                final int available = pipe.available();
                if (available > 0) {
                    final int read = pipe.read(buffer, offset, Math.min(length, available));
                    synchronized (pipe) {
                        pipe.notifyAll();
                    }
                    return read;
                }
                if (end) {
                    return -1;
                }
                if (bound > 0 && System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(bound)) {
                    throw new SocketTimeoutException("The SFTP stream proxy gave no byte for " + bound + " ms.");
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("The read of the SFTP stream proxy was interrupted.");
                }
            }
        }

        @Override
        public int available() throws IOException {
            return closed ? 0 : pipe.available();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            pipe.close();
        }
    }
}
