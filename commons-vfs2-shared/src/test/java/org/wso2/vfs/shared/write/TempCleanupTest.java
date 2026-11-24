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

import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.ShareLock;

class TempCleanupTest {
    @TempDir Path folder;
    private static final long HOUR = TimeUnit.HOURS.toNanos(1);
    private final AtomicLong mono = new AtomicLong();
    private final AtomicLong wall = new AtomicLong(System.currentTimeMillis());
    private final DefaultFileSystemManager manager = new DefaultFileSystemManager();
    private EndpointHealth health;
    private ShareLock locks;
    private LockIo io;
    private TempCleanup sweep;
    private String uri;

    @BeforeEach
    void start() throws Exception {
        uri = folder.toUri().toString();
        manager.addProvider("file", new DefaultLocalFileProvider());
        manager.init();
        io = new LockIo(manager, null);
        health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, uri, 5000);
        locks = ShareLock.create(uri, true, 60000, health);
        sweep = sweeper(".part");
    }

    @AfterEach
    void stop() {
        manager.close();
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, uri);
    }

    private TempCleanup sweeper(String suffix) {
        return new TempCleanup(uri, health, locks, suffix, wall::get, mono::get);
    }

    private Path old(String target, String suffix) throws Exception {
        // The floor of the creation second, far older than any stale age.
        return Files.write(folder.resolve(target + ".0t85s000000001" + suffix), new byte[] {1});
    }

    @Test
    void ageIsFromTheNameAndTheHourIsMonotonic() throws Exception {
        Path stable = old("stable", ".part");
        Path growing = old("growing", ".part");
        Path missing = old("vanished", ".part");
        try (AtomicWriter.Temp temp = new AtomicWriter(uri, health, locks, false, "", ".part")
                .prepare(io, folder.resolve("young").toUri().toString())) {
            Path young = Files.write(AtomicWriterTest.path(temp.uri), new byte[] {1});
            temp.close();
            Files.setLastModifiedTime(young, FileTime.fromMillis(0));
            Path future = Files.write(folder.resolve("future.zzzzzzz0000001.part"), new byte[] {1});
            assertEquals(0, sweep.sweep(io, uri).removed());
            Files.delete(missing);
            Files.write(growing, new byte[] {2, 3});
            mono.set(HOUR - 1);
            assertEquals(0, sweep.sweep(io, uri).removed());
            mono.incrementAndGet();
            assertEquals(1, sweep.sweep(io, uri).removed());
            assertFalse(Files.exists(stable));
            assertTrue(Files.exists(young));
            assertTrue(Files.exists(growing));
            assertTrue(Files.exists(future));
            Files.write(missing, new byte[] {1});
            mono.addAndGet(HOUR);
            assertEquals(1, sweep.sweep(io, uri).removed());
            assertFalse(Files.exists(growing));
            assertTrue(Files.exists(missing));
        }
    }

    @Test
    void activeNamesStayAcrossAttemptsAndConnectionsAndRestartRestartsTheHour() throws Exception {
        TempCleanup other = sweeper(".part");
        TempName active = TempNames.begin("upload", ".part");
        Path file = Files.write(folder.resolve(active.name()), new byte[] {5});
        wall.addAndGet(TimeUnit.DAYS.toMillis(2));
        try {
            for (int round = 0; round < 3; round++) {
                assertEquals(0, sweep.sweep(io, uri).removed());
                assertEquals(0, other.sweep(io, uri).removed());
                mono.addAndGet(HOUR);
            }
            assertTrue(Files.exists(file));
        } finally {
            active.end();
        }
        assertEquals(0, sweep.sweep(io, uri).removed());
        mono.addAndGet(HOUR);
        TempCleanup restarted = sweeper(".part");
        assertEquals(0, restarted.sweep(io, uri).removed());
        mono.addAndGet(HOUR);
        assertEquals(1, restarted.sweep(io, uri).removed());
    }

    @Test
    void disabledNeverAndClampedAgeComeFromTheLock() {
        assertEquals(60000, ShareLock.create(uri, true, 1, health).staleAgeMillis());
        for (ShareLock noSweep : new ShareLock[] {ShareLock.create(uri, false, 60000, health),
                ShareLock.create(uri, true, -1, health)}) {
            assertEquals(-1, noSweep.staleAgeMillis());
            TempCleanup disabled = new TempCleanup(uri, health, noSweep, ".part");
            assertEquals(0, disabled.sweep(null, uri).removed());
        }
    }

    @Test
    void leavesWholeAndCutStagingMarkersBusinessFilesAndDirectories() throws Exception {
        TempCleanup next = sweeper(".next");
        Path whole = old("a.fail", ".next");
        Path cut = old("b.fail", ".next");
        Files.write(cut, new byte[0]);
        Path business = Files.write(folder.resolve("data.next"), new byte[] {1});
        Path directory = Files.createDirectory(folder.resolve("dir.0t85s000000001.next"));
        Path ordinary = old("other", ".next");
        assertEquals(0, next.sweep(io, uri).removed());
        mono.addAndGet(HOUR);
        assertEquals(1, next.sweep(io, uri).removed());
        assertFalse(Files.exists(ordinary));
        for (Path kept : new Path[] {whole, cut, directory, business}) {
            assertTrue(Files.exists(kept));
        }
    }

    @Test
    void stoppedDeleteKeepsTheAcknowledgedCountAndMakesNoFurtherCalls() throws Exception {
        old("a", ".part");
        old("b", ".part");
        old("c", ".part");
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger lists = new AtomicInteger();
        LockIo traced = new LockIo(manager, null) {
            @Override
            public FileObject resolve(String name) throws FileSystemException {
                assertEquals(uri, name, "Only the sweep folder may be resolved");
                return new DecoratedFileObject(super.resolve(name)) {
                    @Override public FileObject[] getChildren() throws FileSystemException {
                        lists.incrementAndGet();
                        FileObject[] children = super.getChildren();
                        for (int index = 0; index < children.length; index++) {
                            children[index] = new DecoratedFileObject(children[index]) {
                                @Override public boolean delete() throws FileSystemException {
                                    int call = deletes.incrementAndGet();
                                    assertTrue(call <= 2);
                                    if (call == 2) {
                                        throw AtomicWriterTest.fault(Fault.TRANSPORT);
                                    }
                                    return super.delete();
                                }
                            };
                        }
                        return children;
                    }
                };
            }
        };
        assertEquals(0, sweep.sweep(traced, uri).removed());
        mono.addAndGet(HOUR);
        SweepResult stopped = sweep.sweep(traced, uri);
        assertEquals(SweepResult.End.TRANSPORT_ERROR, stopped.ended());
        assertTrue(stopped.classification().getKind().isTransport());
        assertEquals(1, stopped.removed());
        assertEquals(2, lists.get());
        assertEquals(2, deletes.get());
    }

    @Test
    void missingFolderDoesNotListItsParent() {
        String absent = folder.resolve("missing").toUri().toString();
        LockIo traced = new LockIo(manager, null) {
            @Override public FileObject resolve(String name) throws FileSystemException {
                assertEquals(absent, name);
                return new DecoratedFileObject(super.resolve(name)) {
                    @Override public FileObject[] getChildren() { throw new AssertionError("Unexpected list"); }
                    @Override public FileObject getParent() { throw new AssertionError("Unexpected parent"); }
                };
            }
        };
        SweepResult missing = sweep.sweep(traced, absent);
        assertEquals(0, missing.removed());
        assertEquals(SweepResult.End.COMPLETE, missing.ended());
    }

    @Test
    void capAdvancesPastTheFirstHundredAndRemovesOnlyStableFiles() throws Exception {
        for (int number = 0; number < 101; number++) {
            old("f" + number, ".part");
        }
        assertEquals(SweepResult.End.CAPPED, sweep.sweep(io, uri).ended());
        mono.addAndGet(HOUR);
        assertEquals(99, sweep.sweep(io, uri).removed());
        mono.addAndGet(HOUR);
        assertEquals(2, sweep.sweep(io, uri).removed());
    }

    @Test
    void anotherCopyCanSweepAPausedUploadAndTheCommitThenFails() throws Exception {
        Path target = Files.write(folder.resolve("target"), new byte[] {1});
        Path source = Files.write(folder.resolve("source"), new byte[] {2, 3});
        AtomicWriter writer = new AtomicWriter(uri, health, locks, false, "", ".part");
        String targetUri = target.toUri().toString();
        try (URLClassLoader copy = new URLClassLoader(new URL[] {
                TempCleanup.class.getProtectionDomain().getCodeSource().getLocation()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("org.wso2.vfs.shared.write.")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    return type == null ? findClass(name) : type;
                }
            }
        }; AtomicWriter.Temp temp = writer.prepare(io, targetUri)) {
            Class<?> isolated = copy.loadClass(TempCleanup.class.getName());
            Constructor<?> constructor = isolated.getDeclaredConstructor(String.class, EndpointHealth.class,
                    ShareLock.class, String.class, LongSupplier.class, LongSupplier.class);
            constructor.setAccessible(true);
            Object cleaner = constructor.newInstance(uri, health, locks, ".part",
                    (LongSupplier) wall::get, (LongSupplier) mono::get);
            wall.addAndGet(TimeUnit.HOURS.toMillis(2));
            writer.transfer(io, temp, output -> {
                output.write(2);
                output.flush();
                try {
                    isolated.getMethod("sweep", LockIo.class, String.class).invoke(cleaner, io, uri);
                    assertTrue(Files.exists(AtomicWriterTest.path(temp.uri)));
                    mono.addAndGet(HOUR);
                    isolated.getMethod("sweep", LockIo.class, String.class).invoke(cleaner, io, uri);
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
                assertFalse(Files.exists(AtomicWriterTest.path(temp.uri)));
                output.write(3);
            });
            HeldLock held = locks.enterLocalOnly(targetUri, 1, 0).handle;
            try {
                AtomicWriter.Result result = writer.commit(io, held, temp, CommitStep.Mode.OVERWRITE,
                        false, 1, 0, true, null);
                assertEquals(AtomicWriter.Outcome.ERROR, result.outcome);
                assertFalse(result.connectionLost());
                assertArrayEquals(new byte[] {1}, Files.readAllBytes(target));
                assertArrayEquals(new byte[] {2, 3}, Files.readAllBytes(source));
            } finally {
                held.releaseChecked(io);
            }
        }
    }
}
