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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link WatchedServerSocket} in its group.
 */
public class WatchedServerSocketTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /**
     * A thread that waits in accept.
     */
    public static final class Accepting {

        private final AtomicReference<Object> outcome = new AtomicReference<>();

        private final Thread thread;

        /**
         * Starts the accept and returns when the thread waits inside it.
         *
         * @param server the listening socket.
         * @throws InterruptedException when the test is interrupted.
         */
        public Accepting(final ServerSocket server) throws InterruptedException {
            thread = new Thread(() -> {
                try {
                    outcome.set(server.accept());
                } catch (final IOException e) {
                    outcome.set(e);
                }
            }, "test-accept");
            thread.setDaemon(true);
            thread.start();
            int seen = 0;
            for (int tries = 0; tries < 100 && seen < 2; tries++) {
                Thread.sleep(50);
                seen = insideAccept() ? seen + 1 : 0;
            }
            assertEquals(2, seen, "the thread does not wait in accept");
        }

        private boolean insideAccept() {
            int frames = 0;
            for (final StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getMethodName().toLowerCase().contains("accept")) {
                    frames++;
                }
            }
            // The accept of the test and at least one frame of the JDK below it.
            return frames >= 2 && outcome.get() == null;
        }

        /**
         * Waits for the end of the accept.
         *
         * @param millis how long to wait.
         * @return the accepted socket or the exception.
         * @throws InterruptedException when the test is interrupted.
         */
        public Object await(final long millis) throws InterruptedException {
            thread.join(millis);
            assertFalse(thread.isAlive(), "the accept did not end within " + millis + " ms");
            return outcome.get();
        }
    }

    @Test
    public void abortEndsAPendingAcceptWithAFiniteTimeoutAndWithNone() throws Exception {
        for (final int timeout : new int[] {60000, 0}) {
            final SocketGroup group = new SocketGroup();
            final ServerSocket server = new WatchedServerSocketFactory(group, 0).createServerSocket(0, 1, LOOPBACK);
            server.setSoTimeout(timeout);
            assertFalse(group.isEmpty());
            assertTrue(group.getSockets().isEmpty());
            final Accepting accepting = new Accepting(server);

            group.abort("stop");

            assertInstanceOf(SocketException.class, accepting.await(1000), "timeout " + timeout);
            assertTrue(server.isClosed());
            assertTrue(group.isEmpty());
        }
    }

    @Test
    public void listenerOfAnAbortedGroupIsClosedAtOnce() throws Exception {
        final SocketGroup group = new SocketGroup();
        group.abort("gone");
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);

        final ServerSocket bound = factory.createServerSocket(0, 1, LOOPBACK);
        final ServerSocket unbound = factory.createServerSocket();

        assertTrue(bound.isClosed());
        assertTrue(unbound.isClosed());
        assertThrows(SocketException.class, bound::accept);
        assertTrue(group.isEmpty());
    }

    @Test
    public void normalCloseAndAFailedBindLeaveNothingInTheGroup() throws Exception {
        final SocketGroup group = new SocketGroup();
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);
        final ServerSocket server = factory.createServerSocket(0, 1, LOOPBACK);
        assertFalse(group.isEmpty());

        assertThrows(BindException.class, () -> factory.createServerSocket(server.getLocalPort(), 1, LOOPBACK));
        server.close();
        server.close();

        assertTrue(group.isEmpty());
        assertFalse(group.isAborted());
    }

    @Test
    public void acceptedSocketThatIsHeldBeforeItEntersTheGroupIsClosedAfterAnAbort() throws Exception {
        final CountDownLatch accepted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // The accept waits with a connected socket that the group does not know yet.
        final SocketGroup group = new SocketGroup() {
            @Override
            public void add(final Socket socket) {
                accepted.countDown();
                await(release);
                super.add(socket);
            }
        };
        final ServerSocket server = new WatchedServerSocketFactory(group, 0).createServerSocket(0, 1, LOOPBACK);
        final AtomicReference<Object> outcome = new AtomicReference<>();
        final Thread accepter = started("accepter", () -> {
            try {
                outcome.set(server.accept());
            } catch (final IOException e) {
                outcome.set(e);
            }
        });
        try (Socket client = new Socket(LOOPBACK, server.getLocalPort())) {
            client.setSoTimeout(5000);
            try {
                assertTrue(accepted.await(5, TimeUnit.SECONDS));
                group.abort("stop");
                assertTrue(server.isClosed());
            } finally {
                release.countDown();
                assertEnded(accepter);
            }
            // The socket comes back closed, and the peer sees the end of the connection.
            final Socket result = assertInstanceOf(Socket.class, outcome.get());
            assertTrue(result.isClosed());
            assertTrue(group.isEmpty());
            assertEquals(-1, readEnd(client));
        }
    }

    @Test
    public void abortWhileTheFirstBindHasPassedItsClosedCheckLeavesNoListeningPort() throws Exception {
        final int port = freePort();
        final Gate gate = new Gate("caller");
        final SocketGroup group = new SocketGroup();
        // The bind of the JDK asks this after its closed check and before it makes or uses the native socket.
        final ServerSocket server = new WatchedServerSocket(group, 0) {
            @Override
            public boolean isBound() {
                gate.pass();
                return super.isBound();
            }
        };
        final AtomicReference<Object> outcome = new AtomicReference<>("none");
        final Thread caller = started("caller", () -> {
            try {
                server.bind(new InetSocketAddress(LOOPBACK, port), 1);
                outcome.set("bound");
            } catch (final IOException e) {
                outcome.set(e);
            }
        });
        try {
            assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
            group.abort("stop");
            assertTrue(server.isClosed());
            assertTrue(group.isEmpty());
        } finally {
            gate.release.countDown();
            assertEnded(caller);
        }
        server.close();

        assertInstanceOf(IOException.class, outcome.get());
        assertRefused(port);
    }

    @Test
    public void listenerOfAnAbortedGroupLeavesNoListeningPort() throws Exception {
        final int port = freePort();
        final SocketGroup group = new SocketGroup();
        group.abort("gone");
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);

        for (int form = 1; form <= 3; form++) {
            final ServerSocket bound = bound(factory, form, port);
            assertTrue(bound.isClosed(), "form " + form);
            assertFalse(bound.isBound(), "form " + form);
            assertRefused(port);
        }
        final ServerSocket unbound = factory.createServerSocket();
        assertThrows(IOException.class, () -> unbound.bind(new InetSocketAddress(LOOPBACK, port), 1));
        assertRefused(port);
        assertTrue(group.isEmpty());
    }

    @Test
    public void abortWhileTheBindOfABoundListenerHasPassedItsClosedCheckLeavesNoListeningPort() throws Exception {
        final int port = freePort();
        final Gate gate = new Gate("caller");
        final SocketGroup group = new SocketGroup();
        final AtomicReference<Object> outcome = new AtomicReference<>("none");
        // The constructor is held inside its bind, after the group took the listener.
        final Thread caller = started("caller", () -> {
            try {
                outcome.set(new WatchedServerSocket(group, 0, port, 1, LOOPBACK) {
                    @Override
                    public boolean isBound() {
                        gate.pass();
                        return super.isBound();
                    }
                });
            } catch (final IOException e) {
                outcome.set(e);
            }
        });
        try {
            assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
            assertFalse(group.isEmpty());
            assertRefused(port);
            group.abort("stop");
            assertTrue(group.isEmpty());
            assertRefused(port);
        } finally {
            gate.release.countDown();
            assertEnded(caller);
        }

        assertInstanceOf(IOException.class, outcome.get());
        assertTrue(group.isEmpty());
        assertRefused(port);
    }

    @Test
    public void everyBoundFormListensOnItsPortAndAddressAndAcceptsIntoTheGroup() throws Exception {
        final SocketGroup group = new SocketGroup();
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);
        for (int form = 1; form <= 3; form++) {
            final int port = freePort();
            try (ServerSocket server = bound(factory, form, port)) {
                server.setSoTimeout(5000);
                assertTrue(server.isBound(), "form " + form);
                assertEquals(port, server.getLocalPort(), "form " + form);
                // Only the form with an address is bound to one address.
                assertEquals(form == 3, server.getInetAddress().isLoopbackAddress(), "form " + form);
                assertEquals(form != 3, server.getInetAddress().isAnyLocalAddress(), "form " + form);
                assertFalse(group.isEmpty());
                try (Socket client = new Socket(LOOPBACK, port); Socket accepted = server.accept()) {
                    assertInstanceOf(WatchedSocket.class, accepted, "form " + form);
                    assertEquals(1, group.getSockets().size(), "form " + form);
                    assertTrue(client.isConnected());
                }
            }
            assertTrue(group.isEmpty(), "form " + form);
        }
        // The port zero gives any free port, and a backlog under one gives the default of the JDK.
        try (ServerSocket server = factory.createServerSocket(0, 0, LOOPBACK)) {
            assertTrue(server.getLocalPort() > 0);
            new Socket(LOOPBACK, server.getLocalPort()).close();
        }
        try (ServerSocket server = factory.createServerSocket(0, -1)) {
            assertTrue(server.getLocalPort() > 0);
            new Socket(LOOPBACK, server.getLocalPort()).close();
        }
        assertTrue(group.isEmpty());
        assertFalse(group.isAborted());
    }

    @Test
    public void everyBoundFormRefusesAPortOutOfRangeAsTheJdkDoesAndLeavesNothing() throws Exception {
        final List<ServerSocket> seen = new ArrayList<>();
        final SocketGroup group = new SocketGroup() {
            @Override
            public void addListener(final ServerSocket listener) {
                seen.add(listener);
                super.addListener(listener);
            }
        };
        final SocketGroup gone = new SocketGroup();
        gone.abort("gone");
        for (final int port : new int[] {-1, 65536}) {
            final String text = assertThrows(IllegalArgumentException.class,
                    () -> new ServerSocket(port, 1, LOOPBACK)).getMessage();
            for (final SocketGroup each : new SocketGroup[] {group, gone}) {
                final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(each, 0);
                for (int form = 1; form <= 3; form++) {
                    final int which = form;
                    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                            () -> bound(factory, which, port), "form " + form);
                    assertEquals(text, refused.getMessage(), "form " + form);
                }
            }
        }
        // The port is checked before a socket is made, so the group was not asked.
        assertTrue(seen.isEmpty());
        assertTrue(group.isEmpty());
        assertFalse(group.isAborted());
    }

    @Test
    public void listenerThatTheGroupDoesNotTakeIsClosed() throws Exception {
        final int port = freePort();
        final List<ServerSocket> seen = new ArrayList<>();
        final SocketGroup group = new SocketGroup() {
            @Override
            public void addListener(final ServerSocket listener) {
                seen.add(listener);
                throw new IllegalStateException("no room");
            }
        };
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);

        assertThrows(IllegalStateException.class, () -> factory.createServerSocket(port, 1, LOOPBACK));
        assertThrows(IllegalStateException.class, () -> factory.createServerSocket());

        assertEquals(2, seen.size());
        for (final ServerSocket listener : seen) {
            assertTrue(listener.isClosed());
        }
        assertRefused(port);
    }

    @Test
    public void acceptNextToAnAbortNeverHandsOutAnOpenSocket() throws Exception {
        for (int round = 0; round < 200; round++) {
            final SocketGroup group = new SocketGroup();
            final ServerSocket server = new WatchedServerSocketFactory(group, 0).createServerSocket(0, 1, LOOPBACK);
            try (Socket client = new Socket(LOOPBACK, server.getLocalPort())) {
                final CountDownLatch start = new CountDownLatch(1);
                final AtomicReference<Object> outcome = new AtomicReference<>();
                final Thread accepter = started("accepter", () -> {
                    await(start);
                    try {
                        outcome.set(server.accept());
                    } catch (final IOException e) {
                        outcome.set(e);
                    }
                });
                final Thread aborter = started("aborter", () -> {
                    await(start);
                    group.abort("race");
                });
                start.countDown();
                assertEnded(accepter);
                assertEnded(aborter);
                final Object result = outcome.get();
                if (result instanceof Socket) {
                    assertTrue(((Socket) result).isClosed(), "round " + round);
                } else {
                    assertInstanceOf(IOException.class, result, "round " + round);
                }
                assertTrue(server.isClosed(), "round " + round);
                assertTrue(group.isEmpty(), "round " + round);
            }
        }
    }

    @Test
    public void fixtureClosesAPeerThatItAcceptedWhileItsCloseBegan() throws Exception {
        final WatchedSockets fixture = new WatchedSockets();
        final Gate gate = new Gate("test-acceptor");
        fixture.beforeOwn = gate::pass;
        final ServerSocket server = fixture.silentServer();
        final Thread closer;
        try (Socket client = new Socket(LOOPBACK, server.getLocalPort())) {
            client.setSoTimeout(5000);
            try {
                // The acceptor has a connected socket that the fixture does not own yet.
                assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.owned().isEmpty());
                closer = started("closer", fixture::close);
                // The close has closed the listener and waits for the acceptor.
                final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isClosed() || closer.getState() != Thread.State.TIMED_WAITING) {
                    assertTrue(System.nanoTime() < end, "the close did not wait for the acceptor");
                    Thread.sleep(2);
                }
                assertTrue(fixture.owned().isEmpty());
            } finally {
                gate.release.countDown();
            }
            assertEnded(closer);
            // The socket that came late is owned and closed, and its peer sees the end.
            assertEquals(1, fixture.owned().size());
            assertTrue(fixture.owned().get(0).isClosed());
            assertEquals(-1, readEnd(client));
        }
    }

    @Test
    public void interruptedFixtureCloseStillClosesALatePeer() throws Exception {
        final Gate gate = new Gate("test-acceptor");
        try (WatchedSockets fixture = new WatchedSockets()) {
            fixture.beforeOwn = gate::pass;
            final ServerSocket server = fixture.silentServer();
            try (Socket peer = new Socket(LOOPBACK, server.getLocalPort())) {
                peer.setSoTimeout(3000);
                try {
                    assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
                    Thread.currentThread().interrupt();
                    try {
                        final IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::close);
                        assertTrue(failure.getMessage().contains("an acceptor"));
                    } finally {
                        assertTrue(Thread.interrupted(), "the close must preserve the interrupt");
                    }
                } finally {
                    gate.release.countDown();
                }
                // Check the wire before the outer cleanup gets a second chance to close the fixture.
                assertEquals(-1, peer.getInputStream().read());
                assertEquals(1, fixture.owned().size());
            }
        }
    }

    @Test
    public void fixtureOwnsASilentClientFromItsStartAndClosesBothEnds() throws Exception {
        final WatchedSockets fixture = new WatchedSockets();
        final SocketGroup group = new SocketGroup();
        final Socket accepted;
        final Socket client;
        try (ServerSocket server = new WatchedServerSocketFactory(group, 0).createServerSocket(0, 1, LOOPBACK)) {
            client = fixture.silentClient(server.getLocalPort());
            assertEquals(1, fixture.owned().size());
            assertSame(client, fixture.owned().get(0));
            accepted = server.accept();
            accepted.setSoTimeout(5000);
        }
        // An interrupt of the test thread does not take the client away from the fixture.
        Thread.currentThread().interrupt();
        try {
            fixture.close();
        } finally {
            assertTrue(Thread.interrupted());
        }

        assertTrue(client.isClosed());
        assertEquals(-1, readEnd(accepted));
        accepted.close();
        assertTrue(group.isEmpty());
    }

    /**
     * Holds the thread with one name once, at a point that a test chooses.
     */
    public static final class Gate {

        public final CountDownLatch reached = new CountDownLatch(1);

        public final CountDownLatch release = new CountDownLatch(1);

        private final AtomicBoolean once = new AtomicBoolean();

        private final String threadName;

        public Gate(final String threadName) {
            this.threadName = threadName;
        }

        public void pass() {
            if (threadName.equals(Thread.currentThread().getName()) && once.compareAndSet(false, true)) {
                reached.countDown();
                await(release);
            }
        }
    }

    /**
     * Calls one of the three forms of the factory that bind: by port, by port and backlog, and by port, backlog and
     * address. The backlog is one and the address is the loopback address.
     *
     * @param factory the factory.
     * @param form 1, 2 or 3.
     * @param port the port.
     * @return what the factory gave.
     * @throws IOException when the factory fails.
     */
    public static ServerSocket bound(final WatchedServerSocketFactory factory, final int form, final int port)
            throws IOException {
        switch (form) {
            case 1:
                return factory.createServerSocket(port);
            case 2:
                return factory.createServerSocket(port, 1);
            default:
                return factory.createServerSocket(port, 1, LOOPBACK);
        }
    }

    /**
     * Gets a port of the loopback address that nothing listens on now.
     *
     * @return the port.
     * @throws IOException when no port is free.
     */
    public static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, LOOPBACK)) {
            return probe.getLocalPort();
        }
    }

    /**
     * Asserts that nothing listens on a port, by a connect that must be refused.
     *
     * @param port the port on the loopback address.
     */
    public static void assertRefused(final int port) {
        assertThrows(ConnectException.class, () -> new Socket(LOOPBACK, port).close(),
                "something listens on the port " + port);
    }

    // Reads what the other side sent. A closed connection gives the end of the stream or a reset.
    private static int readEnd(final Socket socket) throws IOException {
        try {
            return socket.getInputStream().read();
        } catch (final SocketTimeoutException e) {
            throw e;
        } catch (final IOException e) {
            return -1;
        }
    }

    private static Thread started(final String name, final Runnable work) {
        final Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void assertEnded(final Thread thread) throws InterruptedException {
        thread.join(5000);
        assertFalse(thread.isAlive(), "the thread " + thread.getName() + " did not end");
    }

    // A wait of a test thread has an end, so a failed test leaves no thread behind.
    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not let the thread go on");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
