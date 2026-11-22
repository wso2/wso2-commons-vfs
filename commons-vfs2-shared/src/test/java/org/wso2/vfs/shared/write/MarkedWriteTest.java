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
package org.wso2.vfs.shared.write;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep.Mode;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.ShareLock;

@Tag("testbuild")
class MarkedWriteTest {
    private static Path reached(Path folder, String point) throws Exception {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(8)) {
            try (Stream<Path> files = Files.list(folder)) {
                Path match = files.filter(p -> p.getFileName().toString().startsWith(point + "@")
                        && p.getFileName().toString().endsWith(".reached")).findFirst().orElse(null);
                if (match != null && Files.size(match) > 0) {
                    return match;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Missing " + point);
    }

    private static void release(Path reached) throws Exception {
        Files.createFile(reached.resolveSibling(reached.getFileName().toString().replace(".reached", ".release")));
    }

    @Test
    void transferAndCommitHoldOnOppositeSidesOfLockAndSweepUsesBothClockOffsets(@TempDir Path root)
            throws Exception {
        Path log = root.resolve("writer.log");
        Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                MarkedWriteTest.class.getName(), root.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Marked writer timed out");
            assertEquals(0, process.exitValue(), () -> {
                try {
                    return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                }
            });
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = java.nio.file.Paths.get(args[0]);
        String saved = System.getProperty("vfs.testpoints.dir");
        System.setProperty("vfs.testpoints.dir", root.toString());
        String owner = "file:marked";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("file", new DefaultLocalFileProvider());
            manager.init();
            TestPoints.copy("writer", AtomicWriter.class);
            Path copy;
            try (Stream<Path> entries = Files.list(root)) {
                copy = entries.filter(p -> Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            }
            for (String point : Arrays.asList("temp.in-write", "commit.after-lock-share",
                    "cleanup.after-delete", "cleanup.sweep.before-first-delete")) {
                Files.write(copy.resolve(point + ".hold"), Arrays.asList("connection=" + owner, "wait=15"),
                        StandardCharsets.UTF_8);
            }
            EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.CONNECTION, owner, 0);
            ShareLock locks = ShareLock.create(owner, true, 60000, health);
            Path folder = Files.createDirectory(root.resolve("out"));
            Path target = Files.write(folder.resolve("target"), new byte[] {9});
            String uri = target.toUri().toString();
            LockIo io = new LockIo(manager, null);
            AtomicWriter writer = new AtomicWriter(owner, health, locks);
            byte[] next = new byte[1024 * 1024 + 13];
            Future<AtomicWriter.Result> write = worker.submit(() -> writer.write(io, uri, Mode.OVERWRITE,
                    out -> out.write(next), true, 1, 0));
            Path first = reached(copy, "temp.in-write");
            Path lock = folder.resolve("target.lock");
            assertFalse(Files.exists(lock));
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(target));
            assertTrue(new String(Files.readAllBytes(first), StandardCharsets.UTF_8).contains("bytes=1048576"));
            release(first);
            Path commit = reached(copy, "commit.after-lock-share");
            assertTrue(Files.exists(lock));
            assertArrayEquals(new byte[] {9}, Files.readAllBytes(target));
            assertTrue(new String(Files.readAllBytes(commit), StandardCharsets.UTF_8).contains("key=" + owner));
            release(commit);
            assertEquals(AtomicWriter.Outcome.COMMITTED, write.get(8, TimeUnit.SECONDS).outcome);
            assertArrayEquals(next, Files.readAllBytes(target));
            assertFalse(Files.exists(lock));

            TempName temp = TempNames.begin("other", ".part");
            Path stray = Files.write(folder.resolve(temp.name()), new byte[] {7});
            temp.end();
            String folderUri = folder.toUri().toString();
            TempCleanup cleanup = new TempCleanup(owner, health, locks, ".part");
            Files.write(copy.resolve("clock.cleanup.wall"), Arrays.asList("7200000"), StandardCharsets.UTF_8);
            assertEquals(0, cleanup.sweep(io, folderUri).removed());
            Files.write(copy.resolve("clock.cleanup.wall"), Arrays.asList("14400000"), StandardCharsets.UTF_8);
            assertEquals(0, cleanup.sweep(io, folderUri).removed());
            Files.write(copy.resolve("clock.cleanup.mono"), Arrays.asList("3600001"), StandardCharsets.UTF_8);
            Future<SweepResult> sweep = worker.submit(() -> cleanup.sweep(io, folderUri));
            Path before = reached(copy, "cleanup.sweep.before-first-delete");
            assertTrue(Files.exists(stray));
            release(before);
            Path after = reached(copy, "cleanup.after-delete");
            assertFalse(Files.exists(stray));
            release(after);
            assertEquals(1, sweep.get(8, TimeUnit.SECONDS).removed());
            List<String> events = Files.readAllLines(copy.resolve("events.log"), StandardCharsets.UTF_8);
            for (String point : Arrays.asList("temp.in-write", "commit.after-lock-share",
                    "cleanup.sweep.before-first-delete", "cleanup.after-delete")) {
                assertEquals(1, events.stream().filter(line -> line.contains("point=hold-reached")
                        && line.contains("at=" + point + "\t") && line.contains("connection=" + owner)).count());
            }
        } finally {
            worker.shutdownNow();
            HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION, owner);
            if (saved == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", saved);
            }
        }
    }
}
