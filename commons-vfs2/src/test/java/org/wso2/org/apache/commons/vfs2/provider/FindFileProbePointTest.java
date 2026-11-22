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
package org.wso2.org.apache.commons.vfs2.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.FindFileProbeTest.ProbeFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.FindFileProbeTest.ProbeProvider;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * The point {@code findfile.probe} of the test build. It holds the probe after the abort and before the rebuild, and
 * only when a rebuild follows.
 */
@Tag("testbuild")
public class FindFileProbePointTest {

    private static final String DIR = "vfs.testpoints.dir";

    // The root URI of FindFileProbeTest.URI with no user part.
    private static final String KEY = "probe://127.0.0.1:2121/";

    @TempDir
    Path base;

    private Path copy;

    private DefaultFileSystemManager manager;

    private ProbeProvider provider;

    @BeforeEach
    public void start() throws Exception {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        TestPoints.event("probe-test", null);
        try (Stream<Path> folders = Files.list(base)) {
            copy = folders.filter(folder -> Files.exists(folder.resolve("copy"))).findFirst().get();
        }
        provider = new ProbeProvider();
        manager = new DefaultFileSystemManager();
        manager.addProvider("probe", provider);
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
        System.clearProperty(DIR);
    }

    @Test
    public void probeHoldsAfterTheAbortAndBeforeTheRebuild() throws Exception {
        manager.resolveFile(FindFileProbeTest.URI);
        final ProbeFileSystem cached = provider.last();
        cached.failNext(new FileSystemException("read failed", new SocketException("Connection reset")), 0);
        final Path hold = copy.resolve("findfile.probe@k" + hash(KEY) + ".hold");
        Files.write(hold, "wait=30\n".getBytes(StandardCharsets.UTF_8));

        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = new Thread(() -> {
            try {
                result.set(manager.resolveFile(FindFileProbeTest.URI));
            } catch (final FileSystemException e) {
                result.set(e);
            }
        }, "probe-caller");
        caller.start();
        final Path reached = copy.resolve("findfile.probe@k" + hash(KEY) + ".1.reached");
        waitFor(reached);

        // At the barrier the old file system is aborted and closed, and nothing is built yet.
        assertTrue(caller.isAlive());
        assertEquals(1, cached.aborts.get());
        assertTrue(cached.closed);
        assertEquals(1, provider.made.size());
        final Map<String, String> record = fields(new String(Files.readAllBytes(reached), StandardCharsets.UTF_8));
        assertEquals(KEY, record.get("key"));
        assertEquals("probe-caller", record.get("thread"));

        Files.delete(hold);
        caller.join(5000);
        assertFalse(caller.isAlive());
        assertTrue(result.get() instanceof FileObject, String.valueOf(result.get()));
        assertNotSame(cached, ((FileObject) result.get()).getFileSystem());
        assertEquals(2, provider.made.size());
    }

    @Test
    public void windowThatEndsWhileTheCallerWaitsForTheProviderAdmitsNoRebuild() throws Exception {
        for (final boolean expire : new boolean[] {true, false}) {
            final DefaultFileSystemManager own = new DefaultFileSystemManager();
            final ProbeProvider probes = new ProbeProvider();
            own.addProvider("probe", probes);
            own.init();
            try {
                own.resolveFile(FindFileProbeTest.URI);
                final ProbeFileSystem cached = probes.last();
                final FileSystemException reset = new FileSystemException("read failed",
                        new SocketException("Connection reset"));
                cached.failNext(reset, 0);
                final Path hold = copy.resolve("findfile.probe@k" + hash(KEY) + ".hold");
                Files.write(hold, "wait=30\n".getBytes(StandardCharsets.UTF_8));
                final AtomicReference<Object> result = new AtomicReference<>();
                final Thread caller = new Thread(() -> {
                    try {
                        result.set(own.resolveFile(FindFileProbeTest.URI));
                    } catch (final FileSystemException e) {
                        result.set(e);
                    }
                }, "window-caller");
                caller.start();
                waitForAny(copy, "findfile.probe@k" + hash(KEY) + ".");
                // The test holds the real monitor of the provider, then lets the point go. The caller waits at the
                // monitor. When the window ends there, the rebuild is not admitted.
                synchronized (probes) {
                    Files.delete(hold);
                    final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (caller.getState() != Thread.State.BLOCKED && System.nanoTime() < until) {
                        Thread.sleep(5);
                    }
                    assertEquals(Thread.State.BLOCKED, caller.getState());
                    if (expire) {
                        final long left = TimeUnit.NANOSECONDS.toMillis(cached.lastAnswerAt
                                + TimeUnit.SECONDS.toNanos(5) - System.nanoTime());
                        Thread.sleep(Math.max(0, left) + 200);
                    }
                }
                caller.join(5000);
                assertFalse(caller.isAlive());
                if (expire) {
                    assertTrue(result.get() instanceof FileSystemException, String.valueOf(result.get()));
                    assertSame(reset, ((FileSystemException) result.get()).getCause());
                    assertEquals(1, probes.made.size());
                } else {
                    assertTrue(result.get() instanceof FileObject, String.valueOf(result.get()));
                    assertEquals(2, probes.made.size());
                }
                assertEquals(1, cached.aborts.get());
            } finally {
                own.close();
                try (Stream<Path> files = Files.list(copy)) {
                    for (final Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString()
                            .startsWith("findfile.probe"))::iterator) {
                        Files.delete(f);
                    }
                }
            }
        }
    }

    private static void waitForAny(final Path dir, final String prefix) throws Exception {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (Stream<Path> files = Files.list(dir)) {
                if (files.anyMatch(f -> f.getFileName().toString().startsWith(prefix)
                        && f.getFileName().toString().endsWith(".reached"))) {
                    return;
                }
            }
            assertTrue(System.nanoTime() < end, "the point was not reached");
            Thread.sleep(5);
        }
    }

    @Test
    public void slowFailureDoesNotReachThePoint() throws Exception {
        manager.resolveFile(FindFileProbeTest.URI);
        final ProbeFileSystem cached = provider.last();
        cached.failNext(new FileSystemException("read failed", new SocketTimeoutException("Read timed out")), 5100);
        Files.write(copy.resolve("findfile.probe.hold"), "wait=30\n".getBytes(StandardCharsets.UTF_8));
        final long start = System.nanoTime();
        assertThrows(FileSystemException.class, () -> manager.resolveFile(FindFileProbeTest.URI));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000);
        try (Stream<Path> files = Files.list(copy)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("findfile.probe@")
                    || file.getFileName().toString().startsWith("findfile.probe.1.")));
        }
        assertEquals(1, provider.made.size());
    }

    private static void waitFor(final Path file) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(file)) {
            assertTrue(System.nanoTime() < end, "no " + file);
            Thread.sleep(10);
        }
    }

    private static String hash(final String key) throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 16);
    }

    private static Map<String, String> fields(final String line) {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final String field : line.trim().split("\t")) {
            final int equals = field.indexOf('=');
            fields.put(field.substring(0, equals), field.substring(equals + 1));
        }
        return fields;
    }
}
