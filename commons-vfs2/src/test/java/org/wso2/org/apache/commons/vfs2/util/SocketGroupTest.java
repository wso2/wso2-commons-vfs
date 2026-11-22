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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link SocketGroup}.
 */
public class SocketGroupTest {

    @Test
    public void abortClosesEverySocketAndEveryChildAndKeepsTheFirstReason() {
        final SocketGroup group = new SocketGroup();
        final SocketGroup child = new SocketGroup();
        final Socket own = new Socket();
        final Socket ofChild = new Socket();
        group.add(own);
        child.add(ofChild);
        group.addChild(child);
        assertFalse(group.isAborted());
        assertNull(group.reason());

        group.abort("first");
        group.abort("second");

        assertTrue(group.isAborted());
        assertEquals("first", group.reason());
        assertTrue(own.isClosed());
        assertTrue(child.isAborted());
        assertEquals("first", child.reason());
        assertTrue(ofChild.isClosed());
        // An aborted group holds nothing, so its owner can drop it.
        assertTrue(group.isEmpty());
        assertTrue(child.isEmpty());
    }

    @Test
    public void socketOrChildAddedToAnAbortedGroupIsEndedAtOnce() {
        final SocketGroup group = new SocketGroup();
        group.abort(null);
        assertTrue(group.isAborted());
        assertTrue(group.reason() != null && !group.reason().isEmpty());

        final Socket late = new Socket();
        group.add(late);
        assertTrue(late.isClosed());
        assertTrue(group.isEmpty());

        final SocketGroup lateChild = new SocketGroup();
        final Socket ofLateChild = new Socket();
        lateChild.add(ofLateChild);
        group.addChild(lateChild);
        assertTrue(lateChild.isAborted());
        assertTrue(ofLateChild.isClosed());
    }

    @Test
    public void removeIsSafeTwiceAndAnAbortOfAnEmptyGroupDoesNothing() {
        final SocketGroup group = new SocketGroup();
        final SocketGroup child = new SocketGroup();
        final Socket socket = new Socket();
        group.add(socket);
        group.addChild(child);
        assertFalse(group.isEmpty());
        assertEquals(1, group.getSockets().size());

        group.remove(socket);
        group.remove(socket);
        group.removeChild(child);
        group.removeChild(child);
        assertTrue(group.isEmpty());

        group.abort("empty");
        assertFalse(socket.isClosed());
        assertFalse(child.isAborted());
    }

    @Test
    public void ownerHearsEveryChange() {
        final AtomicInteger changes = new AtomicInteger();
        final SocketGroup group = new SocketGroup() {
            @Override
            protected void changed() {
                changes.incrementAndGet();
            }
        };
        final Socket socket = new Socket();
        group.add(socket);
        group.remove(socket);
        group.remove(socket);
        group.abort("done");
        assertEquals(3, changes.get());
    }

    @Test
    public void socketAddedAfterTheReasonIsSetAndBeforeTheWalkIsClosedByItsOwnAdd() throws Exception {
        final CountDownLatch claimed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // The abort waits after it has set the reason and before it closes a socket.
        final SocketGroup group = new SocketGroup() {
            @Override
            boolean claim(final String reason) {
                final boolean first = super.claim(reason);
                claimed.countDown();
                await(release);
                return first;
            }
        };
        final Socket socket = new Socket();
        final Thread aborter = started("aborter", () -> group.abort("stop"));
        try {
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            group.add(socket);
            // Checked while the abort still waits. Only the check after the add can have closed the socket.
            assertTrue(socket.isClosed());
            assertTrue(group.isEmpty());
        } finally {
            release.countDown();
            assertEnded(aborter);
        }
        assertEquals("stop", group.reason());
    }

    @Test
    public void socketInTheSetWhileTheAbortWalksIsClosedByTheWalk() throws Exception {
        final CountDownLatch added = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // The add waits after the socket is in the set and before it checks the abort.
        final SocketGroup group = new SocketGroup() {
            @Override
            public boolean isAborted() {
                if ("adder".equals(Thread.currentThread().getName())) {
                    added.countDown();
                    await(release);
                }
                return super.isAborted();
            }
        };
        final Socket socket = new Socket();
        final Thread adder = started("adder", () -> group.add(socket));
        try {
            assertTrue(added.await(5, TimeUnit.SECONDS));
            assertFalse(socket.isClosed());
            group.abort("stop");
            // Checked while the add still waits. Only the walk of the abort can have closed the socket.
            assertTrue(socket.isClosed());
        } finally {
            release.countDown();
            assertEnded(adder);
        }
        assertTrue(group.isEmpty());
    }

    @Test
    public void errorOfOneCloseDoesNotKeepTheOtherSocketsOpen() {
        final SocketGroup group = new SocketGroup();
        final List<Socket> sockets = new ArrayList<>();
        for (int count = 0; count < 4; count++) {
            final boolean broken = count % 2 == 0;
            final Socket socket = new Socket() {
                @Override
                public void close() throws IOException {
                    super.close();
                    if (broken) {
                        throw new IOException("close failed");
                    }
                    throw new IllegalStateException("close failed");
                }
            };
            sockets.add(socket);
            group.add(socket);
        }

        group.abort("stop");

        for (final Socket socket : sockets) {
            assertTrue(socket.isClosed());
        }
        assertTrue(group.isEmpty());
    }

    @Test
    public void registrationNextToAnAbortNeverLeavesAnOpenSocket() throws Exception {
        for (int round = 0; round < 2000; round++) {
            final SocketGroup group = new SocketGroup();
            final SocketGroup child = new SocketGroup();
            final Socket socket = new Socket();
            final Socket ofChild = new Socket();
            child.add(ofChild);
            final CountDownLatch start = new CountDownLatch(1);
            final Thread adder = started("adder", () -> {
                await(start);
                group.add(socket);
                group.addChild(child);
            });
            final Thread aborter = started("aborter", () -> {
                await(start);
                group.abort("race");
            });
            start.countDown();
            assertEnded(adder);
            assertEnded(aborter);
            assertTrue(socket.isClosed(), "round " + round);
            assertTrue(ofChild.isClosed(), "round " + round);
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
