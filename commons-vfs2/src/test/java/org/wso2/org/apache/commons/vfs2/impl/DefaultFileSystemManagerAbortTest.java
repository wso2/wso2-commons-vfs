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
package org.wso2.org.apache.commons.vfs2.impl;

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

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.AbstractOriginatingFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.url.UrlFileProvider;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.StalledTransferException;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketFactory;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketTest;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketTest.Accepting;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketTest.Gate;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocketFactory;
import org.wso2.org.apache.commons.vfs2.util.WatchedSockets;
import org.wso2.org.apache.commons.vfs2.util.WatchedSockets.Writer;

/**
 * Tests the abort calls of {@link DefaultFileSystemManager} and the base calls of {@link AbstractFileSystem}.
 */
public class DefaultFileSystemManagerAbortTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private static final String FORK_PACKAGE = "org.wso2.org.apache.commons.vfs2";

    @TempDir
    Path folder;

    private final ExecutorService callers = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "test-caller");
        thread.setDaemon(true);
        return thread;
    });

    // What the teardown lets go and waits for, also after a failed assertion.
    private final List<CountDownLatch> releases = new CopyOnWriteArrayList<>();

    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    private WatchedSockets peers;

    private DefaultFileSystemManager manager;

    private RemoteProvider provider;

    private DefaultLocalFileProvider localProvider;

    /**
     * A file system with one socket group, as a remote provider has it.
     */
    private static final class RemoteFileSystem extends AbstractFileSystem {

        private final List<String> calls;

        private final SocketGroup group;

        private volatile boolean alive = true;

        private volatile int handedOut;

        private volatile int open;

        // The calls on this one file system. The list of the provider has the calls on all of them.
        private final List<String> own = new CopyOnWriteArrayList<>();

        // A test sets it to hold the close of this file system.
        private volatile Gate closeGate;

        RemoteFileSystem(final FileName rootName, final FileSystemOptions options, final SocketGroup group,
                final List<String> calls) {
            super(rootName, null, options);
            this.group = group;
            this.calls = calls;
        }

        @Override
        public void abort() {
            calls.add("abort");
            own.add("abort");
            group.abort("The file system was aborted");
        }

        @Override
        protected void doCloseCommunicationLink() {
            calls.add("close");
            own.add("close");
            final Gate gate = closeGate;
            if (gate != null) {
                gate.pass();
            }
        }

        @Override
        public boolean isAliveLocally() {
            return alive;
        }

        @Override
        public int getHandedOutCount() {
            return handedOut;
        }

        @Override
        public int getOpenClientCount() {
            return open;
        }

        RemoteFileSystem with(final int nowHandedOut, final int nowOpen) {
            handedOut = nowHandedOut;
            open = nowOpen;
            return this;
        }

        @Override
        protected void addCapabilities(final Collection<Capability> caps) {
            caps.add(Capability.READ_CONTENT);
        }

        @Override
        protected FileObject createFile(final AbstractFileName name) {
            return new AbstractFileObject<RemoteFileSystem>(name, this) {
                @Override
                protected FileType doGetType() {
                    return FileType.IMAGINARY;
                }

                @Override
                protected String[] doListChildren() {
                    return new String[0];
                }

                @Override
                protected long doGetContentSize() {
                    return 0;
                }

                @Override
                protected InputStream doGetInputStream() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    private static final class RemoteProvider extends AbstractOriginatingFileProvider {

        private final List<String> calls = new CopyOnWriteArrayList<>();

        private final AtomicReference<SocketGroup> group = new AtomicReference<>();

        private final AtomicInteger asked = new AtomicInteger();

        RemoteFileSystem open(final String uri) throws FileSystemException {
            return (RemoteFileSystem) getFileSystem(parseUri(null, uri), null);
        }

        // Puts a new file system under the key of the one that is cached for this root.
        RemoteFileSystem replace(final String uri) throws FileSystemException {
            final FileName rootName = parseUri(null, uri);
            final RemoteFileSystem next = (RemoteFileSystem) doCreateFileSystem(rootName, null);
            addFileSystem(rootName, next);
            return next;
        }

        @Override
        public List<AbstractFileSystem> getCachedFileSystems() {
            asked.incrementAndGet();
            return super.getCachedFileSystems();
        }

        @Override
        protected FileSystem doCreateFileSystem(final FileName rootName, final FileSystemOptions options) {
            final DefaultFileSystemManager owner = (DefaultFileSystemManager) getContext().getFileSystemManager();
            group.set(owner.newSocketGroup());
            return new RemoteFileSystem(rootName, options, group.get(), calls);
        }

        @Override
        public Collection<Capability> getCapabilities() {
            return Collections.singletonList(Capability.READ_CONTENT);
        }
    }

    @BeforeEach
    public void open() throws Exception {
        peers = new WatchedSockets();
        manager = new DefaultFileSystemManager();
        localProvider = new DefaultLocalFileProvider();
        provider = new RemoteProvider();
        manager.addProvider("file", localProvider);
        manager.addProvider("remote", provider);
        manager.init();
    }

    // Lets every held thread go, closes the peers, and checks that no thread of the test is left.
    @AfterEach
    public void close() throws Exception {
        manager.beforeTrack = null;
        for (final CountDownLatch release : releases) {
            release.countDown();
        }
        try {
            peers.close();
            manager.close();
        } finally {
            callers.shutdownNow();
            final boolean callersEnded = callers.awaitTermination(5, TimeUnit.SECONDS);
            final List<String> left = new ArrayList<>();
            for (final Thread thread : threads) {
                thread.join(5000);
                if (thread.isAlive()) {
                    left.add(thread.getName());
                }
            }
            assertTrue(callersEnded, "a caller of the test did not end");
            assertEquals(Collections.emptyList(), left);
        }
    }

    @Test
    public void abortAllEndsABlockedWriteOnEveryKindOfWatchedSocketWhileEveryMonitorIsHeld() throws Exception {
        final ServerSocket server = peers.silentServer();
        final AbstractFileSystem local = localFileSystem();
        final RemoteFileSystem remote = provider.open("remote://host/");
        final SocketGroup group = provider.group.get();
        // The watchdog is off, so only the abort can end these writes.
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, 0);

        final Socket connectedByFactory = factory.createSocket(LOOPBACK, server.getLocalPort());
        final Socket connectedByCaller = WatchedSockets.small(factory.createSocket());
        connectedByCaller.connect(new InetSocketAddress(LOOPBACK, server.getLocalPort()), 5000);
        final Socket accepted;
        try (ServerSocket active = new WatchedServerSocketFactory(manager.newSocketGroup(), 0)
                .createServerSocket(0, 1, LOOPBACK)) {
            // The fixture owns the silent client from its start and closes it at the end of the test.
            peers.silentClient(active.getLocalPort());
            accepted = active.accept();
        }
        final List<Writer> writers = Arrays.asList(peers.blockedWriter(connectedByFactory),
                peers.blockedWriter(connectedByCaller), peers.blockedWriter(accepted));
        for (final Writer writer : writers) {
            assertFalse(writer.hasFailed());
        }
        assertEquals(2, manager.socketGroupCount());

        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = released();
        callers.submit(() -> holdAll(Arrays.asList(manager, provider, localProvider, manager.getFilesCache(), local,
                remote), held, release));
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            final long start = System.nanoTime();
            within(1000, () -> remote.abort());
            within(1000, () -> local.abort());
            within(1000, () -> manager.abortAll());
            for (final Writer writer : writers) {
                final IOException error = writer.awaitError(1000);
                assertInstanceOf(StalledTransferException.class, error);
                assertEquals(Fault.TRANSPORT, Faults.of(error));
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
            // A second call, still with every monitor held.
            within(1000, () -> manager.abortAll());
        } finally {
            release.countDown();
        }
        assertTrue(connectedByFactory.isClosed());
        assertTrue(connectedByCaller.isClosed());
        assertTrue(accepted.isClosed());
        assertEquals(0, manager.socketGroupCount());
    }

    @Test
    public void abortAllEndsABlockedReadAndABlockedConnect() throws Exception {
        final ServerSocket server = peers.silentServer();
        final WatchedSocketFactory factory = new WatchedSocketFactory(manager.newSocketGroup(), 0, 0);
        final Socket reading = factory.createSocket(LOOPBACK, server.getLocalPort());
        final AtomicReference<Thread> reader = new AtomicReference<>();
        final Future<Object> read = callers.submit(() -> {
            reader.set(Thread.currentThread());
            return reading.getInputStream().read();
        });

        // A queue of one that nobody accepts from. A connect behind the full queue gets no answer.
        final ServerSocket full = new ServerSocket(0, 1, LOOPBACK);
        final List<Socket> fillers = new CopyOnWriteArrayList<>();
        final Socket connecting = factory.createSocket();
        final AtomicReference<Thread> connector = new AtomicReference<>();
        final Future<Object> connect = callers.submit(() -> {
            for (int count = 0; count < 4; count++) {
                final Socket filler = new Socket();
                fillers.add(filler);
                try {
                    filler.connect(new InetSocketAddress(LOOPBACK, full.getLocalPort()), 300);
                } catch (final IOException e) {
                    break;
                }
            }
            connector.set(Thread.currentThread());
            connecting.connect(new InetSocketAddress(LOOPBACK, full.getLocalPort()), 0);
            return null;
        });
        try {
            // Both threads are seen inside the call of the JDK, so the abort ends a call that waits.
            assertTrue(waitsInside(reader, "read", read), "the read does not wait");
            final boolean connectBlocked = waitsInside(connector, "connect", connect);

            within(1000, () -> manager.abortAll());

            assertFails(read);
            assertTrue(reading.isClosed());
            assumeTrue(connectBlocked, "This host answered the connect behind a full queue");
            assertFails(connect);
            assertTrue(connecting.isClosed());
        } finally {
            full.close();
            for (final Socket filler : fillers) {
                filler.close();
            }
        }
    }

    // True when the thread was seen twice inside a method of the JDK with this name, with its call not ended.
    private static boolean waitsInside(final AtomicReference<Thread> thread, final String method,
            final Future<Object> call) throws InterruptedException {
        int seen = 0;
        for (int tries = 0; tries < 200 && seen < 2 && !call.isDone(); tries++) {
            Thread.sleep(50);
            boolean inside = false;
            final Thread now = thread.get();
            if (now != null) {
                for (final StackTraceElement frame : now.getStackTrace()) {
                    inside |= frame.getClassName().startsWith("java.") || frame.getClassName().startsWith("sun.")
                            ? frame.getMethodName().toLowerCase().contains(method) : false;
                }
            }
            seen = inside ? seen + 1 : 0;
        }
        return seen == 2 && !call.isDone();
    }

    @Test
    public void abortAllIsSafeOnAManagerThatNeverConnectedAndStopsEveryLaterConnect() throws Exception {
        final SocketGroup early = manager.newSocketGroup();
        manager.abortAll();
        manager.abortAll();

        // A group that holds nothing is not kept. It is aborted with its first socket.
        assertTrue(new WatchedSocketFactory(early, 5000, 0).createSocket().isClosed());
        assertTrue(early.isAborted());
        final SocketGroup late = manager.newSocketGroup();
        assertTrue(late.isAborted());
        assertTrue(new WatchedSocketFactory(late, 5000, 0).createSocket().isClosed());
        assertEquals(0, manager.socketGroupCount());
        // The owner closes its manager after the abort.
        manager.close();
    }

    @Test
    public void groupThatWasEmptyWhenAbortAllRanIsAbortedWithItsNextSocket() throws Exception {
        final SocketGroup group = manager.newSocketGroup();
        final Socket first = new WatchedSocketFactory(group, 5000, 0).createSocket();
        first.close();
        assertEquals(0, manager.socketGroupCount());

        manager.abortAll();
        assertFalse(group.isAborted());

        final Socket second = new WatchedSocketFactory(group, 5000, 0).createSocket();
        assertTrue(group.isAborted());
        assertTrue(second.isClosed());
        assertEquals(0, manager.socketGroupCount());
    }

    @Test
    public void abortAllNextToCloseIsSafe() throws Exception {
        for (int round = 0; round < 50; round++) {
            final DefaultFileSystemManager one = new DefaultFileSystemManager();
            final RemoteProvider remoteProvider = new RemoteProvider();
            one.addProvider("file", new DefaultLocalFileProvider());
            one.addProvider("remote", remoteProvider);
            one.init();
            one.resolveFile(folder.toUri().toString());
            remoteProvider.open("remote://host/");
            final ServerSocket server = peers.silentServer();
            final Socket socket = new WatchedSocketFactory(remoteProvider.group.get(), 5000, 0)
                    .createSocket(LOOPBACK, server.getLocalPort());
            final CountDownLatch start = new CountDownLatch(1);
            final Future<Object> abort = callers.submit(() -> {
                start.await();
                one.abortAll();
                one.abortAll();
                return null;
            });
            final Future<Object> close = callers.submit(() -> {
                start.await();
                one.close();
                return null;
            });
            start.countDown();
            abort.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertTrue(socket.isClosed(), "round " + round);
            assertEquals(0, one.socketGroupCount(), "round " + round);
            server.close();
        }
    }

    @Test
    public void managerKeepsAGroupFromItsFirstSocketUntilItHoldsNothing() throws Exception {
        final SocketGroup group = manager.newSocketGroup();
        assertEquals(0, manager.socketGroupCount());
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, 0);

        // In the map before the socket connects.
        final Socket socket = factory.createSocket();
        assertFalse(socket.isConnected());
        assertEquals(1, manager.socketGroupCount());
        socket.close();
        assertEquals(0, manager.socketGroupCount());

        // A parent stays while a child lives, also with no socket of its own.
        final SocketGroup child = manager.newSocketGroup();
        group.addChild(child);
        assertEquals(1, manager.socketGroupCount());
        final ServerSocket server = peers.silentServer();
        final Socket ofChild = new WatchedSocketFactory(child, 5000, 0).createSocket(LOOPBACK, server.getLocalPort());
        assertEquals(2, manager.socketGroupCount());
        final Socket own = factory.createSocket();
        own.close();
        assertEquals(2, manager.socketGroupCount());

        manager.abortAll();
        assertTrue(ofChild.isClosed());
        assertTrue(group.isAborted());
        assertTrue(child.isAborted());
        assertEquals(0, manager.socketGroupCount());
    }

    @Test
    public void groupWithOnlyAListenerIsKeptAndAbortAllEndsItsPendingAccept() throws Exception {
        final SocketGroup group = manager.newSocketGroup();
        final ServerSocket listener = new WatchedServerSocketFactory(group, 0).createServerSocket(0, 1, LOOPBACK);
        listener.setSoTimeout(60000);
        assertEquals(1, manager.socketGroupCount());
        final Accepting accepting = new Accepting(listener);

        within(1000, () -> manager.abortAll());

        assertInstanceOf(IOException.class, accepting.await(1000));
        assertTrue(listener.isClosed());
        assertEquals(0, manager.socketGroupCount());

        final SocketGroup closing = new DefaultFileSystemManager().newSocketGroup();
        new WatchedServerSocketFactory(closing, 0).createServerSocket(0, 1, LOOPBACK).close();
        assertTrue(closing.isEmpty());
    }

    // Holds the thread with this name once, between a change of its group and the update of the map.
    private CountDownLatch[] holdTrackOf(final String threadName) {
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = released();
        final AtomicBoolean once = new AtomicBoolean();
        manager.beforeTrack = () -> {
            if (threadName.equals(Thread.currentThread().getName()) && once.compareAndSet(false, true)) {
                reached.countDown();
                await(release);
            }
        };
        return new CountDownLatch[] {reached, release};
    }

    // A latch that the teardown counts down, so that a failed assertion leaves no thread waiting.
    private CountDownLatch released() {
        final CountDownLatch release = new CountDownLatch(1);
        releases.add(release);
        return release;
    }

    private Gate gate(final String threadName) {
        final Gate gate = new Gate(threadName);
        releases.add(gate.release);
        return gate;
    }

    // A wait of a test thread has an end.
    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not let the thread go on");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Thread named(final String name, final Runnable work) {
        final Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        return thread;
    }

    private static void assertEnded(final Thread thread) throws InterruptedException {
        thread.join(5000);
        assertFalse(thread.isAlive(), "the thread " + thread.getName() + " did not end");
    }

    @Test
    public void abortAllBeforeTheManagerKnowsAListenerByPortLeavesNoPortForAPeer() throws Exception {
        assertNoPeerReachesAListenerThatAbortAllDidNotSee(1);
    }

    @Test
    public void abortAllBeforeTheManagerKnowsAListenerByPortAndBacklogLeavesNoPortForAPeer() throws Exception {
        assertNoPeerReachesAListenerThatAbortAllDidNotSee(2);
    }

    @Test
    public void abortAllBeforeTheManagerKnowsAListenerByPortBacklogAndAddressLeavesNoPortForAPeer()
            throws Exception {
        assertNoPeerReachesAListenerThatAbortAllDidNotSee(3);
    }

    // The creator is held after its group took the listener and before the manager knows that group. The stop comes
    // and returns. A real peer then connects to the port, while the creator is still held.
    private void assertNoPeerReachesAListenerThatAbortAllDidNotSee(final int form) throws Exception {
        final int port = WatchedServerSocketTest.freePort();
        final SocketGroup group = manager.newSocketGroup();
        final WatchedServerSocketFactory factory = new WatchedServerSocketFactory(group, 0);
        final CountDownLatch[] gate = holdTrackOf("creator");
        final AtomicReference<Object> outcome = new AtomicReference<>("none");
        final Thread creator = named("creator", () -> {
            try {
                outcome.set(WatchedServerSocketTest.bound(factory, form, port));
            } catch (final IOException | RuntimeException e) {
                outcome.set(e);
            }
        });
        assertTrue(gate[0].await(5, TimeUnit.SECONDS));
        assertFalse(group.isEmpty());
        assertEquals(0, manager.socketGroupCount());

        within(1000, () -> manager.abortAll());

        // The stop has returned, it did not see the group, and the creator is still held.
        assertFalse(group.isAborted());
        assertTrue(creator.isAlive());
        assertEquals("none", outcome.get());
        WatchedServerSocketTest.assertRefused(port);

        gate[1].countDown();
        assertEnded(creator);
        // The factory gives the listener back closed. It was never bound, and nothing is left.
        final ServerSocket listener = assertInstanceOf(ServerSocket.class, outcome.get());
        assertTrue(listener.isClosed());
        assertFalse(listener.isBound());
        assertTrue(group.isAborted());
        assertTrue(group.isEmpty());
        assertEquals(0, manager.socketGroupCount());
        WatchedServerSocketTest.assertRefused(port);
    }

    @Test
    public void socketRegisteredWhileAnOlderRemovalWaitsIsReachedByAbortAll() throws Exception {
        final ServerSocket server = peers.silentServer();
        final SocketGroup group = manager.newSocketGroup();
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, 0);
        final Socket first = factory.createSocket();
        final CountDownLatch[] gate = holdTrackOf("delayed");
        // The last socket is gone from the group, and the update of the map for that removal waits.
        final Thread delayed = named("delayed", () -> closeQuietly(first));
        assertTrue(gate[0].await(5, TimeUnit.SECONDS));
        assertTrue(group.isEmpty());

        final Socket second = factory.createSocket(LOOPBACK, server.getLocalPort());
        assertTrue(second.isConnected());
        assertEquals(1, manager.socketGroupCount());
        within(1000, () -> manager.abortAll());

        // Checked before the delayed thread goes on.
        assertTrue(second.isClosed());
        assertTrue(group.isAborted());
        assertFalse(manager.isAliveLocally());
        gate[1].countDown();
        assertEnded(delayed);
        assertEquals(0, manager.socketGroupCount());
    }

    @Test
    public void lateUpdateForAnAddDoesNotKeepAGroupThatIsEmptyAgain() throws Exception {
        final SocketGroup group = manager.newSocketGroup();
        final WatchedSocketFactory factory = new WatchedSocketFactory(group, 5000, 0);
        final CountDownLatch[] gate = holdTrackOf("delayed");
        // The socket is in the group, and the update of the map for that add waits.
        final Thread delayed = named("delayed", () -> {
            try {
                factory.createSocket();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(gate[0].await(5, TimeUnit.SECONDS));
        assertEquals(1, group.getSockets().size());
        assertEquals(0, manager.socketGroupCount());

        group.getSockets().get(0).close();
        assertTrue(group.isEmpty());
        assertEquals(0, manager.socketGroupCount());
        gate[1].countDown();
        assertEnded(delayed);

        assertEquals(0, manager.socketGroupCount());
        assertFalse(group.isAborted());
    }

    @Test
    public void abortAllClosesTheSocketsOfAGroupThatAnotherThreadIsAborting() throws Exception {
        final SocketGroup group = manager.newSocketGroup();
        final CountDownLatch inFirstClose = new CountDownLatch(1);
        final CountDownLatch release = released();
        final List<Socket> sockets = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            // A close by the thread that aborts this one group waits, as in the middle of that abort.
            final Socket socket = new Socket() {
                @Override
                public void close() throws IOException {
                    if ("single".equals(Thread.currentThread().getName())) {
                        inFirstClose.countDown();
                        await(release);
                    }
                    super.close();
                }
            };
            sockets.add(socket);
            group.add(socket);
        }
        final Thread single = named("single", () -> group.abort("single abort"));
        assertTrue(inFirstClose.await(5, TimeUnit.SECONDS));
        // The group says aborted, and its sockets are still open.
        assertTrue(group.isAborted());
        for (final Socket socket : sockets) {
            assertFalse(socket.isClosed());
        }
        assertEquals(1, manager.socketGroupCount());

        within(1000, () -> manager.abortAll());

        // Checked before the other thread goes on.
        for (final Socket socket : sockets) {
            assertTrue(socket.isClosed());
        }
        assertEquals("single abort", group.reason());
        assertEquals(0, manager.socketGroupCount());
        release.countDown();
        assertEnded(single);
        assertTrue(group.isEmpty());
    }

    @Test
    public void emptyManagerIsAliveAndAnAbortedOneIsNotWithNoProviderAsked() throws Exception {
        assertTrue(manager.isAliveLocally());
        assertEquals(0, manager.getHandedOutCount());
        assertEquals(0, manager.getOpenClientCount());
        final DefaultFileSystemManager bare = new DefaultFileSystemManager();
        assertTrue(bare.isAliveLocally());
        assertEquals(0, bare.getOpenClientCount());

        provider.open("remote://host/").with(2, 3);
        assertTrue(manager.isAliveLocally());
        manager.abortAll();
        final int asked = provider.asked.get();

        assertFalse(manager.isAliveLocally());
        assertEquals(asked, provider.asked.get());
        // The abort closed sockets. It did not take a client back.
        assertEquals(2, manager.getHandedOutCount());
        assertEquals(3, manager.getOpenClientCount());
        bare.abortAll();
        assertFalse(bare.isAliveLocally());
    }

    @Test
    public void oneFileSystemThatIsNotAliveDecidesAndTheCountsAreSums() throws Exception {
        localFileSystem();
        final RemoteFileSystem one = provider.open("remote://one/").with(2, 3);
        final RemoteFileSystem two = provider.open("remote://two/").with(1, 4);
        provider.open("remote://three/");

        assertTrue(manager.isAliveLocally());
        assertEquals(3, manager.getHandedOutCount());
        assertEquals(7, manager.getOpenClientCount());

        two.alive = false;
        assertFalse(manager.isAliveLocally());
        two.alive = true;
        one.alive = false;
        assertFalse(manager.isAliveLocally());
        one.alive = true;
        assertTrue(manager.isAliveLocally());
    }

    @Test
    public void providerUnderSeveralSchemesAndAsTheDefaultCountsEachFileSystemOnce() throws Exception {
        final DefaultFileSystemManager aliased = new DefaultFileSystemManager();
        final RemoteProvider named = new RemoteProvider();
        final RemoteProvider onlyDefault = new RemoteProvider();
        try {
            aliased.addProvider(new String[] {"remote", "alias"}, named);
            aliased.setDefaultProvider(named);
            aliased.init();
            named.open("remote://host/").with(2, 3);
            assertEquals(2, aliased.getHandedOutCount());
            assertEquals(3, aliased.getOpenClientCount());

            // A provider that is only the default is counted too.
            aliased.setDefaultProvider(onlyDefault);
            final RemoteFileSystem ofDefault = onlyDefault.open("remote://defaulthost/").with(5, 6);
            assertEquals(7, aliased.getHandedOutCount());
            assertEquals(9, aliased.getOpenClientCount());
            ofDefault.alive = false;
            assertFalse(aliased.isAliveLocally());
            ofDefault.alive = true;

            // The provider stays while one of its schemes is left.
            aliased.removeProvider("alias");
            assertEquals(7, aliased.getHandedOutCount());
            aliased.removeProvider("remote");
            assertEquals(5, aliased.getHandedOutCount());
            assertEquals(6, aliased.getOpenClientCount());
        } finally {
            aliased.close();
        }
        assertEquals(0, aliased.getHandedOutCount());
        assertTrue(aliased.isAliveLocally());
    }

    @Test
    public void removalReplacementAndCloseAreSeenByTheNextQuery() throws Exception {
        final RemoteFileSystem first = provider.open("remote://host/").with(1, 1);
        first.alive = false;
        assertFalse(manager.isAliveLocally());
        assertEquals(1, manager.getHandedOutCount());

        provider.replace("remote://host/").with(5, 6);
        assertTrue(manager.isAliveLocally());
        assertEquals(5, manager.getHandedOutCount());
        assertEquals(6, manager.getOpenClientCount());

        manager.closeFileSystem(provider.findFileSystem(first.getRootName(), null));
        assertEquals(0, manager.getHandedOutCount());

        provider.open("remote://host/").with(4, 4);
        assertEquals(4, manager.getOpenClientCount());
        manager.close();
        assertEquals(0, manager.getOpenClientCount());
        assertTrue(manager.isAliveLocally());
        assertTrue(provider.getCachedFileSystems().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> provider.getCachedFileSystems().add(first));
    }

    @Test
    public void queriesReturnWhileEveryMonitorIsHeld() throws Exception {
        final AbstractFileSystem local = localFileSystem();
        final RemoteFileSystem remote = provider.open("remote://host/").with(2, 3);
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = released();
        callers.submit(() -> holdAll(Arrays.asList(manager, provider, localProvider, manager.getFilesCache(), local,
                remote), held, release));
        assertTrue(held.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(Boolean.TRUE, callers.submit(manager::isAliveLocally).get(1, TimeUnit.SECONDS));
            assertEquals(2, callers.submit(manager::getHandedOutCount).get(1, TimeUnit.SECONDS).intValue());
            assertEquals(3, callers.submit(manager::getOpenClientCount).get(1, TimeUnit.SECONDS).intValue());
            within(1000, () -> manager.abortAll());
            assertEquals(Boolean.FALSE, callers.submit(manager::isAliveLocally).get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void queriesAreSafeWhileAnotherThreadChangesTheCacheAndCloses() throws Exception {
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicInteger rounds = new AtomicInteger();
        final Future<Object> reader = callers.submit(() -> {
            while (!stop.get()) {
                manager.isAliveLocally();
                assertTrue(manager.getHandedOutCount() >= 0);
                assertTrue(manager.getOpenClientCount() >= 0);
                rounds.incrementAndGet();
            }
            return null;
        });
        try {
            for (int round = 0; round < 300; round++) {
                final RemoteFileSystem opened = provider.open("remote://host" + round % 7 + "/").with(1, 2);
                provider.replace("remote://host" + round % 7 + "/").with(3, 4);
                manager.addProvider("alias" + round, provider);
                if (round % 3 == 0) {
                    manager.closeFileSystem(provider.findFileSystem(opened.getRootName(), null));
                }
                manager.removeProvider("alias" + round);
            }
            manager.close();
        } finally {
            stop.set(true);
        }
        reader.get(5, TimeUnit.SECONDS);
        assertTrue(rounds.get() > 0);
        assertEquals(0, manager.getOpenClientCount());
    }

    private static void closeQuietly(final Socket socket) {
        try {
            socket.close();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void abortAndCloseFileSystemAbortsFirstThenClosesAndDropsTheFileSystem() throws Exception {
        final RemoteFileSystem remote = provider.open("remote://host/");
        final ServerSocket server = peers.silentServer();
        final Socket socket = new WatchedSocketFactory(provider.group.get(), 5000, 0)
                .createSocket(LOOPBACK, server.getLocalPort());
        assertNotNull(provider.findFileSystem(remote.getRootName(), null));

        manager.abortAndCloseFileSystem(remote);

        assertEquals(Arrays.asList("abort", "close"), provider.calls);
        assertTrue(socket.isClosed());
        assertNull(provider.findFileSystem(remote.getRootName(), null));
    }

    @Test
    public void baseFileSystemHasNoSocketAndAnswersForALocalPath() throws Exception {
        final AbstractFileSystem local = localFileSystem();
        local.abort();
        assertTrue(local.isAliveLocally());
        assertEquals(0, local.getHandedOutCount());
        assertEquals(0, local.getOpenClientCount());
        assertTrue(manager.resolveFile(folder.toUri().toString()).exists());

        manager.abortAndCloseFileSystem(local);
        assertFalse(manager.isFileSystemCached(local, local.getFileSystemOptions()));
    }

    @Test
    public void abortAllHoldsNoMonitorOfTheForkWhileItClosesSockets() throws Exception {
        localFileSystem();
        provider.open("remote://host/");
        final List<String> monitors = new CopyOnWriteArrayList<>();
        final List<String> samples = new CopyOnWriteArrayList<>();
        final SocketGroup group = provider.group.get();
        final SocketGroup child = manager.newSocketGroup();
        group.addChild(child);
        for (final SocketGroup target : Arrays.asList(group, group, child)) {
            target.add(new Socket() {
                @Override
                public void close() throws IOException {
                    final ThreadInfo[] info = ManagementFactory.getThreadMXBean()
                            .getThreadInfo(new long[] {Thread.currentThread().getId()}, true, false);
                    samples.add(Thread.currentThread().getName());
                    for (final MonitorInfo monitor : info[0].getLockedMonitors()) {
                        final String owner = String.valueOf(monitor.getLockedStackFrame());
                        if (monitor.getClassName().startsWith(FORK_PACKAGE) || owner.contains(FORK_PACKAGE)) {
                            monitors.add(monitor.getClassName() + " at " + owner);
                        }
                    }
                    super.close();
                }
            });
        }
        assumeTrue(ManagementFactory.getThreadMXBean().isObjectMonitorUsageSupported());

        manager.abortAll();

        // One sample for each close of the three sockets. A walk that comes to the child a second time finds its
        // socket gone and closes nothing, so the count does not tell how often the child was visited. This test
        // reads the monitors that are held at a close. It can not see a monitor that was taken and left before.
        assertEquals(3, samples.size());
        assertEquals(Collections.emptyList(), monitors);
    }

    @Test
    public void fileSystemOfADefaultProviderLeavesItsOwnCacheWhateverItsSchemeIs() throws Exception {
        final RemoteProvider onlyDefault = new RemoteProvider();
        manager.setDefaultProvider(onlyDefault);
        // The scheme has a provider of its own, and that one did not make this file system.
        final RemoteFileSystem first = onlyDefault.open("remote://host/");
        assertSame(onlyDefault, first.getOwner());
        final RemoteFileSystem untouched = provider.open("remote://host/");

        manager.abortAndCloseFileSystem(first);

        assertEquals(Arrays.asList("abort", "close"), first.own);
        assertNull(onlyDefault.findFileSystem(first.getRootName(), null));
        assertTrue(onlyDefault.getCachedFileSystems().isEmpty());
        final RemoteFileSystem next = onlyDefault.open("remote://host/");
        assertNotSame(first, next);
        // The file system of the provider that has the scheme has the same key in its own cache. It stays.
        assertTrue(untouched.own.isEmpty());
        assertSame(untouched, provider.findFileSystem(untouched.getRootName(), null));

        // The scheme has no provider any more. The owner is still found.
        manager.removeProvider("remote");
        manager.abortAndCloseFileSystem(next);
        assertEquals(Arrays.asList("abort", "close"), next.own);
        assertNull(onlyDefault.findFileSystem(next.getRootName(), null));
    }

    @Test
    public void secondCloseOfAFileSystemDoesNotTakeItsReplacementOutOfTheCache() throws Exception {
        final String uri = "remote://host/folder/file.txt";
        final RemoteFileSystem first = (RemoteFileSystem) manager.resolveFile(uri).getFileSystem();
        manager.abortAndCloseFileSystem(first);
        final RemoteFileSystem next = (RemoteFileSystem) manager.resolveFile(uri).getFileSystem().getRoot()
                .getFileSystem();
        assertNotSame(first, next);
        next.with(2, 3);

        manager.abortAndCloseFileSystem(first);
        manager.closeFileSystem(first);
        manager._closeFileSystem(first);

        // The replacement is still the one that a resolve gives, and the queries of the manager still count it.
        assertSame(next, provider.findFileSystem(next.getRootName(), null));
        assertSame(next, manager.resolveFile(uri).getFileSystem());
        assertTrue(provider.getCachedFileSystems().contains(next));
        assertEquals(2, manager.getHandedOutCount());
        assertTrue(next.own.isEmpty());
        // Each close of the old one cleaned only the old one.
        assertEquals(Arrays.asList("abort", "close", "abort", "close", "close", "close"), first.own);
    }

    @Test
    public void secondCloseWithTheStockUrlProviderDoesNotTakeTheReplacementOutOfTheCache() throws Exception {
        final UrlFileProvider urls = new UrlFileProvider();
        manager.addProvider("http", urls);
        final String uri = "http://127.0.0.1/test";
        final FileSystem first = manager.resolveFile(uri).getFileSystem();
        assertTrue(urls.getCachedFileSystems().contains(first));

        manager.abortAndCloseFileSystem(first);
        assertFalse(urls.getCachedFileSystems().contains(first));
        final FileSystem next = manager.resolveFile(uri).getFileSystem();
        assertNotSame(first, next);
        manager.abortAndCloseFileSystem(first);

        assertTrue(urls.getCachedFileSystems().contains(next));
        assertSame(next, manager.resolveFile(uri).getFileSystem());
    }

    @Test
    public void fileSystemThatAnotherOneDisplacedIsCleanedAndTheOtherOneStays() throws Exception {
        final RemoteFileSystem first = provider.open("remote://host/");
        final RemoteFileSystem next = provider.replace("remote://host/");
        assertSame(next, provider.findFileSystem(first.getRootName(), null));

        manager.abortAndCloseFileSystem(first);

        assertEquals(Arrays.asList("abort", "close"), first.own);
        assertTrue(next.own.isEmpty());
        assertSame(next, provider.findFileSystem(first.getRootName(), null));
        assertEquals(Collections.singletonList(next), provider.getCachedFileSystems());

        manager.abortAndCloseFileSystem(next);
        assertTrue(provider.getCachedFileSystems().isEmpty());
    }

    @Test
    public void closeTellsTheFilesCacheAndRunsOutsideTheLockOfTheProviderCache() throws Exception {
        final FileObject file = manager.resolveFile("remote://host/folder/file.txt");
        final RemoteFileSystem remote = (RemoteFileSystem) file.getFileSystem();
        assertNotNull(manager.getFilesCache().getFile(remote, file.getName()));
        final Gate gate = gate("closer");
        remote.closeGate = gate;

        final Thread closer = named("closer", () -> manager.abortAndCloseFileSystem(remote));
        assertTrue(gate.reached.await(5, TimeUnit.SECONDS));
        // The close of the file system waits. The cache of the provider is free, and the file system has left it.
        assertNull(callers.submit(() -> provider.findFileSystem(remote.getRootName(), null))
                .get(1, TimeUnit.SECONDS));
        final RemoteFileSystem next = callers.submit(() -> provider.open("remote://host/")).get(1, TimeUnit.SECONDS);
        assertNotSame(remote, next);
        assertNull(manager.getFilesCache().getFile(remote, file.getName()));
        gate.release.countDown();
        assertEnded(closer);
    }

    @Test
    public void virtualFileSystemIsClosedByItsOwnProviderAndASecondCloseIsSafe() throws Exception {
        Files.write(folder.resolve("a.txt"), new byte[] {1});
        final FileObject root = manager.createVirtualFileSystem(manager.resolveFile(folder.toUri().toString()));
        final AbstractFileSystem virtual = (AbstractFileSystem) root.getFileSystem();
        assertInstanceOf(VirtualFileSystem.class, virtual);
        assertNull(virtual.getOwner());
        assertTrue(virtual.resolveFile("/a.txt").exists());

        manager.abortAndCloseFileSystem(virtual);
        manager.abortAndCloseFileSystem(virtual);

        // The close took the junction away, so the name leads to no file any more.
        assertFalse(virtual.resolveFile("/a.txt").exists());
        // The local file system below it was not closed with it.
        assertTrue(manager.resolveFile(folder.toUri().toString()).exists());
    }

    private AbstractFileSystem localFileSystem() throws FileSystemException {
        return (AbstractFileSystem) manager.resolveFile(folder.toUri().toString()).getFileSystem();
    }

    // Takes the monitor of every object, one inside the other, and keeps them until the test lets go.
    private static Object holdAll(final List<Object> objects, final CountDownLatch held, final CountDownLatch release)
            throws InterruptedException {
        if (objects.isEmpty()) {
            held.countDown();
            await(release);
            return null;
        }
        synchronized (objects.get(0)) {
            return holdAll(objects.subList(1, objects.size()), held, release);
        }
    }

    private void within(final long millis, final Runnable call) throws Exception {
        callers.submit(call).get(millis, TimeUnit.MILLISECONDS);
    }

    private static void assertFails(final Future<Object> call) throws Exception {
        try {
            call.get(1000, TimeUnit.MILLISECONDS);
        } catch (final ExecutionException e) {
            assertInstanceOf(IOException.class, e.getCause());
            assertEquals(Fault.TRANSPORT, Faults.of(e.getCause()));
            return;
        }
        throw new AssertionError("The blocked call returned with no error");
    }
}
