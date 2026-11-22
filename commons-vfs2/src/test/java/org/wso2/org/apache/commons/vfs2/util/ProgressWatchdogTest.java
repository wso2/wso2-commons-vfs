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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketTest.Gate;
import org.wso2.org.apache.commons.vfs2.util.WatchedSockets.Writer;

/**
 * Tests {@link ProgressWatchdog} with the watched sockets, streams and factories, on loopback connections.
 */
public class ProgressWatchdogTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private static final long STALL_MILLIS = 400;

    // The stall timeout of a test that calls the scan itself. The watcher thread never reaches it on the frozen clock.
    private static final long LONG_STALL_MILLIS = 10000;

    private static final long LONG_STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(LONG_STALL_MILLIS);

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static final long MINUTE = TimeUnit.SECONDS.toNanos(60);

    private static final String WARNING = "A write to host moved no bytes for ";

    private static final AtomicLong CLOCK_SHIFT = new AtomicLong();

    // While the clock is frozen it moves only when a test moves it.
    private static final AtomicLong FROZEN = new AtomicLong();

    private static volatile boolean frozen;

    // Holds one thread inside its next read of the clock. The thread gets the time of the moment it goes on.
    private static volatile Gate clockGate;

    private static long scanMillisBefore;

    private static Log logBefore;

    private final List<GatedStream> streams = new CopyOnWriteArrayList<>();

    private final List<Gate> gates = new CopyOnWriteArrayList<>();

    private final List<Worker> workers = new CopyOnWriteArrayList<>();

    private WatchedSockets peers;

    private TestLog log;

    @BeforeAll
    public static void shortScans() {
        scanMillisBefore = ProgressWatchdog.scanMillis;
        logBefore = ProgressWatchdog.log;
        ProgressWatchdog.scanMillis = 20;
        ProgressWatchdog.clock = () -> {
            final Gate gate = clockGate;
            if (gate != null) {
                gate.pass();
            }
            return frozen ? FROZEN.get() : System.nanoTime() + CLOCK_SHIFT.get();
        };
    }

    @AfterAll
    public static void realScans() {
        ProgressWatchdog.scanMillis = scanMillisBefore;
        ProgressWatchdog.clock = System::nanoTime;
        ProgressWatchdog.log = logBefore;
    }

    private static void freeze() {
        freezeAt(ProgressWatchdog.now());
    }

    private static void freezeAt(final long nanos) {
        FROZEN.set(nanos);
        frozen = true;
    }

    private static void advance(final long nanos) {
        FROZEN.addAndGet(nanos);
    }

    // The clock goes on from where the test left it.
    private static void unfreeze() {
        CLOCK_SHIFT.set(FROZEN.get() - System.nanoTime());
        frozen = false;
    }

    /**
     * A log that keeps its lines, and that a test can make fail or hang.
     */
    private static final class TestLog extends NoOpLog {

        private static final long serialVersionUID = 1L;

        private final List<String> lines = new CopyOnWriteArrayList<>();

        private volatile RuntimeException failure;

        private volatile Gate gate;

        private volatile Runnable onWarn;

        @Override
        public void warn(final Object message) {
            take(String.valueOf(message));
        }

        @Override
        public void warn(final Object message, final Throwable error) {
            take(message + " " + error);
        }

        private void take(final String line) {
            final Runnable seen = onWarn;
            if (seen != null) {
                seen.run();
            }
            final Gate held = gate;
            if (held != null) {
                held.pass();
            }
            final RuntimeException broken = failure;
            if (broken != null) {
                throw broken;
            }
            lines.add(line);
        }

        int count(final String part) {
            int count = 0;
            for (final String line : lines) {
                if (line.contains(part)) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * One call that the stream below holds until the test or the abort of the group lets it go.
     */
    private static final class Hold {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);
    }

    /**
     * A stream below that can hold a write, a flush or a close, as a channel does when its window is used up.
     */
    private final class GatedStream extends OutputStream {

        private final Map<String, Queue<Hold>> holds = new ConcurrentHashMap<>();

        private final List<Hold> all = new CopyOnWriteArrayList<>();

        private final Map<String, IOException> failures = new ConcurrentHashMap<>();

        private final List<String> calls = new CopyOnWriteArrayList<>();

        private volatile boolean broken;

        GatedStream() {
            streams.add(this);
        }

        // The next call of this name waits. A second hold of the same name is for the call after it.
        Hold hold(final String call) {
            final Hold hold = new Hold();
            all.add(hold);
            holds.computeIfAbsent(call, name -> new ConcurrentLinkedQueue<>()).add(hold);
            return hold;
        }

        void fail(final String call, final IOException error) {
            failures.put(call, error);
        }

        // The socket of the group. Its close breaks the channel, as the close of the proxy socket does.
        Socket socket() {
            return new Socket() {
                @Override
                public void close() throws IOException {
                    broken = true;
                    releaseAll();
                    super.close();
                }
            };
        }

        void releaseAll() {
            for (final Hold hold : all) {
                hold.release.countDown();
            }
        }

        int count(final String call) {
            int count = 0;
            for (final String made : calls) {
                if (made.equals(call)) {
                    count++;
                }
            }
            return count;
        }

        private void pass(final String call) throws IOException {
            calls.add(call);
            final IOException failure = failures.get(call);
            if (failure != null) {
                throw failure;
            }
            final Queue<Hold> waiting = holds.get(call);
            final Hold hold = waiting == null ? null : waiting.poll();
            if (hold == null) {
                return;
            }
            hold.entered.countDown();
            try {
                // A wait of a test has an end, so a failed test leaves no thread behind.
                if (!hold.release.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("the test did not release the call");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (broken) {
                throw new IOException("channel closed");
            }
        }

        @Override
        public void write(final int value) throws IOException {
            pass("write");
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException {
            pass("write");
        }

        @Override
        public void flush() throws IOException {
            pass("flush");
        }

        @Override
        public void close() throws IOException {
            pass("close");
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    /**
     * A thread of a test and how it ended.
     */
    private static final class Worker {

        private final AtomicReference<Throwable> error = new AtomicReference<>();

        private final Thread thread;

        Worker(final String name, final Call call) {
            thread = new Thread(() -> {
                try {
                    call.run();
                } catch (final Throwable e) {
                    error.set(e);
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }

        boolean isAlive() {
            return thread.isAlive();
        }

        // The error of the call, or null when it returned.
        Throwable end() throws InterruptedException {
            thread.join(5000);
            assertFalse(thread.isAlive(), "the thread " + thread.getName() + " did not end");
            return error.get();
        }
    }

    private Worker worker(final String name, final Call call) {
        final Worker worker = new Worker(name, call);
        workers.add(worker);
        return worker;
    }

    private Gate gate(final String threadName) {
        final Gate gate = new Gate(threadName);
        gates.add(gate);
        return gate;
    }

    @BeforeEach
    public void open() {
        peers = new WatchedSockets();
        log = new TestLog();
        ProgressWatchdog.log = log;
    }

    // Lets every held thread go, waits for it, and checks that the test left no thread and no registration.
    @AfterEach
    public void close() throws Exception {
        clockGate = null;
        for (final Gate gate : gates) {
            gate.release.countDown();
        }
        for (final GatedStream stream : streams) {
            stream.releaseAll();
        }
        peers.close();
        final List<String> left = new ArrayList<>();
        for (final Worker worker : workers) {
            worker.thread.join(5000);
            if (worker.thread.isAlive()) {
                left.add("the thread " + worker.thread.getName());
            }
        }
        if (frozen) {
            unfreeze();
        }
        if (!awaitNoReporter()) {
            left.add("the reporter thread");
        }
        if (ProgressWatchdog.registered() != 0) {
            left.add(ProgressWatchdog.registered() + " registered streams");
        }
        assertEquals(new ArrayList<String>(), left);
    }

    // Waits for scans that began after this call. The first scan that ends can have read the clock before the call.
    // It answers false when the watcher ended instead.
    private static boolean awaitScans() throws InterruptedException {
        final Thread watcher = ProgressWatchdog.watcher();
        final long target = ProgressWatchdog.scans() + 2;
        final long end = System.nanoTime() + 5 * SECOND;
        while (ProgressWatchdog.scans() < target) {
            if (watcher == null || !watcher.isAlive() || System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    private boolean awaitLines(final int count) throws InterruptedException {
        final long end = System.nanoTime() + 5 * SECOND;
        while (log.lines.size() < count) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    private static boolean awaitNoReporter() throws InterruptedException {
        final long end = System.nanoTime() + 5 * SECOND;
        while (ProgressWatchdog.reporter() != null || threads(ProgressWatchdog.REPORTER_NAME) != 0) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(2);
        }
        return true;
    }

    private static int threads(final String name) {
        int count = 0;
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName()) && thread.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static int watcherThreads() {
        return threads(ProgressWatchdog.THREAD_NAME);
    }

    private static WatchedOutputStream registered(final OutputStream below, final SocketGroup group,
            final long stallMillis) {
        final WatchedOutputStream out = new WatchedOutputStream(below, group, stallMillis, "host");
        ProgressWatchdog.register(out);
        return out;
    }

    private SocketGroup groupOf(final GatedStream below) {
        final SocketGroup group = new SocketGroup();
        group.add(below.socket());
        return group;
    }

    @Test
    public void writeIsCutIntoSlicesAndMarkedOnlyInsideASlice() throws Exception {
        final List<Integer> slices = new ArrayList<>();
        final List<Long> marks = new ArrayList<>();
        final List<Long> counts = new ArrayList<>();
        final WatchedOutputStream[] holder = new WatchedOutputStream[1];
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final OutputStream below = new OutputStream() {
            @Override
            public void write(final int value) {
                throw new IllegalStateException("single byte write below");
            }

            @Override
            public void write(final byte[] buffer, final int offset, final int length) {
                slices.add(length);
                marks.add(holder[0].getWriteSince());
                counts.add(holder[0].getBytesWritten());
                sink.write(buffer, offset, length);
            }
        };
        final WatchedOutputStream out = new WatchedOutputStream(below, new SocketGroup(), 60000, "host");
        holder[0] = out;
        final byte[] data = new byte[100000];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }

        out.write(data, 0, data.length);
        out.write(7);

        assertEquals(8, slices.size());
        long before = 0;
        for (int index = 0; index < slices.size(); index++) {
            assertTrue(slices.get(index) <= WatchedOutputStream.SLICE_BYTES);
            assertTrue(marks.get(index) != 0);
            // Inside a slice the count is the count before that slice.
            assertEquals(before, counts.get(index).longValue());
            before += slices.get(index);
        }
        assertEquals(0, out.getWriteSince());
        assertEquals(data.length + 1, out.getBytesWritten());
        final byte[] written = sink.toByteArray();
        assertEquals(data.length + 1, written.length);
        for (int index = 0; index < data.length; index++) {
            assertEquals(data[index], written[index]);
        }
        assertEquals(7, written[data.length]);
    }

    @Test
    public void errorOfAStreamWhoseGroupIsNotAbortedIsPassedOnAndTheMarkIsCleared() {
        final IOException broken = new IOException("broken");
        final OutputStream below = new OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                throw broken;
            }

            @Override
            public void flush() throws IOException {
                throw broken;
            }
        };
        final WatchedOutputStream out = new WatchedOutputStream(below, new SocketGroup(), 60000, "host");
        assertSame(broken, assertThrows(IOException.class, () -> out.write(new byte[10])));
        assertSame(broken, assertThrows(IOException.class, out::flush));
        assertEquals(0, out.getWriteSince());
        assertEquals(0, out.getBytesWritten());
    }

    @Test
    public void stalledWriteIsEndedAtTheStallTimeoutAndNoSooner() throws Exception {
        final SocketGroup group = new SocketGroup();
        final ServerSocket server = peers.silentServer();
        final Socket socket = WatchedSockets.small(new WatchedSocketFactory(group, 5000, STALL_MILLIS).createSocket());
        socket.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), 5000);
        final WatchedOutputStream out = (WatchedOutputStream) socket.getOutputStream();

        final Writer writer = peers.blockedWriter(socket);
        // The start of the blocked slice, read by the fixture while the writer was blocked in it.
        final long blockedSince = writer.blockedSince();
        assertTrue(blockedSince != 0);
        final IOException error = writer.awaitError(5000);
        final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(writer.failedAtNanos() - blockedSince);

        final StalledTransferException stalled = assertInstanceOf(StalledTransferException.class, error);
        assertTrue(stalled.getStallMillis() >= STALL_MILLIS, "stall time " + stalled.getStallMillis());
        assertTrue(waitedMillis >= STALL_MILLIS && waitedMillis < STALL_MILLIS + 1500, "waited " + waitedMillis);
        assertEquals(out.getBytesWritten(), stalled.getBytesWritten());
        assertInstanceOf(SocketException.class, stalled.getCause());
        assertEquals(Fault.TRANSPORT, Faults.of(stalled));
        assertTrue(group.isAborted());
        assertTrue(group.reason().contains("moved no bytes"), group.reason());
        assertTrue(socket.isClosed());
        assertTrue(group.isEmpty());
    }

    @Test
    public void slowCallerIsNotClosed() throws Exception {
        final SocketGroup group = new SocketGroup();
        final ServerSocket server = peers.silentServer();
        final Socket socket = new WatchedSocketFactory(group, 5000, 200).createSocket(LOOPBACK, server.getLocalPort());
        final OutputStream out = socket.getOutputStream();

        out.write(new byte[100]);
        Thread.sleep(600);
        out.write(new byte[100]);
        Thread.sleep(600);

        assertFalse(group.isAborted());
        assertFalse(socket.isClosed());
        socket.close();
    }

    @Test
    public void stallTimeoutZeroTurnsTheWatchdogOffAndTheGroupStillEndsTheWrite() throws Exception {
        final SocketGroup group = new SocketGroup();
        final ServerSocket server = peers.silentServer();
        final int before = ProgressWatchdog.registered();
        final Socket socket = WatchedSockets.small(new WatchedSocketFactory(group, 5000, 0).createSocket());
        assertEquals(before, ProgressWatchdog.registered());
        socket.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), 5000);

        final Writer writer = peers.blockedWriter(socket);
        Thread.sleep(1000);
        assertFalse(writer.hasFailed());

        group.abort("test abort");
        final IOException error = writer.awaitError(1000);
        assertInstanceOf(StalledTransferException.class, error);
        assertTrue(error.getMessage().contains("test abort"), error.getMessage());
    }

    @Test
    public void closedSocketLeavesTheRegistryAndTheGroup() throws Exception {
        final SocketGroup group = new SocketGroup();
        final int before = ProgressWatchdog.registered();
        final Socket socket = new WatchedSocketFactory(group, 5000, STALL_MILLIS).createSocket();
        assertEquals(before + 1, ProgressWatchdog.registered());
        assertEquals(1, group.getSockets().size());
        assertSame(socket, group.getSockets().get(0));

        socket.close();
        socket.close();

        assertEquals(before, ProgressWatchdog.registered());
        assertTrue(group.isEmpty());
    }

    @Test
    public void socketOfAnAbortedGroupIsClosedAtOnceAndNoFormOfTheFactoryReachesThePeer() throws Exception {
        final SocketGroup group = new SocketGroup();
        group.abort("gone");
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, STALL_MILLIS);
        final WatchedSocketFactory throughProxy = new WatchedSocketFactory(group, 5000, STALL_MILLIS, Proxy.NO_PROXY);
        try (ServerSocket peer = new ServerSocket(0, 5, LOOPBACK)) {
            final int port = peer.getLocalPort();
            final InetSocketAddress address = new InetSocketAddress(LOOPBACK, port);

            final Socket plain = factory.createSocket();
            assertTrue(plain.isClosed());
            assertThrows(SocketException.class, () -> plain.connect(address, 5000));
            assertTrue(throughProxy.createSocket().isClosed());
            assertThrows(SocketException.class, () -> factory.createSocket(LOOPBACK, port));
            assertThrows(SocketException.class, () -> factory.createSocket(LOOPBACK, port, LOOPBACK, 0));
            assertThrows(SocketException.class, () -> factory.createSocket(LOOPBACK.getHostAddress(), port));
            assertThrows(SocketException.class, () -> throughProxy.createSocket(LOOPBACK, port));

            assertNoConnection(peer);
        }
        assertTrue(group.isEmpty());
        assertEquals(0, ProgressWatchdog.registered());
    }

    // The peer is asked, because the closed flag of a socket does not tell whether a native socket connected.
    private static void assertNoConnection(final ServerSocket peer) throws IOException {
        peer.setSoTimeout(300);
        try (Socket reached = peer.accept()) {
            throw new AssertionError("a connection reached the peer from " + reached.getRemoteSocketAddress());
        } catch (final SocketTimeoutException e) {
            // No connection came.
        }
    }

    // Waits until the gate holds its thread. It answers false when the thread ended and never came to the gate.
    private static boolean reached(final Gate gate, final Worker worker) throws InterruptedException {
        final long end = System.nanoTime() + 5 * SECOND;
        while (!gate.reached.await(5, TimeUnit.MILLISECONDS)) {
            if (!worker.isAlive()) {
                return gate.reached.await(0, TimeUnit.MILLISECONDS);
            }
            assertTrue(System.nanoTime() < end, "the thread came to no end and to no gate");
        }
        return true;
    }

    @Test
    public void abortWhileAConnectHasPassedItsClosedCheckDoesNotReachThePeer() throws Exception {
        final Gate gate = gate("caller");
        final SocketGroup group = new SocketGroup();
        // Up to JDK 17 the connect asks this after its closed check and before it makes or uses the native socket.
        final Socket socket = new WatchedSocket(group, 0) {
            @Override
            public boolean isConnected() {
                gate.pass();
                return super.isConnected();
            }
        };
        try (ServerSocket peer = new ServerSocket(0, 5, LOOPBACK)) {
            final InetSocketAddress address = new InetSocketAddress(LOOPBACK, peer.getLocalPort());
            final Worker caller = worker("caller", () -> socket.connect(address, 5000));
            final boolean held = reached(gate, caller);
            if (held) {
                group.abort("stop");
                assertTrue(socket.isClosed());
                assertTrue(group.isEmpty());
                gate.release.countDown();
            }
            final Throwable error = caller.end();
            socket.close();
            assumeTrue(held, "This JDK asks the socket nothing between the closed check and the native connect");

            assertInstanceOf(IOException.class, error);
            assertNoConnection(peer);
        }
    }

    @Test
    public void abortWhileABindHasPassedItsClosedCheckLeavesNoBoundPort() throws Exception {
        final int port = WatchedServerSocketTest.freePort();
        final Gate gate = gate("caller");
        final SocketGroup group = new SocketGroup();
        final Socket socket = new WatchedSocket(group, 0) {
            @Override
            public boolean isBound() {
                gate.pass();
                return super.isBound();
            }
        };
        final Worker caller = worker("caller", () -> socket.bind(new InetSocketAddress(LOOPBACK, port)));
        final boolean held = reached(gate, caller);
        if (held) {
            group.abort("stop");
            assertTrue(socket.isClosed());
            gate.release.countDown();
        }
        final Throwable error = caller.end();
        socket.close();
        assumeTrue(held, "This JDK asks the socket nothing between the closed check and the native bind");

        assertInstanceOf(IOException.class, error);
        // A native socket that was bound behind the abort would still hold the port.
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(LOOPBACK, port));
        }
    }

    @Test
    public void socketThatTheGroupDoesNotTakeIsClosedAndNotWatched() throws Exception {
        final List<Socket> seen = new ArrayList<>();
        final SocketGroup group = new SocketGroup() {
            @Override
            public void add(final Socket socket) {
                seen.add(socket);
                throw new IllegalStateException("no room");
            }
        };
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, STALL_MILLIS);

        assertThrows(IllegalStateException.class, factory::createSocket);
        assertThrows(IllegalStateException.class,
                () -> new WatchedSocketFactory(group, 5000, STALL_MILLIS, Proxy.NO_PROXY).createSocket());

        assertEquals(2, seen.size());
        for (final Socket socket : seen) {
            assertTrue(socket.isClosed());
        }
        assertEquals(0, ProgressWatchdog.registered());
    }

    @Test
    public void failedConnectTakesTheSocketOutOfTheGroup() throws Exception {
        final int port = WatchedServerSocketTest.freePort();
        final SocketGroup group = new SocketGroup();
        final int before = ProgressWatchdog.registered();

        assertThrows(ConnectException.class,
                () -> new WatchedSocketFactory(group, 5000, STALL_MILLIS).createSocket(LOOPBACK, port));

        assertTrue(group.isEmpty());
        assertEquals(before, ProgressWatchdog.registered());
    }

    @Test
    public void connectErrorClosesTheSocketAndReachesTheCaller() throws Exception {
        final SocketGroup group = new SocketGroup();
        final int before = ProgressWatchdog.registered();
        final InternalError refusal = new InternalError("proxy refused the connection");
        try (WatchedSocket socket = new WatchedSocket(group, STALL_MILLIS) {
            @Override
            public void connect(final SocketAddress endpoint, final int timeout) {
                throw refusal;
            }
        }) {
            final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, STALL_MILLIS) {
                @Override
                public Socket createSocket() {
                    return socket;
                }
            };
            assertEquals(before + 1, ProgressWatchdog.registered());
            assertSame(refusal, assertThrows(InternalError.class, () -> factory.createSocket(LOOPBACK, 8021)));
            assertTrue(socket.isClosed());
            assertTrue(group.isEmpty());
            assertFalse(group.isAborted());
            assertEquals(before, ProgressWatchdog.registered());
        }
    }

    @Test
    public void factoryConnectsEveryFormIntoTheGroup() throws Exception {
        final SocketGroup group = new SocketGroup();
        final ServerSocket server = peers.silentServer();
        final int port = server.getLocalPort();
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, STALL_MILLIS);
        final WatchedSocketFactory throughProxy = new WatchedSocketFactory(group, 5000, STALL_MILLIS, Proxy.NO_PROXY);
        final List<Socket> sockets = new ArrayList<>();
        sockets.add(factory.createSocket(LOOPBACK.getHostAddress(), port));
        sockets.add(factory.createSocket(LOOPBACK.getHostAddress(), port, LOOPBACK, 0));
        sockets.add(factory.createSocket(LOOPBACK, port));
        sockets.add(factory.createSocket(LOOPBACK, port, LOOPBACK, 0));
        sockets.add(throughProxy.createSocket(LOOPBACK, port));

        for (final Socket socket : sockets) {
            assertInstanceOf(WatchedSocket.class, socket);
            assertTrue(socket.isConnected());
            assertInstanceOf(WatchedOutputStream.class, socket.getOutputStream());
            assertSame(socket.getOutputStream(), socket.getOutputStream());
        }
        assertEquals(sockets.size(), group.getSockets().size());

        group.abort("done");
        for (final Socket socket : sockets) {
            assertTrue(socket.isClosed());
        }
        assertTrue(group.isEmpty());
    }

    @Test
    public void acceptedSocketIsWatchedAndInTheGroup() throws Exception {
        final SocketGroup group = new SocketGroup();
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, STALL_MILLIS);
        try (ServerSocket server = factory.createServerSocket(0, 1, LOOPBACK)) {
            assertInstanceOf(WatchedServerSocket.class, server);
            peers.silentClient(server.getLocalPort());
            final Socket accepted = server.accept();
            assertInstanceOf(WatchedSocket.class, accepted);
            assertEquals(1, group.getSockets().size());
            accepted.setSendBufferSize(8192);

            final Writer writer = peers.blockedWriter(accepted);
            assertInstanceOf(StalledTransferException.class, writer.awaitError(5000));
            assertTrue(group.isAborted());

            // The abort closed the listening socket with the accepted one.
            assertTrue(server.isClosed());
            assertThrows(SocketException.class, server::accept);
            assertTrue(group.isEmpty());
        }
        try (ServerSocket unbound = factory.createServerSocket()) {
            assertFalse(unbound.isBound());
            assertThrows(SocketException.class, unbound::accept);
        }
        // The group above is aborted, and a listener of it is never bound. A new group shows the other forms.
        final WatchedServerSocketFactory open = new WatchedServerSocketFactory(new SocketGroup(), STALL_MILLIS);
        try (ServerSocket byPort = open.createServerSocket(0);
                ServerSocket byBacklog = open.createServerSocket(0, 1);
                ServerSocket ofAborted = factory.createServerSocket(0)) {
            assertTrue(byPort.isBound());
            assertTrue(byBacklog.isBound());
            assertTrue(ofAborted.isClosed());
            assertFalse(ofAborted.isBound());
        }
    }

    @Test
    public void twoManagersInOneCopyShareOneWatcherThread() throws Exception {
        final DefaultFileSystemManager first = new DefaultFileSystemManager();
        final DefaultFileSystemManager second = new DefaultFileSystemManager();
        try (Socket ofFirst = new WatchedSocketFactory(first.newSocketGroup(), 5000, STALL_MILLIS).createSocket();
                Socket ofSecond = new WatchedSocketFactory(second.newSocketGroup(), 5000, STALL_MILLIS)
                        .createSocket()) {
            assertNotNull(ProgressWatchdog.watcher());
            assertEquals(1, watcherThreads());
            assertTrue(ProgressWatchdog.watcher().isDaemon());
        }
    }

    @Test
    public void watcherStillEndsAStalledWriteAfterAnotherManagerClosed() throws Exception {
        final DefaultFileSystemManager closing = new DefaultFileSystemManager();
        final DefaultFileSystemManager working = new DefaultFileSystemManager();
        closing.init();
        working.init();
        try {
            final ServerSocket server = peers.silentServer();
            final Socket idle = new WatchedSocketFactory(closing.newSocketGroup(), 5000, STALL_MILLIS)
                    .createSocket(LOOPBACK, server.getLocalPort());
            final SocketGroup group = working.newSocketGroup();
            final Socket socket = WatchedSockets.small(new WatchedSocketFactory(group, 5000, STALL_MILLIS)
                    .createSocket());
            socket.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), 5000);
            final Thread watcher = ProgressWatchdog.watcher();

            closing.abortAll();
            closing.close();
            assertTrue(idle.isClosed());

            final Writer writer = peers.blockedWriter(socket);
            assertInstanceOf(StalledTransferException.class, writer.awaitError(5000));
            assertTrue(group.isAborted());
            assertSame(watcher, ProgressWatchdog.watcher());
            assertEquals(1, watcherThreads());
        } finally {
            working.close();
        }
    }

    @Test
    public void watcherEndsAtTheFirstScanAfterOneEmptyMinuteAndTheNextRegistrationStartsANewOne() throws Exception {
        final Thread watcher = assertEndsAfterOneEmptyMinute(SECOND);

        final DefaultFileSystemManager manager = new DefaultFileSystemManager();
        try (Socket next = new WatchedSocketFactory(manager.newSocketGroup(), 5000, STALL_MILLIS).createSocket()) {
            final Thread second = ProgressWatchdog.watcher();
            assertNotNull(second);
            assertNotSame(watcher, second);
            assertTrue(second.isAlive());
            assertEquals(1, watcherThreads());
        }
    }

    @Test
    public void emptyMinuteIsMeasuredOnAClockThatIsNegativeCrossesZeroOrWraps() throws Exception {
        // The registration is 61 s long. So the empty minute starts 61 s after each of these times.
        assertEndsAfterOneEmptyMinute(-200 * SECOND);
        // It starts 30 s before zero and ends 30 s after.
        assertEndsAfterOneEmptyMinute(-91 * SECOND);
        // It starts 30 s before the largest value and ends behind the wrap.
        assertEndsAfterOneEmptyMinute(Long.MAX_VALUE - 91 * SECOND);
        // It starts at zero.
        assertEndsAfterOneEmptyMinute(-61 * SECOND);
    }

    // A long registration, then exactly one empty minute on the test clock. It gives back the watcher that ended.
    private Thread assertEndsAfterOneEmptyMinute(final long origin) throws Exception {
        final String at = "clock origin " + origin;
        assertEquals(0, ProgressWatchdog.registered(), "a stream of another test is still registered");
        freezeAt(origin);
        try {
            final WatchedOutputStream out = registered(new ByteArrayOutputStream(), new SocketGroup(), STALL_MILLIS);
            final Thread watcher = ProgressWatchdog.watcher();
            assertNotNull(watcher);
            // Busy for longer than a minute. None of that time counts.
            advance(61 * SECOND);
            assertTrue(awaitScans(), at);

            ProgressWatchdog.unregister(out);
            assertTrue(awaitScans(), "the watcher ended with no empty time, " + at);
            advance(MINUTE - 1);
            assertTrue(awaitScans(), "the watcher ended one nanosecond before the minute, " + at);
            final long scans = ProgressWatchdog.scans();
            advance(1);
            watcher.join(5000);
            assertFalse(watcher.isAlive(), "the watcher did not end at the minute, " + at);
            // At most the one scan that had read the clock before the last step went on.
            assertTrue(ProgressWatchdog.scans() - scans <= 1, "the watcher did not end at its first scan, " + at);
            assertEquals(0, watcherThreads());
            assertNull(ProgressWatchdog.watcher());
            return watcher;
        } finally {
            unfreeze();
        }
    }

    @Test
    public void watcherDoesNotEndWhileTheLastRemovalIsStillOnItsWay() throws Exception {
        assertEquals(0, ProgressWatchdog.registered(), "a stream of another test is still registered");
        freeze();
        final WatchedOutputStream out = registered(new ByteArrayOutputStream(), new SocketGroup(), STALL_MILLIS);
        final Thread watcher = ProgressWatchdog.watcher();
        advance(61 * SECOND);
        assertTrue(awaitScans());

        // The stream is out of the registry, and the thread that took it out has not read the clock yet.
        final Gate gate = gate("remover");
        clockGate = gate;
        final Worker remover = worker("remover", () -> ProgressWatchdog.unregister(out));
        assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
        assertEquals(0, ProgressWatchdog.registered());
        assertTrue(awaitScans(), "the watcher ended on the time before the removal");
        advance(61 * SECOND);
        assertTrue(awaitScans(), "the watcher ended on an empty time that nobody has set");

        gate.release.countDown();
        assertNull(remover.end());
        clockGate = null;
        // The empty minute starts where the removal read the clock.
        advance(MINUTE - 1);
        assertTrue(awaitScans(), "the watcher ended before one empty minute");
        assertSame(watcher, ProgressWatchdog.watcher());
        advance(1);
        watcher.join(5000);
        assertFalse(watcher.isAlive());
    }

    @Test
    public void registrationThatCameAndWentBetweenTwoScansStartsTheSixtySecondsAgain() throws Exception {
        assertEquals(0, ProgressWatchdog.registered(), "a stream of another test is still registered");
        final SocketGroup group = new SocketGroup();
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, STALL_MILLIS);
        freeze();
        factory.createSocket().close();
        final Thread watcher = ProgressWatchdog.watcher();
        assertNotNull(watcher);
        advance(59 * SECOND);
        assertTrue(awaitScans());
        // Open and closed with the clock at 59 s. No scan has to see it in the registry.
        factory.createSocket().close();
        advance(2 * SECOND);
        assertTrue(awaitScans(), "the watcher counted the time before the last registration");
        assertSame(watcher, ProgressWatchdog.watcher());
        advance(58 * SECOND - 1);
        assertTrue(awaitScans(), "the watcher ended before 60 s after the last registration");
        advance(1);
        watcher.join(5000);
        assertFalse(watcher.isAlive(), "the watcher did not end at the first scan after 60 s");
    }

    @Test
    public void registrationWhileTheWatcherIsAboutToEndKeepsTheWatcher() throws Exception {
        assertEquals(0, ProgressWatchdog.registered(), "a stream of another test is still registered");
        freeze();
        final WatchedOutputStream first = registered(new ByteArrayOutputStream(), new SocketGroup(), STALL_MILLIS);
        final Thread watcher = ProgressWatchdog.watcher();
        ProgressWatchdog.unregister(first);
        final WatchedOutputStream second = new WatchedOutputStream(new ByteArrayOutputStream(), new SocketGroup(),
                STALL_MILLIS, "host");
        try {
            synchronized (ProgressWatchdog.THREAD_LOCK) {
                advance(MINUTE);
                // The watcher has read a full empty minute and waits for the lock to end itself.
                final long end = System.nanoTime() + 5 * SECOND;
                while (watcher.getState() != Thread.State.BLOCKED) {
                    assertTrue(System.nanoTime() < end, "the watcher did not come to its end");
                    Thread.sleep(2);
                }
                // The registration finds the watcher in place and starts no other.
                ProgressWatchdog.register(second);
            }
            assertTrue(awaitScans(), "the watcher ended and left a registered stream alone");
            assertSame(watcher, ProgressWatchdog.watcher());
            assertTrue(watcher.isAlive());
            assertEquals(1, watcherThreads());
            assertEquals(1, ProgressWatchdog.registered());
        } finally {
            ProgressWatchdog.unregister(second);
        }
    }

    @Test
    public void registrationAtTheMomentTheWatcherEndsAlwaysHasOneWatcher() throws Exception {
        final WatchedSocketFactory factory = new WatchedSocketFactory(new SocketGroup(), 5000, STALL_MILLIS);
        for (int round = 0; round < 30; round++) {
            freeze();
            try {
                factory.createSocket().close();
                advance(MINUTE - 1);
                awaitScans();
                final AtomicReference<Socket> made = new AtomicReference<>();
                final Worker registering = worker("registering", () -> made.set(factory.createSocket()));
                advance(1);
                assertNull(registering.end());
                // The socket is registered, so a watcher must run now and after the next scans.
                assertNotNull(ProgressWatchdog.watcher(), "round " + round);
                assertTrue(awaitScans(), "round " + round);
                assertTrue(ProgressWatchdog.watcher().isAlive(), "round " + round);
                assertEquals(1, watcherThreads(), "round " + round);
                made.get().close();
            } finally {
                unfreeze();
            }
        }
    }

    @Test
    public void writeIsStalledOnlyWhenItIsOlderThanTheTimeoutToTheNanosecond() throws Exception {
        for (final long origin : new long[] {SECOND, -200 * SECOND, -LONG_STALL_NANOS, Long.MAX_VALUE - 5}) {
            final String at = "clock origin " + origin;
            freezeAt(origin);
            final GatedStream below = new GatedStream();
            final Hold hold = below.hold("write");
            final SocketGroup group = groupOf(below);
            final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
            final Worker writer = worker("writer", () -> out.write(new byte[10]));
            assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
            final long since = out.getWriteSince();
            assertEquals(ProgressWatchdog.now(), since);

            ProgressWatchdog.scan(since + LONG_STALL_NANOS - 1);
            assertFalse(group.isAborted(), "one nanosecond below the timeout, " + at);
            ProgressWatchdog.scan(since + LONG_STALL_NANOS);
            assertFalse(group.isAborted(), "exactly at the timeout, " + at);
            ProgressWatchdog.scan(since + LONG_STALL_NANOS + 1);
            assertTrue(group.isAborted(), "one nanosecond above the timeout, " + at);

            assertInstanceOf(StalledTransferException.class, writer.end(), at);
            unfreeze();
        }
    }

    @Test
    public void idleTimeAndALaterStartAreNotAStall() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        assertNull(worker("first writer", () -> out.write(new byte[10])).end());
        final long firstStart = ProgressWatchdog.now();

        // No send is inside the stream, however long ago the last one was.
        advance(61 * SECOND);
        ProgressWatchdog.scan(firstStart + 3600 * SECOND);
        assertTrue(awaitScans());
        assertFalse(group.isAborted());

        // The next send starts 61 s after the first. Only its own time counts.
        final Hold hold = below.hold("write");
        final Worker second = worker("second writer", () -> out.write(new byte[10]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();
        assertEquals(firstStart + 61 * SECOND, since);
        ProgressWatchdog.scan(since + LONG_STALL_NANOS);
        assertTrue(awaitScans());
        assertFalse(group.isAborted());

        hold.release.countDown();
        assertNull(second.end());
        assertEquals(20, out.getBytesWritten());
        assertTrue(log.lines.isEmpty());
        ProgressWatchdog.unregister(out);
    }

    @Test
    public void flushThatWaitsInTheStreamBelowIsEndedAtTheStallTimeout() throws Exception {
        assertStalledIn("flush");
    }

    @Test
    public void closeThatWaitsInTheStreamBelowIsEndedAtTheStallTimeout() throws Exception {
        assertStalledIn("close");
    }

    private void assertStalledIn(final String call) throws Exception {
        final int before = ProgressWatchdog.registered();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, STALL_MILLIS);
        out.write(new byte[10]);
        assertEquals(0, out.getWriteSince());
        final Hold hold = below.hold(call);
        final long start = System.nanoTime();
        final Worker caller = worker("caller", "flush".equals(call) ? out::flush : out::close);
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        assertTrue("flush".equals(call) ? out.getWriteSince() != 0 : out.closeSince() != 0);

        final Throwable error = caller.end();
        final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        final StalledTransferException stalled = assertInstanceOf(StalledTransferException.class, error);
        assertTrue(waitedMillis >= STALL_MILLIS && waitedMillis < STALL_MILLIS + 1500, "waited " + waitedMillis);
        assertTrue(stalled.getStallMillis() >= STALL_MILLIS, "stall time " + stalled.getStallMillis());
        assertEquals(10, stalled.getBytesWritten());
        assertEquals(10, out.getBytesWritten());
        assertEquals("channel closed", stalled.getCause().getMessage());
        assertTrue(stalled.getMessage().contains("moved no bytes"), stalled.getMessage());
        assertTrue(group.isAborted());
        assertEquals(0, out.getWriteSince());
        assertEquals(0, out.closeSince());
        assertEquals(before, ProgressWatchdog.registered());
    }

    @Test
    public void healthyFlushAndCloseAreNotWatchedBetweenTheCalls() throws Exception {
        final int before = ProgressWatchdog.registered();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, 200);
        assertEquals(before + 1, ProgressWatchdog.registered());

        out.write(new byte[10]);
        Thread.sleep(500);
        out.flush();
        assertEquals(0, out.getWriteSince());
        Thread.sleep(500);
        out.close();
        out.close();

        assertEquals(0, out.getWriteSince());
        assertEquals(10, out.getBytesWritten());
        assertFalse(group.isAborted());
        assertEquals(before, ProgressWatchdog.registered());
        assertEquals(1, below.count("close"));
        // A send after the close does not come to the stream below.
        assertThrows(IOException.class, () -> out.write(new byte[10]));
        assertThrows(IOException.class, out::flush);
        assertEquals(1, below.count("write"));
        assertEquals(1, below.count("flush"));
        assertEquals(0, out.getWriteSince());
    }

    @Test
    public void closeNextToABlockedSendLeavesTheMarkAndTheWatchOfThatSend() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold hold = below.hold("flush");
        final Worker writer = worker("writer", out::flush);
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();

        // The close comes 3 s later, runs through and returns. A second close finds nothing to do.
        advance(3 * SECOND);
        out.close();
        out.close();

        assertEquals(1, below.count("close"));
        assertEquals(since, out.getWriteSince());
        assertEquals(1, ProgressWatchdog.registered());
        assertTrue(writer.isAlive());
        assertTrue(awaitScans());
        assertFalse(group.isAborted());

        // The send still has the deadline of its own start. The abort of the group lets the stream below go.
        ProgressWatchdog.scan(since + LONG_STALL_NANOS);
        assertFalse(group.isAborted());
        ProgressWatchdog.scan(since + LONG_STALL_NANOS + 1);
        assertTrue(group.isAborted());
        final StalledTransferException stalled = assertInstanceOf(StalledTransferException.class, writer.end());
        assertEquals(3000, stalled.getStallMillis());
        assertEquals("channel closed", stalled.getCause().getMessage());
        assertEquals(0, out.getWriteSince());
        assertEquals(0, ProgressWatchdog.registered());
        assertTrue(awaitLines(1));
        assertEquals(1, log.count(WARNING + LONG_STALL_MILLIS + " ms after 0 bytes"));
    }

    @Test
    public void closeThatFailsNextToABlockedSendLeavesTheMarkAndTheWatchOfThatSend() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold hold = below.hold("flush");
        final Worker writer = worker("writer", out::flush);
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();
        final IOException broken = new IOException("close failed");
        below.fail("close", broken);

        assertSame(broken, assertThrows(IOException.class, out::close));

        assertEquals(0, out.closeSince());
        assertEquals(since, out.getWriteSince());
        assertEquals(1, ProgressWatchdog.registered());
        ProgressWatchdog.scan(since + LONG_STALL_NANOS + 1);
        assertInstanceOf(StalledTransferException.class, writer.end());
        assertEquals(0, ProgressWatchdog.registered());
    }

    @Test
    public void sendThatComesWhileACloseIsBlockedIsRefusedAndTheCloseKeepsItsOwnDeadline() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        out.write(new byte[10]);
        final Hold hold = below.hold("close");
        final Worker closer = worker("closer", out::close);
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.closeSince();
        assertTrue(since != 0);

        advance(3 * SECOND);
        assertThrows(IOException.class, out::flush);
        assertThrows(IOException.class, () -> out.write(new byte[10]));
        // A second close returns while the first is still inside the stream below.
        out.close();

        assertEquals(0, below.count("flush"));
        assertEquals(1, below.count("write"));
        assertEquals(1, below.count("close"));
        assertEquals(since, out.closeSince());
        assertEquals(0, out.getWriteSince());
        assertEquals(1, ProgressWatchdog.registered());
        assertTrue(closer.isAlive());

        ProgressWatchdog.scan(since + LONG_STALL_NANOS);
        assertFalse(group.isAborted());
        ProgressWatchdog.scan(since + LONG_STALL_NANOS + 1);
        final StalledTransferException stalled = assertInstanceOf(StalledTransferException.class, closer.end());
        assertEquals(3000, stalled.getStallMillis());
        assertEquals(10, stalled.getBytesWritten());
        assertEquals(0, out.closeSince());
        assertEquals(0, ProgressWatchdog.registered());
    }

    @Test
    public void sendAndCloseThatAreBothBlockedAreBothEndedByOneAbort() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold flushHold = below.hold("flush");
        final Hold closeHold = below.hold("close");
        final Worker writer = worker("writer", out::flush);
        assertTrue(flushHold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();
        advance(2 * SECOND);
        final Worker closer = worker("closer", out::close);
        assertTrue(closeHold.entered.await(5, TimeUnit.SECONDS));
        assertEquals(since + 2 * SECOND, out.closeSince());
        assertEquals(since, out.getWriteSince());

        // Only the send is over its deadline. The close began 2 s later.
        ProgressWatchdog.scan(since + LONG_STALL_NANOS + 1);

        assertEquals(2000, assertInstanceOf(StalledTransferException.class, writer.end()).getStallMillis());
        assertEquals(0, assertInstanceOf(StalledTransferException.class, closer.end()).getStallMillis());
        assertEquals(0, out.getWriteSince());
        assertEquals(0, out.closeSince());
        assertEquals(0, ProgressWatchdog.registered());
        assertTrue(awaitLines(1));
        assertTrue(awaitNoReporter());
        assertEquals(1, log.lines.size(), log.lines.toString());
    }

    @Test
    public void closeThatFailsAloneClearsItsMarkAndLeavesTheRegistry() throws Exception {
        final GatedStream below = new GatedStream();
        final WatchedOutputStream out = registered(below, groupOf(below), LONG_STALL_MILLIS);
        final IOException broken = new IOException("close failed");
        below.fail("close", broken);

        assertSame(broken, assertThrows(IOException.class, out::close));
        out.close();

        assertEquals(1, below.count("close"));
        assertEquals(0, out.closeSince());
        assertEquals(0, ProgressWatchdog.registered());
    }

    @Test
    public void watchdogWarnsOnceAfterItHasClosedTheSockets() throws Exception {
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final Socket socket = group.getSockets().get(0);
        final WatchedOutputStream out = new WatchedOutputStream(below, group, STALL_MILLIS, "files.example.org");
        ProgressWatchdog.register(out);
        final AtomicBoolean closedBefore = new AtomicBoolean();
        log.onWarn = () -> closedBefore.set(group.isAborted() && socket.isClosed());
        out.write(new byte[10]);
        final Hold hold = below.hold("write");
        final Worker writer = worker("writer", () -> out.write(new byte[20]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));

        assertInstanceOf(StalledTransferException.class, writer.end());

        assertTrue(awaitLines(1));
        assertTrue(awaitNoReporter());
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertTrue(log.lines.get(0).matches("A write to files\\.example\\.org moved no bytes for \\d+ ms after 10 bytes\\. "
                + "The watchdog closes the sockets of this connection\\."), log.lines.get(0));
        assertTrue(closedBefore.get(), "the warning came before the sockets were closed");
        assertEquals(0, threads(ProgressWatchdog.REPORTER_NAME));
    }

    @Test
    public void logThatThrowsKeepsNoWriteBlockedAndTheWatcherGoesOn() throws Exception {
        log.failure = new IllegalStateException("log unavailable");
        final Thread watcher = stallOne();
        // A later registration is still watched by the same thread.
        assertSame(watcher, stallOne());
        assertTrue(awaitNoReporter());
        log.failure = null;
        assertSame(watcher, stallOne());

        assertTrue(awaitLines(1));
        assertTrue(awaitNoReporter());
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertTrue(watcher.isAlive());
        assertEquals(1, watcherThreads());
    }

    // Blocks one write on a new group and waits until the watcher has ended it. It gives back the watcher.
    private Thread stallOne() throws Exception {
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, STALL_MILLIS);
        final Thread watcher = ProgressWatchdog.watcher();
        final Hold hold = below.hold("write");
        final Worker writer = worker("writer", () -> out.write(new byte[10]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        assertInstanceOf(StalledTransferException.class, writer.end());
        assertTrue(group.isAborted());
        assertTrue(group.isEmpty());
        return watcher;
    }

    @Test
    public void twoGroupsThatAreOverdueInOneScanAreBothAbortedWithALogThatThrows() throws Exception {
        log.failure = new IllegalStateException("log unavailable");
        freeze();
        final List<SocketGroup> groups = new ArrayList<>();
        final List<Worker> writers = new ArrayList<>();
        for (int count = 0; count < 2; count++) {
            final GatedStream below = new GatedStream();
            final SocketGroup group = groupOf(below);
            final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
            final Hold hold = below.hold("write");
            writers.add(worker("writer " + count, () -> out.write(new byte[10])));
            assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
            groups.add(group);
        }

        ProgressWatchdog.scan(ProgressWatchdog.now() + LONG_STALL_NANOS + 1);

        for (final SocketGroup group : groups) {
            assertTrue(group.isAborted());
        }
        for (final Worker writer : writers) {
            assertInstanceOf(StalledTransferException.class, writer.end());
        }
        assertEquals(0, ProgressWatchdog.registered());
        assertTrue(awaitNoReporter());
        assertTrue(log.lines.isEmpty());
    }

    @Test
    public void abortFromElsewhereThatWasFirstKeepsItsReasonAndGetsNoWarning() throws Exception {
        final Throwable error = stallNextToAnAbortFromElsewhere(true);
        assertTrue(error.getMessage().startsWith("stopped by the owner."), error.getMessage());
        assertTrue(awaitNoReporter());
        assertTrue(log.lines.isEmpty(), log.lines.toString());
    }

    @Test
    public void abortFromElsewhereThatWasSecondLeavesTheReasonAndTheWarningOfTheWatchdog() throws Exception {
        final Throwable error = stallNextToAnAbortFromElsewhere(false);
        assertTrue(error.getMessage().startsWith(WARNING + LONG_STALL_MILLIS + " ms."), error.getMessage());
        assertTrue(awaitLines(1));
        assertTrue(awaitNoReporter());
        assertEquals(1, log.lines.size(), log.lines.toString());
    }

    // One overdue write, and an abort by its owner right before or right after the watchdog sets its reason.
    private Throwable stallNextToAnAbortFromElsewhere(final boolean before) throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final AtomicBoolean once = new AtomicBoolean();
        final SocketGroup group = new SocketGroup() {
            @Override
            boolean claim(final String reason) {
                final boolean ofWatchdog = reason.startsWith(WARNING) && once.compareAndSet(false, true);
                if (ofWatchdog && before) {
                    abort("stopped by the owner");
                }
                final boolean first = super.claim(reason);
                if (ofWatchdog && !before) {
                    abort("stopped by the owner");
                }
                return first;
            }
        };
        group.add(below.socket());
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold hold = below.hold("write");
        final Worker writer = worker("writer", () -> out.write(new byte[10]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));

        ProgressWatchdog.scan(out.getWriteSince() + LONG_STALL_NANOS + 1);

        assertTrue(group.isEmpty());
        assertEquals(0, ProgressWatchdog.registered());
        return assertInstanceOf(StalledTransferException.class, writer.end());
    }

    @Test
    public void logThatHangsDoesNotKeepALaterGroupFromItsAbort() throws Exception {
        final Gate gate = gate(ProgressWatchdog.REPORTER_NAME);
        log.gate = gate;
        final Thread watcher = stallOne();
        // The first warning is inside the log and does not come back.
        assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
        assertTrue(log.lines.isEmpty());

        // A group that stalls after that is aborted and its writer ends, with the log still held.
        assertSame(watcher, stallOne());
        assertTrue(awaitScans());
        assertEquals(1, gate.release.getCount());
        assertTrue(log.lines.isEmpty());
        assertEquals(1, threads(ProgressWatchdog.REPORTER_NAME));

        gate.release.countDown();
        assertTrue(awaitLines(2));
        assertTrue(awaitNoReporter());
        assertEquals(2, log.count(WARNING));
        assertEquals(1, watcherThreads());
    }

    @Test
    public void linesOverTheLimitAreDroppedAndCountedAndNoScanWaits() throws Exception {
        final Thread test = Thread.currentThread();
        // The group can not be checked by the scans of this test. The watcher thread reads it with no error.
        final SocketGroup group = new SocketGroup() {
            @Override
            public boolean isAborted() {
                if (Thread.currentThread() == test) {
                    throw new IllegalStateException("broken group");
                }
                return super.isAborted();
            }
        };
        final WatchedOutputStream out = registered(new ByteArrayOutputStream(), group, LONG_STALL_MILLIS);
        final Gate gate = gate(ProgressWatchdog.REPORTER_NAME);
        log.gate = gate;
        try {
            ProgressWatchdog.scan(1);
            assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
            final int more = ProgressWatchdog.REPORT_LIMIT + 5;
            final long start = System.nanoTime();
            for (int count = 0; count < more; count++) {
                ProgressWatchdog.scan(1);
            }
            assertTrue(System.nanoTime() - start < 2 * SECOND, "a scan waited on the log");
            assertTrue(log.lines.isEmpty());
            assertEquals(1, threads(ProgressWatchdog.REPORTER_NAME));

            gate.release.countDown();
            assertTrue(awaitLines(ProgressWatchdog.REPORT_LIMIT + 2));
            assertTrue(awaitNoReporter());
            assertEquals(ProgressWatchdog.REPORT_LIMIT + 1, log.count("could not check a stream"));
            assertEquals(1, log.count("5 more lines of the write watchdog were dropped"));
            assertEquals(ProgressWatchdog.REPORT_LIMIT + 2, log.lines.size());
        } finally {
            ProgressWatchdog.unregister(out);
        }
    }

    @Test
    public void streamThatCanNotBeCheckedDoesNotEndTheScanOfTheOthers() throws Exception {
        freeze();
        final Thread test = Thread.currentThread();
        final List<WatchedOutputStream> broken = new ArrayList<>();
        // Several of them, so that one comes before the healthy stream in whatever order the registry has.
        for (int count = 0; count < 8; count++) {
            broken.add(registered(new ByteArrayOutputStream(), new SocketGroup() {
                @Override
                public boolean isAborted() {
                    if (Thread.currentThread() == test) {
                        throw new IllegalStateException("broken group");
                    }
                    return super.isAborted();
                }
            }, LONG_STALL_MILLIS));
        }
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold hold = below.hold("write");
        final Worker writer = worker("writer", () -> out.write(new byte[10]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        try {
            ProgressWatchdog.scan(out.getWriteSince() + LONG_STALL_NANOS + 1);

            assertTrue(group.isAborted());
            assertInstanceOf(StalledTransferException.class, writer.end());
            assertTrue(awaitLines(9));
            assertEquals(8, log.count("could not check a stream"));
            assertEquals(1, log.count(WARNING));
        } finally {
            for (final WatchedOutputStream one : broken) {
                ProgressWatchdog.unregister(one);
            }
        }
    }

    @Test
    public void stallThatWasSeenStaysTheReasonWhenTheNextSendIsTheOneThatFails() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final Gate gate = gate("scanner");
        // The scan waits after it has read the overdue send and before it sets its reason.
        final SocketGroup group = new SocketGroup() {
            @Override
            boolean claim(final String reason) {
                gate.pass();
                return super.claim(reason);
            }
        };
        group.add(below.socket());
        final WatchedOutputStream out = registered(below, group, LONG_STALL_MILLIS);
        final Hold first = below.hold("write");
        final Hold second = below.hold("write");
        final Worker writer = worker("writer", () -> {
            out.write(new byte[10]);
            out.write(new byte[20]);
        });
        assertTrue(first.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();
        final Worker scanner = worker("scanner", () -> ProgressWatchdog.scan(since + 61 * SECOND));
        assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
        assertFalse(group.isAborted());

        // The send that was seen ends, and the next one begins and waits.
        first.release.countDown();
        assertTrue(second.entered.await(5, TimeUnit.SECONDS));
        assertEquals(10, out.getBytesWritten());
        advance(TimeUnit.MILLISECONDS.toNanos(5));
        gate.release.countDown();
        assertNull(scanner.end());

        final StalledTransferException stalled = assertInstanceOf(StalledTransferException.class, writer.end());
        // The reason is the stall that the scan saw. The fields are those of the call that failed.
        assertEquals(WARNING + "61000 ms", group.reason());
        assertEquals(WARNING + "61000 ms. The call that failed was blocked for 5 ms after 10 bytes.",
                stalled.getMessage());
        assertEquals(5, stalled.getStallMillis());
        assertEquals(10, stalled.getBytesWritten());
        assertEquals("channel closed", stalled.getCause().getMessage());
        assertEquals(Fault.TRANSPORT, Faults.of(stalled));
        assertTrue(awaitLines(1));
        assertTrue(awaitNoReporter());
        assertEquals(1, log.lines.size(), log.lines.toString());
        assertEquals(WARNING + "61000 ms after 0 bytes. The watchdog closes the sockets of this connection.",
                log.lines.get(0));
    }

    @Test
    public void sendThatEndsWhileTheScanReadsTheStreamIsNotTakenForAStall() throws Exception {
        freeze();
        final GatedStream below = new GatedStream();
        final SocketGroup group = groupOf(below);
        final Hold hold = below.hold("write");
        final AtomicBoolean once = new AtomicBoolean();
        // The send ends between the two reads of the byte count that one scan makes.
        final WatchedOutputStream out = new WatchedOutputStream(below, group, LONG_STALL_MILLIS, "host") {
            @Override
            public long getWriteSince() {
                final long since = super.getWriteSince();
                if ("scanner".equals(Thread.currentThread().getName()) && once.compareAndSet(false, true)) {
                    hold.release.countDown();
                    final long end = System.nanoTime() + 5 * SECOND;
                    while (getBytesWritten() == 0 && System.nanoTime() < end) {
                        Thread.yield();
                    }
                }
                return since;
            }
        };
        ProgressWatchdog.register(out);
        final Worker writer = worker("writer", () -> out.write(new byte[10]));
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS));
        final long since = out.getWriteSince();

        assertNull(worker("scanner", () -> ProgressWatchdog.scan(since + 61 * SECOND)).end());

        assertTrue(once.get());
        assertNull(writer.end());
        assertEquals(10, out.getBytesWritten());
        assertFalse(group.isAborted());
        assertTrue(log.lines.isEmpty());
        ProgressWatchdog.unregister(out);
    }

}
