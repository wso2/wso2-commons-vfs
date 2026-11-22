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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.CommitResult;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep.Mode;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;

class AtomicWriterTest {
    @TempDir Path dir;
    final String owner = "file:" + UUID.randomUUID();
    final DefaultFileSystemManager manager = new DefaultFileSystemManager();
    final byte[] old = {1, 2};
    final byte[] next = {3, 4, 5};
    EndpointHealth health;
    ShareLock locks;
    LockIo io;
    AtomicWriter writer;
    Path target;
    String uri;

    @BeforeEach
    void start() throws Exception {
        manager.addProvider("file", new DefaultLocalFileProvider());
        manager.init();
        io = new LockIo(manager, null);
        health = HealthRegistry.register(HealthRegistry.Kind.CONNECTION, owner, 0);
        locks = ShareLock.create(owner, true, 60000, health);
        writer = new AtomicWriter(owner, health, locks);
        target = dir.resolve("a.csv");
        uri = target.toUri().toString();
    }

    @AfterEach
    void stop() {
        manager.close();
        HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION, owner);
    }

    static FileSystemException fault(Fault kind) {
        FileSystemException error = new FileSystemException("Test reply.");
        error.setFault(kind);
        return error;
    }

    static Path path(String uri) {
        return Paths.get(URI.create(uri));
    }

    @Test
    void wholeContentAndMetadataAreVisibleAtPublication() throws Exception {
        Files.write(target, old);
        Path lock = dir.resolve("a.csv.lock");
        AtomicWriter.Result result = writer.write(io, uri, Mode.OVERWRITE, output -> {
            assertFalse(Files.exists(lock));
            output.write(next);
            assertArrayEquals(old, Files.readAllBytes(target));
            assertEquals(LockOutcome.Kind.LOCK_BUSY, locks.enterLocalOnly(uri, 1, 0).kind);
        }, true, 1, 0, temp -> {
            assertFalse(Files.exists(lock));
            temp.getContent().setLastModifiedTime(1234000);
        });
        assertEquals(AtomicWriter.Outcome.COMMITTED, result.outcome);
        assertEquals(CommitResult.Method.REPLACING_RENAME, result.commit.getMethod());
        assertArrayEquals(next, Files.readAllBytes(target));
        assertEquals(1234000, Files.getLastModifiedTime(target).toMillis());
        assertFalse(Files.exists(lock));
        assertEquals(0, result.sweep().removed());
    }

    @Test
    void aCutBeforeCommitLeavesExistingOrAbsentTargetAndNoLock() throws Exception {
        for (boolean existing : new boolean[] {false, true}) {
            if (existing) {
                Files.write(target, old);
            }
            AtomicWriter.Result result = writer.write(io, uri, Mode.OVERWRITE, output -> {
                output.write(next[0]);
                throw fault(Fault.TRANSPORT);
            }, true, 1, 0);
            assertTrue(result.connectionLost());
            assertEquals(AtomicWriter.Outcome.ERROR, result.outcome);
            assertEquals(existing, Files.exists(target));
            if (existing) {
                assertArrayEquals(old, Files.readAllBytes(target));
            }
            assertFalse(Files.exists(dir.resolve("a.csv.lock")));
        }
        try (Stream<Path> files = Files.list(dir.resolve(".part"))) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void definiteErrorDeletesTempAndLostMetadataReplyStopsBeforePublication() throws Exception {
        Files.write(target, old);
        AtomicWriter.Result denied = writer.write(io, uri, Mode.OVERWRITE, out -> {
            out.write(next);
            throw fault(Fault.APPLICATION);
        }, true, 1, 0);
        assertEquals(AtomicWriter.Outcome.ERROR, denied.outcome);
        try (Stream<Path> files = Files.list(dir.resolve(".part"))) {
            assertEquals(0, files.count());
        }
        AtomicWriter.Result cut = writer.write(io, uri, Mode.OVERWRITE, out -> out.write(next), true, 1, 0,
                file -> { throw fault(Fault.TRANSPORT); });
        assertTrue(cut.connectionLost());
        assertArrayEquals(old, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("a.csv.lock")));
        try (Stream<Path> files = Files.list(dir.resolve(".part"))) {
            assertArrayEquals(next, Files.readAllBytes(files.findFirst().get()));
        }
        assertEquals(AtomicWriter.Outcome.COMMITTED, writer.write(io, uri, Mode.OVERWRITE, out -> out.write(next),
                false, 1, 0, file -> { throw fault(Fault.APPLICATION); }).outcome);
    }

    @Test
    void retryOnFreshManagerCommitsExistingTempWithoutTransfer() throws Exception {
        Files.write(target, old);
        try (AtomicWriter.Temp temp = writer.prepare(io, uri);
                DefaultFileSystemManager fresh = new DefaultFileSystemManager()) {
            writer.transfer(io, temp, out -> out.write(next));
            fresh.addProvider("file", new DefaultLocalFileProvider());
            fresh.init();
            LockIo other = new LockIo(fresh, null);
            assertTrue(TempNames.inUse(temp.name.name()));
            assertTrue(AtomicOps.existsNow(other.resolve(temp.uri)));
            HeldLock held = locks.enterLocalOnly(uri, 1, 0).handle;
            try {
                AtomicWriter.Result saved = writer.commit(other, held, temp, Mode.OVERWRITE, true, 1, 0, true, null);
                assertEquals(AtomicWriter.Outcome.COMMITTED, saved.outcome);
                assertArrayEquals(next, Files.readAllBytes(target));
                assertFalse(Files.exists(path(temp.uri)));
                assertEquals(LockOutcome.Kind.LOCK_BUSY, locks.enterLocalOnly(uri, 1, 0).kind);
            } finally {
                held.releaseChecked(other);
            }
        }
    }

    @Test
    void createNewChecksBeforeTransferAndAgainAtCommit() throws Exception {
        Files.write(target, old);
        assertEquals(AtomicWriter.Outcome.TARGET_EXISTS, writer.write(io, uri, Mode.CREATE_NEW,
                out -> fail("Unexpected transfer"), false, 1, 0).outcome);
        Files.delete(target);
        AtomicWriter.Result result = writer.write(io, uri, Mode.CREATE_NEW, out -> {
            out.write(next);
            Files.write(target, old);
        }, true, 1, 0);
        assertEquals(AtomicWriter.Outcome.TARGET_EXISTS, result.outcome);
        assertArrayEquals(old, Files.readAllBytes(target));
        Files.delete(target);
        assertEquals(CommitResult.Method.LINK, writer.write(io, uri, Mode.CREATE_NEW,
                out -> out.write(next), true, 1, 0).commit.getMethod());
        assertArrayEquals(next, Files.readAllBytes(target));
    }

    @Test
    void deniedFolderUsesParentAndRemembersFallback() throws Exception {
        Path hidden = Files.write(dir.resolve(".part"), old);
        try (AtomicWriter.Temp first = writer.prepare(io, uri)) {
            assertEquals(dir, path(first.uri).getParent());
        }
        Files.delete(hidden);
        try (AtomicWriter.Temp again = writer.prepare(io, uri)) {
            assertEquals(dir, path(again.uri).getParent());
            assertFalse(Files.exists(hidden));
        }
    }

    @Test
    void cutAfterOldTargetDeletionRetainsTempWhileDefinitiveRefusalCleansIt() throws Exception {
        checkDeletionWarnings(false);
    }

    @Test
    void firstTransportCutWarnsAboutTheMissingTargetWindow() throws Exception {
        checkDeletionWarnings(true);
    }

    private void checkDeletionWarnings(boolean firstCut) throws Exception {
        AtomicInteger warnings = new AtomicInteger();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains(owner) && record.getMessage().contains("no replacing rename")) {
                    warnings.incrementAndGet();
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger(EndpointHealth.class.getName());
        logger.addHandler(handler);
        try {
            afterDeletion(firstCut);
            assertEquals(1, warnings.get());
            Files.deleteIfExists(dir.resolve("a.csv.lock"));
            afterDeletion(!firstCut);
            assertEquals(1, warnings.get());
        } finally {
            logger.removeHandler(handler);
        }
    }

    private void afterDeletion(boolean cut) throws Exception {
        Files.write(target, old);
        try (AtomicWriter.Temp temp = writer.prepare(io, uri)) {
            writer.transfer(io, temp, out -> out.write(next));
            FileObject original = io.resolve(temp.uri);
            LocalFileName name = (LocalFileName) original.getName();
            LocalFile nonReplacing = new LocalFile((LocalFileSystem) original.getFileSystem(), name.getRootFile(), name) {
                @Override public AtomicResult renameReplacing(FileObject target, FileObject probeA, FileObject probeB) {
                    for (FileObject probe : new FileObject[] {probeA, probeB}) {
                        String base = probe.getName().getBaseName();
                        assertEquals("vfsprobe", TempNames.parse(base, ".part").targetName());
                        assertTrue(TempNames.inUse(base));
                    }
                    return new AtomicResult(AtomicOutcome.UNSUPPORTED, "Known refusal");
                }
                @Override public AtomicResult takeAway(FileObject to) throws FileSystemException {
                    assertFalse(Files.exists(target));
                    if (cut) {
                        throw fault(Fault.TRANSPORT);
                    }
                    return new AtomicResult(AtomicOutcome.REFUSED, "Definite refusal");
                }
            };
            LockIo controlled = new LockIo(manager, null) {
                @Override public FileObject resolve(String ref) throws FileSystemException {
                    return ref.equals(temp.uri) ? nonReplacing : super.resolve(ref);
                }
            };
            HeldLock held = locks.enterLocalOnly(uri, 1, 0).handle;
            try {
                AtomicWriter.Result result = writer.commit(controlled, held, temp, Mode.OVERWRITE,
                        true, 1, 0, true, null);
                assertEquals(AtomicWriter.Outcome.ERROR, result.outcome);
                assertEquals(cut, result.connectionLost());
                assertFalse(Files.exists(target));
                if (cut) {
                    assertEquals(MovePhase.AFTER_DELETE, result.failure.getPhase());
                    assertArrayEquals(next, Files.readAllBytes(path(temp.uri)));
                    assertTrue(Files.exists(dir.resolve("a.csv.lock")));
                } else {
                    assertTrue(result.commit.isOldTargetDeleted());
                    assertFalse(Files.exists(path(temp.uri)));
                    assertFalse(Files.exists(dir.resolve("a.csv.lock")));
                }
            } finally {
                held.releaseChecked(io);
            }
        }
    }

    @Test
    void lostSweepAfterPublicationPreservesSuccess() throws Exception {
        try (AtomicWriter.Temp temp = writer.prepare(io, uri)) {
            writer.transfer(io, temp, out -> out.write(next));
            LockIo cut = new LockIo(manager, null) {
                @Override public FileObject resolve(String ref) throws FileSystemException {
                    if (ref.equals(temp.folder)) {
                        assertFalse(Files.exists(dir.resolve("a.csv.lock")));
                        throw fault(Fault.TRANSPORT);
                    }
                    return super.resolve(ref);
                }
            };
            HeldLock held = locks.enterLocalOnly(uri, 1, 0).handle;
            AtomicWriter.Result result = writer.commit(cut, held, temp, Mode.OVERWRITE, true, 1, 0, true, null);
            assertEquals(AtomicWriter.Outcome.COMMITTED, result.outcome);
            assertEquals(SweepResult.End.TRANSPORT_ERROR, result.sweep().ended());
            assertTrue(result.connectionLost());
            assertArrayEquals(next, Files.readAllBytes(target));
            assertEquals(LockOutcome.Kind.ENDED, held.releaseChecked(io).kind);
        }
    }

    @Test
    void concurrentFirstUsersShareTheFolderDecision() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        FileObject actual = io.resolve(uri);
        FileObject folder = new DecoratedFileObject(actual.getParent()) {
            @Override public FileObject resolveFile(String name, NameScope scope) throws FileSystemException {
                return new DecoratedFileObject(super.resolveFile(name, scope)) {
                    @Override public void createFolder() throws FileSystemException {
                        creates.incrementAndGet();
                        super.createFolder();
                    }
                };
            }
        };
        LockIo together = new LockIo(manager, null) {
            @Override public FileObject resolve(String ref) {
                return new DecoratedFileObject(actual) {
                    @Override public FileObject getParent() { return folder; }
                };
            }
        };
        Callable<String> prepare = () -> {
            ready.countDown();
            assertTrue(go.await(5, TimeUnit.SECONDS));
            try (AtomicWriter.Temp temp = writer.prepare(together, uri)) {
                return temp.folder;
            }
        };
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = threads.submit(prepare);
            Future<String> second = threads.submit(prepare);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, creates.get());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void oldestOfOneThousandFolderNotesIsEvictedAndLateFailureKeepsTheNewNote() throws Exception {
        String first = dir.resolve("first").resolve("file").toUri().toString();
        try (AtomicWriter.Temp original = writer.prepare(io, first)) {
            Path hidden = path(original.folder);
            for (int number = 1; number <= 1000; number++) {
                try (AtomicWriter.Temp staged = writer.prepare(io,
                        dir.resolve("other" + number).resolve("file").toUri().toString())) {
                    assertTrue(TempNames.inUse(staged.name.name()));
                }
            }
            Files.delete(hidden);
            Files.createFile(hidden);
            try (AtomicWriter.Temp fallback = writer.prepare(io, first)) {
                assertEquals(hidden.getParent(), path(fallback.folder));
                LockIo missing = new LockIo(manager, null) {
                    @Override public FileObject resolve(String ref) throws FileSystemException {
                        throw fault(Fault.MISSING);
                    }
                };
                assertThrows(IOException.class, () -> writer.transfer(missing, original, out -> out.write(next)));
            }
            Files.delete(hidden);
            try (AtomicWriter.Temp kept = writer.prepare(io, first)) {
                assertEquals(hidden.getParent(), path(kept.folder));
                assertFalse(Files.exists(hidden));
            }
        }
    }

    @Test
    void missingFolderInvalidatesTheNoteAndTheNextAttemptCreatesIt() throws Exception {
        try (AtomicWriter.Temp first = writer.prepare(io, uri)) {
            Path hidden = path(first.folder);
            Files.delete(hidden);
            LockIo missing = new LockIo(manager, null) {
                @Override public FileObject resolve(String name) throws FileSystemException {
                    if (name.equals(first.uri)) {
                        throw fault(Fault.MISSING);
                    }
                    return super.resolve(name);
                }
            };
            assertThrows(IOException.class, () -> writer.transfer(missing, first, out -> out.write(next)));
            try (AtomicWriter.Temp retry = writer.prepare(io, uri)) {
                assertTrue(Files.isDirectory(hidden));
                assertEquals(first.folder, retry.folder);
                assertNotEquals(first.uri, retry.uri);
            }
        }
    }

    @Test
    void applicationFailureWithALostCloseLeavesTheTempAndReleasesLocally() throws Exception {
        Files.write(target, old);
        AtomicInteger touched = new AtomicInteger();
        LockIo failing = new LockIo(manager, null) {
            @Override public FileObject resolve(String name) throws FileSystemException {
                assertEquals(0, touched.get(), "Unexpected call after the stream close failed");
                FileObject original = super.resolve(name);
                if (TempNames.parse(original.getName().getBaseName(), ".part") == null) {
                    return original;
                }
                return new DecoratedFileObject(original) {
                    @Override public FileContent getContent() throws FileSystemException {
                        FileContent content = super.getContent();
                        return (FileContent) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {FileContent.class}, (proxy, method, args) -> {
                            if (!method.getName().equals("getOutputStream")) {
                                return method.invoke(content, args);
                            }
                            return new FilterOutputStream(content.getOutputStream(false)) {
                                @Override public void close() throws IOException {
                                    super.close();
                                    touched.incrementAndGet();
                                    throw fault(Fault.TRANSPORT);
                                }
                            };
                        });
                    }
                };
            }
        };
        AtomicWriter.Result result = writer.write(failing, uri, Mode.OVERWRITE, out -> {
            out.write(next);
            throw fault(Fault.APPLICATION);
        }, true, 1, 0);
        assertEquals(AtomicWriter.Outcome.ERROR, result.outcome);
        assertTrue(result.connectionLost());
        assertArrayEquals(old, Files.readAllBytes(target));
        assertEquals(1, touched.get());
        try (Stream<Path> files = Files.list(dir.resolve(".part"))) {
            assertArrayEquals(next, Files.readAllBytes(files.findFirst().get()));
        }
        locks.enterLocalOnly(uri, 1, 0).handle.abandon();
    }

    @Test
    void slowTransferKeepsTheShareWaitAvailable() throws Exception {
        Path marker = dir.resolve("a.csv.lock");
        Files.write(marker, ("1:foreign:127.0.0.1:" + System.currentTimeMillis() + ":v2:" + UUID.randomUUID())
                .getBytes("UTF-8"));
        CountDownLatch firstLook = new CountDownLatch(1);
        LockIo observed = new LockIo(manager, null) {
            @Override public FileObject resolve(String ref) throws FileSystemException {
                if (ref.endsWith(".lock")) {
                    firstLook.countDown();
                }
                return super.resolve(ref);
            }
        };
        FutureTask<Void> release = new FutureTask<>(() -> {
            assertTrue(firstLook.await(5, TimeUnit.SECONDS));
            Thread.sleep(40);
            Files.delete(marker);
            return null;
        });
        new Thread(release).start();
        AtomicWriter.Result result = writer.write(observed, uri, Mode.OVERWRITE, output -> {
            try {
                Thread.sleep(550);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            assertTrue(Files.exists(marker));
            output.write(next);
        }, true, 3, 200);
        release.get(5, TimeUnit.SECONDS);
        assertEquals(AtomicWriter.Outcome.COMMITTED, result.outcome);
        assertArrayEquals(next, Files.readAllBytes(target));
    }

    @Test
    void foreignLockAtCommitLeavesOldTargetAndCleansOnlyOurTemp() throws Exception {
        Files.write(target, old);
        AtomicInteger transfers = new AtomicInteger();
        HeldLock remote = locks.acquire(io, uri, ShareLock.Mode.WRITER, null).handle;
        assertNotNull(remote);
        remote.abandon();
        Path lock = dir.resolve("a.csv.lock");
        Files.write(lock, ("1:foreign:127.0.0.1:" + System.currentTimeMillis() + ":v2:" + UUID.randomUUID()).getBytes("UTF-8"));
        AtomicWriter.Result busy = writer.write(io, uri, Mode.OVERWRITE, out -> {
            transfers.incrementAndGet();
            out.write(next);
        }, true, 1, 0);
        assertEquals(AtomicWriter.Outcome.LOCK_BUSY, busy.outcome);
        assertEquals(1, transfers.get());
        assertArrayEquals(old, Files.readAllBytes(target));
        assertTrue(Files.exists(lock));
        try (Stream<Path> files = Files.list(dir.resolve(".part"))) {
            assertEquals(0, files.count());
        }
    }
}
