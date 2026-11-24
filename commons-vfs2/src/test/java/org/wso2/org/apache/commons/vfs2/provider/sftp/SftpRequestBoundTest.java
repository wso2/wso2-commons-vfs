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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * A server whose SSH layer answers each keep alive while its SFTP part gives no reply. JSch then waits with no end, so
 * a request ends at the bound of the session, the session timeout times (keep alive count plus 1), and the next
 * operation works on a new session. A request that keeps getting replies is never cut.
 */
public class SftpRequestBoundTest {

    // SFTP packet types.
    private static final int OPEN = 3;
    private static final int READ = 5;
    private static final int LSTAT = 7;
    private static final int READDIR = 12;
    private static final int REMOVE = 13;
    private static final int REALPATH = 16;
    private static final int STAT = 17;
    private static final int RENAME = 18;

    // A session timeout of 500 ms with one keep alive gives a bound of 1000 ms.
    private static final long BOUND_MILLIS = 1000;

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    // One options object, so every file of a test is on one file system and its session can be compared.
    private FileSystemOptions bounded;

    private final ExecutorService caller = Executors.newSingleThreadExecutor();

    @BeforeEach
    public void start() throws Exception {
        server = new SftpTestServer();
        server.write("in/a.txt", "content of a");
        for (int i = 0; i < 40; i++) {
            server.write("many/f" + i + ".txt", "f" + i);
        }
        manager = new StandardFileSystemManager();
        manager.init();
        bounded = options(500);
    }

    @AfterEach
    public void stop() throws Exception {
        caller.shutdownNow();
        manager.close();
        server.close();
    }

    private FileSystemOptions options(final long sessionTimeoutMillis) throws FileSystemException {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setSessionTimeout(options, Duration.ofMillis(sessionTimeoutMillis));
        SftpFileSystemConfigBuilder.getInstance().setKeepAliveCountMax(options, 1);
        // With no connect timeout the key exchange gets the session timeout. The bound of a request does not use it.
        SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, Duration.ofSeconds(30));
        // The write check of a move runs commands on the server, which is not what these tests are about.
        SftpFileSystemConfigBuilder.getInstance().setAvoidPermissionCheck(options, "true");
        return options;
    }

    private FileObject file(final String path) throws FileSystemException {
        return manager.resolveFile(SftpTestServer.uri(server.getPort(), path), bounded);
    }

    // Runs the operation on another thread and gives back how long it took to fail.
    private long failsAfter(final Callable<?> operation) throws Exception {
        final long start = System.nanoTime();
        final Future<?> call = caller.submit(operation);
        try {
            call.get(BOUND_MILLIS + 10_000, TimeUnit.MILLISECONDS);
            fail("The held request did not fail");
        } catch (final ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException, expected.getCause().toString());
        }
        assertTrue(call.isDone(), "No thread is left waiting");
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static void endsAtTheBound(final long millis) {
        assertTrue(millis >= BOUND_MILLIS, "Ended before the bound: " + millis + " ms");
        assertTrue(millis < BOUND_MILLIS + 4000, "Ended long after the bound: " + millis + " ms");
    }

    // After the abort the held request is let go and a new operation on the same file system works on a new session.
    // It reads a file that no held request changes.
    private void nextOperationWorks(final FileObject held) throws Exception {
        final Object old = SftpTestServer.sessionOf((SftpFileSystem) held.getFileSystem());
        server.releaseHolds();
        final FileObject again = file("many/f0.txt");
        assertSame(held.getFileSystem(), again.getFileSystem());
        again.refresh();
        assertTrue(again.exists());
        assertNotSame(old, SftpTestServer.sessionOf((SftpFileSystem) again.getFileSystem()));
    }

    @Test
    public void statWithNoReplyEndsAtTheBound() throws Exception {
        final FileObject file = file("in/a.txt");
        server.holdRequests(-1, STAT, LSTAT);
        endsAtTheBound(failsAfter(() -> {
            file.refresh();
            return file.exists();
        }));
        nextOperationWorks(file);
    }

    @Test
    public void listingWithNoReplyEndsAtTheBound() throws Exception {
        final FileObject folder = file("many");
        folder.refresh();
        assertTrue(folder.isFolder());
        server.holdRequests(-1, READDIR);
        endsAtTheBound(failsAfter(folder::getChildren));
        nextOperationWorks(folder);
    }

    @Test
    public void renameWithNoReplyEndsAtTheBound() throws Exception {
        final FileObject file = file("in/a.txt");
        final FileObject target = file("in/b.txt");
        assertTrue(file.exists());
        server.holdRequests(-1, RENAME);
        endsAtTheBound(failsAfter(() -> {
            file.moveTo(target);
            return null;
        }));
        nextOperationWorks(file);
    }

    @Test
    public void removeWithNoReplyEndsAtTheBound() throws Exception {
        final FileObject file = file("in/a.txt");
        assertTrue(file.exists());
        server.holdRequests(-1, REMOVE);
        endsAtTheBound(failsAfter(file::delete));
        nextOperationWorks(file);
    }

    @Test
    public void readWithNoReplyEndsAtTheBound() throws Exception {
        final FileObject file = file("in/a.txt");
        assertTrue(file.exists());
        server.holdRequests(-1, READ);
        endsAtTheBound(failsAfter(() -> {
            try (InputStream in = file.getContent().getInputStream()) {
                return in.read();
            }
        }));
        nextOperationWorks(file);
    }

    @Test
    public void listingThatKeepsAnsweringPastTheBoundIsNotCut() throws Exception {
        final FileObject folder = file("many");
        folder.refresh();
        // Each reply to a read of the folder comes after 0.6 of the bound, so the listing takes longer than the bound.
        server.holdRequests(BOUND_MILLIS * 6 / 10, READDIR);
        final long start = System.nanoTime();
        assertEquals(40, folder.getChildren().length);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= BOUND_MILLIS);
    }

    @Test
    public void quickRequestsOnOneChannelPastTheBoundAreNotCut() throws Exception {
        // Every request waits 0.4 of the bound, and the operations make enough of them to pass the bound in all.
        server.holdRequests(BOUND_MILLIS * 4 / 10, STAT, LSTAT, OPEN, READ, READDIR, REALPATH);
        final long start = System.nanoTime();
        final FileObject file = file("in/a.txt");
        assertTrue(file.exists());
        try (InputStream in = file.getContent().getInputStream()) {
            assertEquals('c', in.read());
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) > BOUND_MILLIS);
        assertFalse(SftpTestServer.groupOf((SftpFileSystem) file.getFileSystem()).isAborted());
    }

    @Test
    public void sessionTimeoutOffLeavesTheRequestWithNoBound() throws Exception {
        final FileObject file = manager.resolveFile(SftpTestServer.uri(server.getPort(), "in/a.txt"), options(0));
        server.holdRequests(-1, STAT, LSTAT);
        final Future<Boolean> call = caller.submit(() -> {
            file.refresh();
            return file.exists();
        });
        Thread.sleep(BOUND_MILLIS * 3);
        assertFalse(call.isDone(), "With no session timeout the request keeps waiting, as JSch does");
        server.releaseHolds();
        assertTrue(call.get(10, TimeUnit.SECONDS));
    }
}
