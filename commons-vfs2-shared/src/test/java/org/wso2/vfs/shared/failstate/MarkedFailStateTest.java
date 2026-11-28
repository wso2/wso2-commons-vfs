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
package org.wso2.vfs.shared.failstate;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.health.EndpointHealth;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockOutcome;

@Tag("testbuild")
class MarkedFailStateTest {
    @Test
    void bytePrefixLostSwapAndStepSevenTransportEnd(@TempDir Path root) throws Exception {
        Path log = root.resolve("store.log");
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                MarkedFailStateTest.class.getName(), root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(100, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally {
            child.destroyForcibly();
        }
    }

    private static Path reached(Path copy, String point) throws Exception {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8)) {
            try (Stream<Path> files = Files.list(copy)) {
                Path hit = files.filter(p -> p.getFileName().toString().startsWith(point + "@")
                        && p.getFileName().toString().endsWith(".reached")).findFirst().orElse(null);
                if (hit != null && Files.size(hit) != 0) {
                    return hit;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Missing reached record for " + point);
    }

    private static void release(Path copy, String point, Path hit) throws Exception {
        Files.delete(copy.resolve(point + ".hold"));
        Files.createFile(hit.resolveSibling(hit.getFileName().toString().replace(".reached", ".release")));
    }

    private static String snapshot(Path copy, int sequence) throws Exception {
        Files.createFile(copy.resolve("failstate.registry.ask"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Stream<Path> files = Files.list(copy)) {
                Path response = files.filter(path -> path.getFileName().toString().startsWith("failstate.registry@")
                        && path.toString().endsWith("." + sequence + ".snap")).findFirst().orElse(null);
                if (response != null && !Files.exists(copy.resolve("failstate.registry.ask"))) {
                    return Files.readString(response);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Missing registry snapshot " + sequence);
    }

    private static void checkStep(Path root, Path copy, boolean update, String number, String side) throws Exception {
        FailStateStoreTest bed = new FailStateStoreTest();
        bed.dir = Files.createTempDirectory(root, "step");
        bed.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        String point = "failstate.step";
        try {
            bed.params.retryMillis = 0;
            if (update) {
                bed.startFailure();
            }
            HeldLock held = bed.acquire(null);
            FailStateStore.BeginResult entry = bed.store.beginFile(bed.io, held, bed.uri);
            FailStateStoreTest.SwapIo io = bed.new SwapIo();
            boolean fallback = number.startsWith("5");
            io.fallback = update && fallback;
            Callable<FailStateStore.Write> write = () -> update
                    ? bed.store.retryFailed(io, held, entry.state, FailState.Action.DELETE, null, bed.refused)
                    : bed.store.recordFailure(io, held, entry.identity, FailState.Outcome.SUCCESS,
                            FailState.Action.DELETE, null, bed.refused);
            boolean pending = number.equals("3d") || number.equals("7d");
            if (pending) {
                io.cut = number.equals("3d") ? 5 : -1;
                io.lost = number.equals("7d");
                assertEquals(FailStateStore.Write.PENDING, write.call());
                io.cut = -1;
                if (number.equals("7d")) {
                    assertEquals(update ? 1 : 0, bed.record().attempts);
                    assertTrue(bed.stages().isEmpty());
                }
            }
            String label = number + "." + side;
            String kind = update ? "update" : "first";
            Path control = copy.resolve(point + ".hold");
            Files.write(control, Arrays.asList("step=" + label, "kind=" + kind, "try=" + (pending ? 2 : 1),
                    "wait=8"), StandardCharsets.UTF_8);
            boolean applicable = update || !fallback;
            Future<?> action = worker.submit(() -> pending ? bed.store.runPending(io) : write.call());
            if (applicable) {
                Path hit = reached(copy, point);
                String fields = Files.readString(hit);
                assertTrue(fields.contains("step=" + label) && fields.contains("kind=" + kind)
                        && fields.contains("try=" + (pending ? 2 : 1)), fields);
                boolean after = side.equals("after");
                boolean newCanonical = number.equals("7d") || after && (number.equals("4") || number.equals("5r"));
                boolean gap = label.equals("5d.after") || label.equals("5r.before");
                assertEquals(newCanonical || update && !gap, Files.exists(bed.marker), label);
                if (Files.exists(bed.marker)) {
                    assertEquals(newCanonical && update ? 1 : 0, bed.record().attempts, label);
                }
                boolean cut = label.equals("3d.before");
                boolean whole = label.equals("2.after") || number.equals("3") || label.equals("4.before")
                        || number.equals("5d") || label.equals("5r.before");
                List<Path> stages = bed.stages();
                assertEquals(cut || whole ? 1 : 0, stages.size(), label);
                if (cut || whole) {
                    byte[] bytes = Files.readAllBytes(stages.get(0));
                    FailState staged = FailState.parse(bytes, bed.uri, "b.csv");
                    if (cut) {
                        assertEquals(5, bytes.length);
                        assertNull(staged);
                    } else {
                        assertNotNull(staged, label);
                        assertEquals(update ? 1 : 0, staged.attempts);
                    }
                }
                assertEquals(LockOutcome.Kind.OURS, held.verifyOwner(bed.io).kind, label);
                release(copy, point, hit);
                action.get(6, TimeUnit.SECONDS);
                Files.delete(hit);
            } else {
                assertEquals(FailStateStore.Write.WRITTEN, action.get(3, TimeUnit.SECONDS));
                try (Stream<Path> files = Files.list(copy)) {
                    assertFalse(files.anyMatch(path -> path.toString().endsWith(".reached")
                            && path.getFileName().toString().startsWith(point + "@")));
                }
                Files.delete(control);
            }
            bed.released(held);
            assertEquals(update ? 1 : 0, bed.record().attempts);
        } finally {
            worker.shutdownNow();
            bed.close();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Path hooks = Files.createDirectory(root.resolve("hooks"));
        System.setProperty("vfs.testpoints.dir", hooks.toString());
        TestPoints.copy("inbound", FailStateStore.class);
        FailStateStoreTest bed = new FailStateStoreTest();
        bed.dir = Files.createDirectory(root.resolve("files"));
        bed.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Stream<Path> copies = Files.list(hooks)) {
            Path copy = copies.filter(p -> Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            bed.params.retryMillis = 0;
            HeldLock held = bed.acquire(null);
            FileIdentity identity = bed.store.beginFile(bed.io, held, bed.uri).identity;
            String id = "handle=" + Integer.toUnsignedString(System.identityHashCode(held));
            String initial = snapshot(copy, 1);
            assertTrue(initial.contains(id) && initial.contains("owner=THREAD") && initial.contains("state=held"), initial);
            String point = "failstate.staging.bytes";
            Files.write(copy.resolve(point + ".hold"), Arrays.asList("bytes=11", "wait=12"), StandardCharsets.UTF_8);
            Files.write(copy.resolve(point + ".fault"), Arrays.asList("connection-lost", "once"), StandardCharsets.UTF_8);
            Future<FailStateStore.Write> first = worker.submit(() -> bed.store.recordFailure(bed.io, held, identity,
                    FailState.Outcome.SUCCESS, FailState.Action.DELETE, null, bed.refused));
            Path hit = reached(copy, point);
            assertTrue(new String(Files.readAllBytes(hit), StandardCharsets.UTF_8).contains("bytes=11"));
            Path cut = bed.stages().get(0);
            assertEquals(11, Files.size(cut));
            assertFalse(Files.exists(bed.marker));
            release(copy, point, hit);
            assertEquals(FailStateStore.Write.PENDING, first.get(5, TimeUnit.SECONDS));

            String transferred = snapshot(copy, 2);
            assertTrue(transferred.contains(id) && transferred.contains("owner=PENDING_STATE_WRITE")
                    && transferred.contains(LockIo.pointKey(bed.uri)), transferred);
            point = "failstate.step";
            Files.write(copy.resolve(point + ".hold"), Arrays.asList("step=4.after", "wait=12"), StandardCharsets.UTF_8);
            Files.write(copy.resolve(point + ".fault"), Arrays.asList("connection-lost", "step=4.after", "once"),
                    StandardCharsets.UTF_8);
            Future<FailStateStore.PendingResult> next = worker.submit(() -> bed.store.runPending(bed.io));
            hit = reached(copy, point);
            String fields = new String(Files.readAllBytes(hit), StandardCharsets.UTF_8);
            assertTrue(fields.contains("step=4.after") && fields.contains("try=2") && fields.contains("kind=first"));
            assertFalse(Files.exists(cut));
            assertTrue(bed.stages().isEmpty());
            assertNotNull(bed.record());
            assertEquals(LockOutcome.Kind.OURS, held.verifyOwner(bed.io).kind);
            release(copy, point, hit);
            assertEquals(1, next.get(5, TimeUnit.SECONDS).pending);
            Files.write(copy.resolve(point + ".fault"), Arrays.asList("connection-lost", "step=7d.before", "once"),
                    StandardCharsets.UTF_8);
            FailStateStore.PendingResult done = bed.store.runPending(bed.io);
            assertEquals(1, done.written);
            assertEquals(0, done.pending);
            assertTrue(done.failure.getKind().isTransport());
            assertTrue(Files.exists(bed.dir.resolve("b.csv.lock")));
            assertEquals(LockOutcome.Kind.ENDED, held.verifyOwner(bed.io).kind);
            String events = new String(Files.readAllBytes(copy.resolve("events.log")), StandardCharsets.UTF_8);
            assertTrue(events.contains("way=abandon") && events.contains("failstate.try") && events.contains(id));
            String empty = snapshot(copy, 3);
            assertFalse(empty.contains("handle="), empty);
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, bed.endpoint);
            EndpointHealth replacement = HealthRegistry.register(HealthRegistry.Kind.INBOUND, bed.endpoint, 10000);
            ShareLock redeployed = ShareLock.create(bed.endpoint, true, 60000, replacement);
            ShareLock.create(bed.endpoint, true, 60000, bed.health);
            EndpointHealth connection = HealthRegistry.register(HealthRegistry.Kind.CONNECTION, bed.endpoint, 0);
            ShareLock connectionLock = ShareLock.create(bed.endpoint, true, 60000, connection);
            String newPath = bed.dir.resolve("new.csv").toUri().toString();
            String connPath = bed.dir.resolve("connection.csv").toUri().toString();
            Files.createFile(bed.dir.resolve("new.csv"));
            Files.createFile(bed.dir.resolve("connection.csv"));
            HeldLock current = redeployed.acquire(bed.io, newPath, ShareLock.Mode.LISTENER, null).handle;
            HeldLock other = connectionLock.acquire(bed.io, connPath, ShareLock.Mode.LISTENER, null).handle;
            assertNotNull(current);
            assertNotNull(other);
            String isolated = snapshot(copy, 4);
            assertTrue(isolated.contains("new.csv") && !isolated.contains("connection.csv"), isolated);
            HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION, bed.endpoint);
            assertTrue(snapshot(copy, 5).contains("new.csv"));
            current.releaseChecked(bed.io);
            other.releaseChecked(bed.io);
            TestPoints.Handle observer = TestPoints.snapshot("test.registry-observer", "control", () -> "ready");
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, bed.endpoint);
            Path ask = Files.createFile(copy.resolve("failstate.registry.ask"));
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (Files.exists(ask) && System.nanoTime() < limit) {
                Thread.sleep(10);
            }
            assertFalse(Files.exists(ask));
            try (Stream<Path> paths = Files.list(copy)) {
                assertFalse(paths.anyMatch(path -> path.toString().contains("failstate.registry@")
                        && path.toString().endsWith(".6.snap")));
            }
            observer.remove();
            Files.delete(hit);
            for (boolean update : Arrays.asList(false, true)) {
                for (String number : Arrays.asList("1", "2", "3", "3d", "4", "5d", "5r", "7d")) {
                    for (String side : Arrays.asList("before", "after")) {
                        checkStep(root, copy, update, number, side);
                    }
                }
            }
        } finally {
            worker.shutdownNow();
            bed.close();
        }
    }
}
