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
import static org.wso2.vfs.shared.failstate.FailStateStore.Decision.*;
import static org.wso2.vfs.shared.failstate.FailStateStore.Write.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFile;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileName;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.FolderListing;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.write.TempName;
import org.wso2.vfs.shared.write.TempNames;

class FailStateStoreTest {
    @TempDir Path dir;
    final String endpoint = "store" + UUID.randomUUID();
    final DefaultFileSystemManager manager = new DefaultFileSystemManager();
    final AtomicLong wall = new AtomicLong(1700000000000L);
    final AtomicLong monotonic = new AtomicLong();
    final FailStateStore.Parameters params = new FailStateStore.Parameters();
    final ActionResult refused = new ActionResult(ActionResult.Step.NONE, true, null, false, null);
    Path data;
    Path marker;
    String uri;
    ShareLock locks;
    EndpointHealth health;
    FailStateStore store;
    LockIo io;

    @BeforeEach
    void start() throws Exception {
        manager.addProvider("file", new DefaultLocalFileProvider());
        manager.init();
        io = new LockIo(manager, null);
        health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 10000);
        locks = ShareLock.create(endpoint, true, 60000, health);
        store = fresh();
        use("b.csv");
        Files.write(data, new byte[1024]);
    }

    FailStateStore fresh() {
        return new FailStateStore(endpoint, params, locks, health, wall::get, monotonic::get);
    }

    void use(String name) {
        data = dir.resolve(name);
        uri = data.toUri().toString();
        marker = dir.resolve(name + ".fail");
    }

    @AfterEach
    void close() {
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
        manager.close();
    }

    FolderListing listing() throws Exception {
        Map<String, FolderListing.Metadata> files = new HashMap<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.collect(Collectors.toList())) {
                FileObject file = io.resolve(path.toUri().toString());
                files.put(file.getName().getBaseName(), new FolderListing.Metadata(Files.size(path),
                        Files.getLastModifiedTime(path).toMillis()));
            }
        }
        return FolderListing.of(dir.toUri().toString(), files);
    }

    HeldLock acquire(FolderListing listing) {
        LockOutcome lock = locks.acquire(io, uri, ShareLock.Mode.LISTENER, listing);
        assertEquals(LockOutcome.Kind.ACQUIRED, lock.kind);
        return lock.handle;
    }

    void released(HeldLock held) {
        assertEquals(LockOutcome.Kind.ENDED, held.verifyOwner(io).kind);
        assertFalse(Files.exists(dir.resolve(data.getFileName() + ".lock")));
    }

    FailState record() throws Exception {
        FailState record = FailState.parse(Files.readAllBytes(marker), uri, data.getFileName().toString());
        assertNotNull(record);
        return record;
    }

    List<Path> stages() throws Exception {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".next")).collect(Collectors.toList());
        }
    }

    FailState startFailure() throws Exception {
        HeldLock held = acquire(null);
        FailStateStore.BeginResult entry = store.beginFile(io, held, uri);
        assertEquals(PROCESS, entry.decision);
        assertEquals(WRITTEN, store.recordFailure(io, held, entry.identity, FailState.Outcome.SUCCESS,
                FailState.Action.DELETE, null, refused));
        released(held);
        return record();
    }

    @Test
    void writeResultKeepsTheDispositionAndTheFailureOfThisCall() throws Exception {
        params.retryMillis = 0;
        for (boolean retry : Arrays.asList(false, true)) {
            for (String stage : Arrays.asList("ordinary", "refused", "lost", "release")) {
                use("result" + stage + retry);
                Files.write(data, new byte[] {3, 4});
                if (retry) {
                    startFailure();
                    wall.incrementAndGet();
                }
                HeldLock held = acquire(null);
                FailStateStore.BeginResult begin = store.beginFile(io, held, uri);
                assertEquals(retry ? RETRY : PROCESS, begin.decision);
                LockIo observed = new LockIo(manager, null) {
                    @Override
                    public FileObject resolve(String name) throws FileSystemException {
                        if (stage.equals("release") && name.endsWith("/" + data.getFileName() + ".lock")
                                && Files.exists(marker)) {
                            try {
                                if (record().attempts == (retry ? 1 : 0)) {
                                    throw fault(Fault.TRANSPORT);
                                }
                            } catch (FileSystemException failure) {
                                throw failure;
                            } catch (Exception failure) {
                                throw new AssertionError(failure);
                            }
                        }
                        if ((stage.equals("refused") || stage.equals("lost")) && !name.endsWith(".lock")) {
                            throw fault(stage.equals("lost") ? Fault.TRANSPORT : Fault.APPLICATION);
                        }
                        return super.resolve(name);
                    }
                };
                FailStateStore.WriteResult result = retry
                        ? store.retryFailureResult(observed, held, begin.state, FailState.Action.DELETE, null, refused)
                        : store.recordFailureResult(observed, held, begin.identity, FailState.Outcome.SUCCESS,
                                FailState.Action.DELETE, null, refused);
                if (stage.equals("ordinary")) {
                    assertEquals(WRITTEN, result.disposition);
                    assertNull(result.failure);
                    released(held);
                } else if (stage.equals("release")) {
                    assertEquals(WRITTEN, result.disposition);
                    assertTrue(result.failure.getKind().isTransport());
                    assertEquals(retry ? 1 : 0, record().attempts);
                    assertEquals(LockOutcome.Kind.ENDED, held.verifyOwner(io).kind);
                } else {
                    assertEquals(PENDING, result.disposition);
                    assertEquals(stage.equals("lost"), result.failure.getKind().isTransport());
                    assertEquals(LockOutcome.Kind.OURS, held.verifyOwner(io).kind);
                    wall.addAndGet(1800001);
                    assertEquals(1, store.runPending(io).written);
                    released(held);
                }
            }
        }
    }

    @Test
    void sameRelativeTargetRetainsTheFailedMoveDestinationAcrossMarkerReads() throws Exception {
        params.retryMillis = 0;
        params.moveAfterFailedMove = id -> "daily.csv";
        HeldLock first = acquire(null);
        FileIdentity id = store.beginFile(io, first, uri).identity;
        assertEquals(WRITTEN, store.recordFailureResult(io, first, id, FailState.Outcome.SUCCESS,
                FailState.Action.MOVE, "daily.csv", refused).disposition);
        released(first);
        FailState primary = record();
        assertEquals("daily.csv", primary.failedTarget);
        assertEquals("daily.csv", primary.target);
        assertEquals(FailState.MoveOrigin.ORDINARY, primary.moveOrigin);
        assertEquals(FailState.Action.MOVE_AFTER_FAILED_MOVE, primary.next);
        wall.incrementAndGet();
        HeldLock second = acquire(null);
        FailState due = store.beginFile(io, second, uri).state;
        assertEquals(WRITTEN, store.retryFailureResult(io, second, due, FailState.Action.MOVE_AFTER_FAILED_MOVE,
                "daily.csv", refused).disposition);
        released(second);
        FailState fallback = record();
        assertEquals(primary.failedTarget, fallback.failedTarget);
        assertEquals(primary.target, fallback.target);
        assertEquals(FailState.MoveOrigin.FALLBACK, fallback.moveOrigin);
        assertEquals(FailState.Action.MOVE, fallback.failed);
        assertEquals(1, fallback.attempts);
    }

    @Test
    void failureBeforeRetryActionRetainsBothRecordedPathsAndTheEarlierMoveOrigin() throws Exception {
        params.retryMillis = 0;
        params.moveAfterFailedMove = id -> "fallback" + wall.get() + ".csv";
        HeldLock first = acquire(null);
        FileIdentity id = store.beginFile(io, first, uri).identity;
        assertEquals(WRITTEN, store.recordFailure(io, first, id, FailState.Outcome.SUCCESS, FailState.Action.MOVE,
                "original.csv", refused));
        FailState original = record();
        wall.addAndGet(1000);
        HeldLock next = acquire(null);
        FailState previous = store.beginFile(io, next, uri).state;
        assertEquals(WRITTEN, store.retryUnchanged(io, next, previous, null).disposition);
        FailState retained = record();
        assertEquals(original.failedTarget, retained.failedTarget);
        assertEquals(original.target, retained.target);
        assertEquals(original.moveOrigin, retained.moveOrigin);
        assertEquals(original.action.phase, retained.action.phase);
        assertEquals(original.attempts + 1, retained.attempts);
        assertTrue(retained.written > original.written);
        released(next);
    }

    @Test
    void takeoverDropsOnlyThePendingThresholdContribution() throws Exception {
        params.flagAfter = 12;
        params.retryMillis = 0;
        for (int durable : Arrays.asList(11, 12)) {
            use("retry" + durable);
            Files.write(data, new byte[] {1, 2});
            startFailure();
            for (int i = 1; i <= durable + 1; i++) {
                wall.incrementAndGet();
                HeldLock held = acquire(null);
                FailStateStore.BeginResult due = store.beginFile(io, held, uri);
                assertEquals(RETRY, due.decision);
                boolean pending = i > durable;
                assertEquals(pending ? PENDING : WRITTEN,
                        store.retryFailed(pending ? null : io, held, due.state, FailState.Action.DELETE, null, refused));
            }
            assertEquals(1L, health.snapshot().get("failStatesFlagged"));
            assertEquals(durable, record().attempts);
            Files.delete(dir.resolve(data.getFileName() + ".lock"));
            wall.incrementAndGet();
            assertEquals(0, store.runPending(io).pending);
            assertEquals(durable == 12 ? 1L : 0L, health.snapshot().get("failStatesFlagged"));
            Files.delete(data);
            Files.delete(marker);
            if (durable == 12) {
                LockIo denied = new LockIo(manager, null) {
                    @Override public FileObject resolve(String ref) throws FileSystemException {
                        if (ref.equals(uri)) {
                            throw fault(Fault.APPLICATION);
                        }
                        return super.resolve(ref);
                    }
                };
                assertNotNull(store.checkAbsentSingleFile(denied, uri, listing()).failure);
                assertEquals(1L, health.snapshot().get("failStatesFlagged"));
            }
            store.checkAbsentSingleFile(io, uri, listing());
            assertEquals(0L, health.snapshot().get("failStatesFlagged"));
        }
    }

    @Test
    void lostCloseTakesPrecedenceOverReadFailure() throws Exception {
        for (Fault primary : Arrays.asList(Fault.MISSING, Fault.BUSY)) {
            for (boolean lostClose : Arrays.asList(false, true)) {
                class ReadFailureIo extends LockIo {
                    boolean markerOnly;
                    boolean closed;
                    int callsAfterClose;
                    ReadFailureIo() { super(manager, null); }
                    @Override public FileObject resolve(String ref) throws FileSystemException {
                        if (closed) {
                            callsAfterClose++;
                        }
                        FileObject file = super.resolve(ref);
                        if (markerOnly && !ref.endsWith(".fail")) {
                            return file;
                        }
                        LocalFileName name = (LocalFileName) file.getName();
                        return new LocalFile((LocalFileSystem) file.getFileSystem(), name.getRootFile(), name) {
                            @Override protected InputStream doGetInputStream(int size) {
                                return new InputStream() {
                                    @Override public int read() throws java.io.IOException { throw fault(primary); }
                                    @Override public void close() throws java.io.IOException {
                                        closed = true;
                                        if (lostClose) {
                                            throw fault(Fault.TRANSPORT);
                                        }
                                    }
                                };
                            }
                        };
                    }
                }
                ReadFailureIo failing = new ReadFailureIo();
                DigestResult digest = FileDigest.of(failing, uri);
                assertEquals(lostClose ? DigestResult.Kind.TRANSPORT_ERROR : DigestResult.Kind.ERROR, digest.kind);
                assertEquals(lostClose ? "TRANSPORT" : primary.name(), digest.failure.getKind().name());
                assertEquals(!lostClose && primary == Fault.MISSING ? 1 : 0, failing.callsAfterClose);
                failing = new ReadFailureIo();
                failing.markerOnly = true;
                Files.write(marker, "1700000000000".getBytes(StandardCharsets.UTF_8));
                HeldLock held = acquire(null);
                FailStateStore.BeginResult found = store.beginFile(failing, held, uri);
                assertEquals(lostClose ? TRANSPORT_ERROR : SKIP, found.decision);
                assertEquals(lostClose ? "TRANSPORT" : primary.name(), found.failure.getKind().name());
                assertEquals(!lostClose && primary == Fault.MISSING ? 1 : 0, failing.callsAfterClose);
                held.releaseChecked(io);
            }
        }
    }

    @Test
    void recoveredStageUsesTheBaseNameFromTheSmb2Listing() throws Exception {
        String remote = "smb2://server/share/";
        org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileNameParser parser =
                org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileNameParser.getInstance();
        manager.addProvider("smb2", new DefaultLocalFileProvider());
        org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext context =
                (org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext.class},
                        (proxy, method, values) -> manager);
        for (String literal : Arrays.asList("invoice[1]", "report #1", "literal%5b")) {
            String stage = literal + ".fail.0t9k2m17f3k9q2.next";
            String dataRef = remote + org.wso2.org.apache.commons.vfs2.provider.UriParser.encode(literal, new char[] {' ', '#'});
            String stageRef = remote + org.wso2.org.apache.commons.vfs2.provider.UriParser.encode(stage, new char[] {' ', '#'});
            String requested = parser.parseUri(context, null, dataRef).getURI();
            String actual = parser.parseUri(context, null, dataRef).getBaseName();
            String staged = parser.parseUri(context, null, stageRef).getBaseName();
            Map<String, FolderListing.Metadata> entries = new HashMap<>();
            entries.put(staged, new FolderListing.Metadata(1, 1));
            FolderListing absent = FolderListing.of(remote, entries);
            assertEquals(actual, absent.markedBase(requested));
            assertEquals(Collections.singletonList(staged), absent.stagingNames(actual));
            entries.put(actual, new FolderListing.Metadata(2, 0));
            FolderListing folder = FolderListing.of(remote, entries);
            use(literal);
            Files.write(data, new byte[] {3, 4});
            startFailure();
            Files.move(marker, dir.resolve(stage));
            LockIo mapped = new LockIo(manager, null) {
                @Override public FileObject resolve(String ref) throws FileSystemException {
                    String name = parser.parseUri(context, null, ref).getBaseName();
                    String decoded = org.wso2.org.apache.commons.vfs2.provider.UriParser.decode(name);
                    return io.resolve(dir.resolve(decoded).toUri().toString());
                }
            };
            LockOutcome lock = locks.acquire(mapped, requested, ShareLock.Mode.LISTENER, folder);
            assertEquals(LockOutcome.Kind.ACQUIRED, lock.kind);
            assertEquals(SKIP, fresh().beginFile(mapped, lock.handle, requested).decision);
            lock.handle.releaseChecked(mapped);
            assertTrue(Files.isRegularFile(marker));
            assertFalse(Files.exists(dir.resolve(stage)));
        }
    }

    @Test
    void recursivePollSharesBudgetAndForgetsAbsenceInEveryListing() throws Exception {
        Path home = dir;
        Path first = Files.createDirectory(home.resolve("a"));
        Path second = Files.createDirectory(home.resolve("b"));
        Path third = Files.createDirectory(home.resolve("z"));
        dir = third;
        TempName temp = TempNames.begin("m.fail", ".next");
        temp.end();
        Path cut = dir.resolve(temp.name());
        wall.set(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2));
        Files.write(cut, new byte[] {1});
        assertEquals(0, store.sweep(io, listing()).removed);
        Files.delete(cut);
        List<FolderListing> all = new ArrayList<>();
        for (Path folder : Arrays.asList(first, second)) {
            dir = folder;
            for (int i = 0; i < 12; i++) {
                Files.write(dir.resolve("n" + i + ".fail"), "1700000000000".getBytes(StandardCharsets.UTF_8));
            }
            all.add(listing());
        }
        dir = third;
        all.add(listing());
        assertEquals(20, store.sweep(io, all).removed);
        monotonic.addAndGet(TimeUnit.MINUTES.toNanos(61));
        Files.write(cut, new byte[] {2});
        all.clear();
        for (Path folder : Arrays.asList(first, second, third)) {
            dir = folder;
            all.add(listing());
        }
        assertEquals(4, store.sweep(io, all).removed);
        assertTrue(Files.exists(cut));
        monotonic.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertEquals(1, store.sweep(io, listing()).removed);
        assertFalse(Files.exists(cut));
        dir = home;
    }

    @Test
    void e426BackoffThroughFourteenFailuresAndOneFlag() throws Exception {
        long start = wall.get();
        List<LogRecord> messages = new ArrayList<>();
        Handler capture = new Handler() {
            public void publish(LogRecord record) { messages.add(record); }
            public void flush() { }
            public void close() { }
        };
        Logger logger = Logger.getLogger(EndpointHealth.class.getName());
        logger.addHandler(capture);
        try {
            FailState current = startFailure();
            for (int n = 0; n <= 14; n++) {
                String schedule = n == 0 ? "[the first failure is recorded with a 30 s wait]"
                        : "[each failed retry doubles the wait up to 30 minutes]";
                assertEquals(n, current.attempts, schedule);
                if (n == 0) {
                    assertEquals(start, current.written, "[the first failure is recorded with a 30 s wait]");
                }
                assertEquals(Math.min(30000L << n, 1800000), current.due - current.written, schedule);
                if (n == 14) {
                    System.out.println("Observed: each failed retry doubles the wait up to 30 minutes");
                }
                if (n > 0) {
                    assertEquals(wall.get(), current.written);
                }
                assertEquals(n >= 12, current.flagged, n == 0 ? "[the first failure is recorded with a 30 s wait]"
                        : "[twelve failed retries flag the record]");
                if (n == 0) {
                    System.out.println("Observed: the first failure is recorded with a 30 s wait");
                }
                if (n == 14) {
                    System.out.println("Observed: twelve failed retries flag the record");
                }
                assertEquals(n >= 12 ? 1L : 0L, health.snapshot().get("failStatesFlagged"),
                        "[a flag counts in health and logs one error]");
                assertEquals(n >= 12 ? 1L : 0L, messages.stream().filter(m -> m.getLevel().intValue() >= 1000
                        && m.getMessage().contains("b.csv") && m.getMessage().contains(endpoint)
                                && m.getMessage().contains(" failed at step ")).count(),
                        "[a flag counts in health and logs one error]");
                if (n == 12) {
                    assertEquals(12690000, current.written - start, "[the flag comes 12690 s after the first failure]");
                    System.out.println("Observed: the flag comes 12690 s after the first failure");
                }
                byte[] old = Files.readAllBytes(marker);
                wall.set(current.due - 1);
                HeldLock early = acquire(null);
                assertEquals(SKIP, store.beginFile(io, early, uri).decision, "[a file is retried only when due]");
                early.releaseChecked(io);
                assertArrayEquals(old, Files.readAllBytes(marker), "[a file is retried only when due]");
                wall.incrementAndGet();
                HeldLock due = acquire(null);
                FailStateStore.BeginResult entry = store.beginFile(io, due, uri);
                assertEquals(RETRY, entry.decision, n == 12 || n == 13 ? "[a flagged file is still retried when due]"
                        : "[a file is retried only when due]");
                if (n == 13) {
                    System.out.println("Observed: a flagged file is still retried when due");
                }
                if (n == 14) {
                    System.out.println("Observed: a file is retried only when due");
                    store.retrySucceeded(io, entry.state);
                    assertTrue(Files.exists(dir.resolve("b.csv.lock")));
                    due.releaseChecked(io);
                } else {
                    if (n == 11) {
                        assertEquals(PENDING, store.retryFailed(null, due, entry.state, FailState.Action.DELETE, null, refused));
                        assertEquals(1L, health.snapshot().get("failStatesFlagged"),
                                "[a flag counts in health and logs one error]");
                        assertEquals(11, record().attempts);
                        assertEquals(1L, messages.stream().filter(m -> m.getLevel().intValue() >= 1000
                                && m.getMessage().contains("b.csv") && m.getMessage().contains(endpoint)
                                && m.getMessage().contains(" failed at step ")).count(),
                                "[a flag counts in health and logs one error]");
                        wall.addAndGet(30000);
                        assertEquals(1, store.runPending(io).written);
                        wall.addAndGet(-30000);
                    } else {
                        assertEquals(WRITTEN, store.retryFailed(io, due, entry.state, FailState.Action.DELETE, null, refused));
                    }
                    current = record();
                }
            }
            assertEquals(1L, messages.stream().filter(m -> m.getLevel().intValue() >= 1000
                    && m.getMessage().contains("b.csv") && m.getMessage().contains(endpoint)
                                && m.getMessage().contains(" failed at step ")).count(),
                    "[a flag counts in health and logs one error]");
            System.out.println("Observed: a flag counts in health and logs one error");
            assertEquals(0L, health.snapshot().get("failStatesFlagged"));
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void bytesAndChecksumBindTheRecordToItsOwnFile() throws Exception {
        byte[] record = startFailure().bytes();
        for (int length = 1; length < record.length; length++) {
            byte[] cut = Arrays.copyOf(record, length);
            assertNull(FailState.parse(cut, uri, "b.csv"));
            Files.write(marker, cut);
            HeldLock held = acquire(null);
            assertEquals(SKIP, store.beginFile(io, held, uri).decision, "cut " + length);
            held.releaseChecked(io);
            assertArrayEquals(cut, Files.readAllBytes(marker));
        }
        byte[] corrupt = record.clone();
        corrupt[25] ^= 1;
        assertNull(FailState.parse(corrupt, uri, "b.csv"));
        assertNull(FailState.parse(record, uri, "other"));
        Files.write(marker, record);
        use("literal %20 #[1].csv");
        Files.write(data, new byte[] {1});
        FailState escaped = startFailure();
        assertEquals(data.getFileName().toString(), escaped.name);
        assertTrue(new String(escaped.bytes(), StandardCharsets.UTF_8).contains("%2520"));
        assertEquals(0, stages().size());
    }

    @Test
    void legacyAndEmptyMarkersConvertOnceAndNeverReplay() throws Exception {
        for (String old : Arrays.asList("123", "1731400000000:SUCCESS", "1731400000000:FAILURE", "1731400000000:UNKNOWN",
                "1731400000000:SUCCESS:" + Files.getLastModifiedTime(data).toMillis() + ":1024", "")) {
            Files.write(marker, old.getBytes(StandardCharsets.US_ASCII));
            HeldLock held = acquire(null);
            FailStateStore.BeginResult read = store.beginFile(io, held, uri);
            assertEquals(SKIP, read.decision);
            assertFalse(read.handleTransferred);
            held.releaseChecked(io);
            if (old.isEmpty()) {
                monotonic.addAndGet(TimeUnit.SECONDS.toNanos(60) - 1);
                HeldLock early = acquire(null);
                assertEquals(SKIP, store.beginFile(io, early, uri).decision);
                assertEquals(0, Files.size(marker));
                early.releaseChecked(io);
                monotonic.incrementAndGet();
                HeldLock aged = acquire(null);
                assertEquals(SKIP, store.beginFile(io, aged, uri).decision);
                aged.releaseChecked(io);
            }
            FailState legacy = record();
            assertTrue(legacy.legacy);
            assertEquals(old.contains("SUCCESS") ? FailState.Outcome.SUCCESS : FailState.Outcome.FAILURE, legacy.outcome);
            assertEquals(FailState.Action.NONE, legacy.next);
            assertNull(legacy.identity.digest());
            byte[] before = Files.readAllBytes(marker);
            wall.set(legacy.due + 1);
            held = acquire(null);
            assertEquals(SKIP, fresh().beginFile(io, held, uri).decision);
            held.releaseChecked(io);
            assertArrayEquals(before, Files.readAllBytes(marker));
            assertEquals(1024, Files.size(data));
        }
        Files.write(marker, "1731400000000:UNKNOWN:1:8".getBytes(StandardCharsets.US_ASCII));
        HeldLock changed = acquire(null);
        assertEquals(PROCESS, store.beginFile(io, changed, uri).decision);
        changed.releaseChecked(io);
        assertFalse(Files.exists(marker));
        for (String text : Arrays.asList("1731400000000:MOVE", "1:SUCCESS", "123456789012:FAILURE:1:8")) {
            byte[] foreign = text.getBytes(StandardCharsets.US_ASCII);
            Files.write(marker, foreign);
            HeldLock guarded = acquire(null);
            FailStateStore.BeginResult decision = store.beginFile(io, guarded, uri);
            assertEquals(SKIP, decision.decision);
            assertFalse(decision.handleTransferred);
            guarded.releaseChecked(io);
            assertArrayEquals(foreign, Files.readAllBytes(marker));
            assertTrue(stages().isEmpty());
        }
    }

    @Test
    void pendingHoldsUnjudgedEmptyAndForeignCanonical() throws Exception {
        HeldLock held = acquire(null);
        FileIdentity identity = store.beginFile(io, held, uri).identity;
        Files.createFile(marker);
        assertEquals(PENDING, store.recordFailure(io, held, identity, FailState.Outcome.FAILURE,
                FailState.Action.DELETE, null, refused));
        assertEquals(0, Files.size(marker));
        assertEquals(LockOutcome.Kind.BUSY_LOCAL, locks.acquire(io, uri, ShareLock.Mode.LISTENER, null).kind);
        byte[] foreign = "vfsfail=2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(marker, foreign);
        wall.addAndGet(30000);
        assertEquals(1, store.runPending(io).pending);
        assertArrayEquals(foreign, Files.readAllBytes(marker));
        Files.delete(marker);
        wall.addAndGet(60000);
        assertEquals(1, store.runPending(io).written);
        released(held);
    }

    final class SwapIo extends LockIo {
        int refusedSwaps;
        int stores;
        int reads;
        int swaps;
        boolean fallback;
        boolean lost;
        boolean denyCleanup;
        int cut = -1;

        SwapIo() {
            super(manager, null);
        }

        @Override
        public FileObject resolve(String ref) throws FileSystemException {
            FileObject file = super.resolve(ref);
            if (!file.getName().getBaseName().endsWith(".next")) {
                return file;
            }
            LocalFileName name = (LocalFileName) file.getName();
            return new LocalFile((LocalFileSystem) file.getFileSystem(), name.getRootFile(), name) {
                @Override protected OutputStream doGetOutputStream(boolean append) throws java.io.IOException {
                    stores++;
                    OutputStream target = super.doGetOutputStream(append);
                    if (cut < 0) {
                        return target;
                    }
                    int limit = cut;
                    return new java.io.FilterOutputStream(target) {
                        int sent;
                        @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                            int part = Math.min(limit - sent, length);
                            out.write(bytes, offset, part);
                            sent += part;
                            if (sent == limit) {
                                out.flush();
                                throw fault(Fault.TRANSPORT);
                            }
                        }
                        @Override public void write(int value) throws java.io.IOException {
                            write(new byte[] {(byte) value}, 0, 1);
                        }
                    };
                }
                @Override protected InputStream doGetInputStream(int buffer) throws Exception {
                    reads++;
                    return super.doGetInputStream(buffer);
                }
                @Override public AtomicResult renameReplacing(FileObject target, FileObject probeA, FileObject probeB)
                        throws FileSystemException {
                    swaps++;
                    if (fallback) {
                        return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
                    }
                    if (refusedSwaps-- > 0) {
                        return new AtomicResult(AtomicOutcome.REFUSED, null);
                    }
                    AtomicResult result = super.renameReplacing(target, probeA, probeB);
                    if (lost) {
                        lost = false;
                        throw fault(Fault.TRANSPORT);
                    }
                    return result;
                }
                @Override public AtomicResult takeAway(FileObject target) throws FileSystemException {
                    if (refusedSwaps-- > 0) {
                        return new AtomicResult(AtomicOutcome.REFUSED, null);
                    }
                    return super.takeAway(target);
                }
                @Override public boolean existsNow() throws FileSystemException {
                    if (denyCleanup) {
                        throw fault(Fault.BUSY);
                    }
                    return super.existsNow();
                }
            };
        }
    }

    static FileSystemException fault(Fault kind) {
        FileSystemException failure = new FileSystemException("Test fault.");
        failure.setFault(kind);
        return failure;
    }

    @Test
    void everyCutOfFirstAndUpdateRetainsThePreviousCanonicalUntilRecovery() throws Exception {
        params.retryMillis = 0;
        for (boolean update : new boolean[] {false, true}) {
            int length = Integer.MAX_VALUE;
            for (int offset = 0; offset <= length; offset++) {
                Files.deleteIfExists(marker);
                FailState old = update ? startFailure() : null;
                byte[] before = update ? Files.readAllBytes(marker) : null;
                HeldLock held = acquire(null);
                FailStateStore.BeginResult input = store.beginFile(io, held, uri);
                SwapIo cut = new SwapIo();
                cut.cut = offset;
                FailStateStore.Write answer = update
                        ? store.retryFailed(cut, held, input.state, FailState.Action.DELETE, null, refused)
                        : store.recordFailure(cut, held, input.identity, FailState.Outcome.SUCCESS, FailState.Action.DELETE,
                                null, refused);
                assertEquals(PENDING, answer, "offset " + offset);
                assertEquals(update, Files.exists(marker));
                if (update) {
                    assertArrayEquals(before, Files.readAllBytes(marker));
                }
                Path stage = stages().get(0);
                assertEquals(offset, Files.size(stage));
                assertEquals(1, store.runPending(io).written);
                byte[] after = Files.readAllBytes(marker);
                length = after.length;
                assertNotNull(FailState.parse(after, uri, "b.csv"));
                assertTrue(stages().isEmpty());
                released(held);
                if (old != null) {
                    assertEquals(old.written + 1, record().written);
                }
            }
        }
    }

    @Test
    void oneThousandRefusedSwapsReuseTheCheckedStageAndReleaseOnce() throws Exception {
        SwapIo controlled = new SwapIo();
        controlled.refusedSwaps = 1000;
        HeldLock held = acquire(null);
        FileIdentity id = store.beginFile(io, held, uri).identity;
        assertEquals(PENDING, store.recordFailure(controlled, held, id, FailState.Outcome.FAILURE,
                FailState.Action.DELETE, null, refused));
        Path stage = stages().get(0);
        byte[] bytes = Files.readAllBytes(stage);
        for (int n = 1; n <= 1000; n++) {
            assertEquals(n, controlled.reads);
            assertEquals(n, controlled.swaps);
            assertEquals(1, controlled.stores);
            assertEquals(Collections.singletonList(stage), stages());
            assertTrue(TempNames.inUse(stage.getFileName().toString()));
            assertArrayEquals(bytes, Files.readAllBytes(stage));
            assertEquals(LockOutcome.Kind.BUSY_LOCAL, locks.acquire(io, uri, ShareLock.Mode.LISTENER, null).kind);
            wall.addAndGet(1800000);
            assertEquals(n == 1000 ? 0 : 1, store.runPending(controlled).pending);
        }
        assertFalse(TempNames.inUse(stage.getFileName().toString()));
        assertEquals(1001, controlled.reads);
        assertEquals(1, controlled.stores);
        assertArrayEquals(bytes, Files.readAllBytes(marker));
        assertTrue(stages().isEmpty());
        released(held);
    }

    @Test
    void fallbackGapKeepsLegacyHandleAndLostReplyUsesCanonical() throws Exception {
        Files.write(marker, "1731400000000:SUCCESS".getBytes(StandardCharsets.US_ASCII));
        SwapIo controlled = new SwapIo();
        controlled.refusedSwaps = 1;
        controlled.fallback = true;
        HeldLock held = acquire(null);
        FailStateStore.BeginResult converted = store.beginFile(controlled, held, uri);
        assertEquals(SKIP, converted.decision);
        assertTrue(converted.handleTransferred);
        assertFalse(Files.exists(marker));
        assertEquals(1, stages().size());
        assertEquals(LockOutcome.Kind.BUSY_LOCAL, locks.acquire(io, uri, ShareLock.Mode.LISTENER, null).kind);
        wall.addAndGet(30000);
        assertEquals(1, store.runPending(io).written);
        released(held);
        assertTrue(record().legacy);
        Files.delete(marker);
        controlled.fallback = false;
        controlled.lost = true;
        held = acquire(null);
        FileIdentity identity = store.beginFile(io, held, uri).identity;
        assertEquals(PENDING, store.recordFailure(controlled, held, identity, FailState.Outcome.SUCCESS,
                FailState.Action.DELETE, null, refused));
        byte[] durable = Files.readAllBytes(marker);
        assertTrue(stages().isEmpty());
        int reads = controlled.reads;
        int writes = controlled.stores;
        wall.addAndGet(30000);
        controlled.denyCleanup = true;
        assertEquals(1, store.runPending(controlled).written);
        assertArrayEquals(durable, Files.readAllBytes(marker));
        assertEquals(reads, controlled.reads);
        assertEquals(writes, controlled.stores);
        released(held);
    }

    @Test
    void sameLengthAndTimeCannotProveACommitWithoutTheDigest() throws Exception {
        FailState current = startFailure();
        Path target = dir.resolve("target");
        Files.write(target, Files.readAllBytes(data));
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(data));
        String targetUri = target.toUri().toString();
        assertEquals(FailStateStore.Match.MATCH, store.compareTarget(io, current.identity, targetUri).match);
        byte[] other = Files.readAllBytes(data);
        other[0]++;
        Files.write(target, other);
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(data));
        assertEquals(FailStateStore.Match.DIFFERENT, store.compareTarget(io, current.identity, targetUri).match);
        FileIdentity noDigest = new FileIdentity(uri, current.identity.size, current.identity.mtime,
                current.identity.mtimeSource, null);
        assertEquals(FailStateStore.Match.MATCH_INFERRED, store.compareTarget(io, noDigest, targetUri).match);
        assertTrue(Files.exists(data));
        Files.delete(target);
        assertEquals(FailStateStore.Match.ABSENT, store.compareTarget(io, noDigest, targetUri).match);
    }


    @Test
    void newestWholeStageCountsWhileCutBytesRemainForTwoSweeps() throws Exception {
        FailState old = startFailure();
        byte[] previous = Files.readAllBytes(marker);
        wall.set(old.due);
        HeldLock owner = acquire(null);
        FailState due = store.beginFile(io, owner, uri).state;
        assertEquals(WRITTEN, store.retryFailed(io, owner, due, due.failed, null, refused));
        byte[] newest = Files.readAllBytes(marker);
        TempName stage = TempNames.begin("b.csv.fail", ".next");
        Files.write(dir.resolve(stage.name()), newest);
        stage.end();
        TempName cut = TempNames.begin("b.csv.fail", ".next");
        Files.write(dir.resolve(cut.name()), Arrays.copyOf(newest, newest.length - 1));
        cut.end();
        Files.write(marker, previous);
        wall.set(System.currentTimeMillis() + 3600000);
        store.sweep(io, listing());
        assertTrue(Files.exists(dir.resolve(cut.name())));
        assertTrue(Files.exists(dir.resolve(stage.name())));
        owner = acquire(listing());
        assertEquals(RETRY, store.beginFile(io, owner, uri).decision);
        owner.releaseChecked(io);
        assertArrayEquals(newest, Files.readAllBytes(marker));
        assertFalse(Files.exists(dir.resolve(stage.name())));
        monotonic.set(TimeUnit.HOURS.toNanos(1) - 1);
        assertEquals(0, store.sweep(io, listing()).removed);
        monotonic.incrementAndGet();
        assertEquals(1, store.sweep(io, listing()).removed);
        assertFalse(Files.exists(dir.resolve(cut.name())));
    }

    @Test
    void pendingUsesDefiniteAbsenceAndOwnerLossAndKeepsHandleAcrossResume() throws Exception {
        HeldLock owner = acquire(null);
        FileIdentity id = store.beginFile(io, owner, uri).identity;
        assertEquals(PENDING, store.recordFailure(null, owner, id, FailState.Outcome.SUCCESS,
                FailState.Action.DELETE, null, refused));
        wall.addAndGet(30000);
        LockIo denial = new LockIo(manager, null) {
            @Override public FileObject resolve(String ref) throws FileSystemException {
                throw fault(Fault.APPLICATION);
            }
        };
        assertEquals(1, store.runPending(denial).pending);
        assertTrue(Files.exists(dir.resolve("b.csv.lock")));
        Files.delete(data);
        wall.addAndGet(60000);
        FailStateStore.PendingResult dropped = store.runPending(io);
        assertEquals(1, dropped.dropped.size());
        assertEquals(0, dropped.pending);
        released(owner);
        Files.write(data, new byte[8]);
        owner = acquire(null);
        id = store.beginFile(io, owner, uri).identity;
        assertEquals(PENDING, store.recordFailure(null, owner, id, FailState.Outcome.FAILURE,
                FailState.Action.DELETE, null, refused));
        Files.delete(dir.resolve("b.csv.lock"));
        wall.addAndGet(30000);
        FailStateStore.PendingResult taken = store.runPending(io);
        assertEquals(0, taken.pending);
        assertEquals(0, taken.written);
        assertFalse(Files.exists(marker));
        assertSame(FailStateStore.forEndpoint(endpoint, params, locks, health),
                FailStateStore.forEndpoint(endpoint, params, locks, health));
    }

    @Test
    void copyFailureBeforeOpeningTempCanBeRecordedAndReleased() throws Exception {
        HeldLock held = acquire(null);
        FileIdentity id = store.beginFile(io, held, uri).identity;
        try (FileObject source = io.resolve(uri); FileObject dest = io.resolve(dir.resolve("out").toUri().toString());
                FileObject temp = io.resolve(dir.resolve("out.part").toUri().toString())) {
            source.setIsMounted(true);
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(data);
            try {
                Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
                org.wso2.org.apache.commons.vfs2.provider.MoveOptions options =
                        new org.wso2.org.apache.commons.vfs2.provider.MoveOptions(true, temp, null, null, null);
                org.wso2.org.apache.commons.vfs2.provider.MoveFailedException error = assertThrows(
                        org.wso2.org.apache.commons.vfs2.provider.MoveFailedException.class,
                        () -> org.wso2.org.apache.commons.vfs2.util.FileObjectUtils.getAbstractFileObject(source)
                                .moveTo(dest, options));
                ActionResult action = ActionResult.of(error, null);
                assertEquals(ActionResult.Step.COPY, action.step);
                assertNull(action.temp);
                assertEquals(WRITTEN, store.recordFailure(io, held, id, FailState.Outcome.SUCCESS,
                        FailState.Action.MOVE, "out", action));
                assertEquals(ActionResult.Step.COPY, record().action.step);
                assertNull(record().action.temp);
                assertEquals(0, store.runPending(io).pending);
                released(held);
            } finally {
                Files.setPosixFilePermissions(data, permissions);
            }
        }
    }

    @Test
    void sweepHandsBackOnlyAnUnclearRenameAfterDefiniteAbsence() throws Exception {
        HeldLock owner = acquire(null);
        FileIdentity id = store.beginFile(io, owner, uri).identity;
        ActionResult rename = new ActionResult(ActionResult.Step.RENAME, false, null, false, null);
        assertEquals(WRITTEN, store.recordFailure(io, owner, id, FailState.Outcome.SUCCESS,
                FailState.Action.MOVE, "done/b.csv", rename));
        TempName stage = TempNames.begin("b.csv.fail", ".next");
        Files.move(marker, dir.resolve(stage.name()));
        stage.end();
        assertEquals(0, store.sweep(io, listing()).removed);
        Files.delete(data);
        FailStateStore.SweepResult swept = store.checkAbsentSingleFile(io, uri, listing());
        assertEquals(1, swept.removed);
        assertEquals(1, swept.records.size());
        assertEquals("done/b.csv", swept.records.get(0).failedTarget);
        assertFalse(Files.exists(dir.resolve(stage.name())));
    }

    @Test
    void legacyLookupFailureLeavesCallerInChargeAndLostConversionReplyTransfers() throws Exception {
        Files.write(marker, "123".getBytes(StandardCharsets.US_ASCII));
        HeldLock held = acquire(null);
        LockIo blocked = new LockIo(manager, null) {
            @Override public FileObject resolve(String ref) throws FileSystemException {
                if (ref.endsWith(".fail")) {
                    throw fault(Fault.APPLICATION);
                }
                return super.resolve(ref);
            }
        };
        FailStateStore.BeginResult denied = store.beginFile(blocked, held, uri);
        assertEquals(SKIP, denied.decision);
        assertFalse(denied.handleTransferred);
        assertEquals(LockOutcome.Kind.OURS, held.verifyOwner(io).kind);
        SwapIo lostReply = new SwapIo();
        lostReply.lost = true;
        FailStateStore.BeginResult pending = store.beginFile(lostReply, held, uri);
        assertEquals(TRANSPORT_ERROR, pending.decision);
        assertTrue(pending.handleTransferred);
        byte[] canonical = Files.readAllBytes(marker);
        assertTrue(record().legacy);
        wall.addAndGet(30000);
        assertEquals(1, store.runPending(io).written);
        assertArrayEquals(canonical, Files.readAllBytes(marker));
        released(held);
        HeldLock reader = acquire(listing());
        assertEquals(SKIP, store.beginFile(io, reader, uri).decision);
        reader.releaseChecked(io);
        assertEquals(FailStateStore.Peek.LOOK, store.peek(uri, Files.size(marker),
                Files.getLastModifiedTime(marker).toMillis(), false));
        Files.write(data, new byte[] {4});
        reader = acquire(null);
        assertEquals(PROCESS, store.beginFile(io, reader, uri).decision);
        reader.releaseChecked(io);
        assertFalse(Files.exists(marker));
    }

    @Test
    void recoveredStageIsRemovedBeforeCanonicalAndRelease() throws Exception {
        FailState current = startFailure();
        TempName stage = TempNames.begin("b.csv.fail", ".next");
        Files.copy(marker, dir.resolve(stage.name()));
        stage.end();
        SwapIo refusedSwap = new SwapIo();
        refusedSwap.refusedSwaps = 1;
        wall.set(current.due);
        HeldLock owner = acquire(listing());
        FailStateStore.BeginResult found = store.beginFile(refusedSwap, owner, uri);
        assertEquals(RETRY, found.decision);
        assertTrue(Files.exists(dir.resolve(stage.name())));
        store.retrySucceeded(io, found.state);
        assertFalse(Files.exists(dir.resolve(stage.name())));
        assertFalse(Files.exists(marker));
        assertTrue(Files.exists(dir.resolve("b.csv.lock")));
        owner.releaseChecked(io);
    }

    @Test
    void digestNeedsEofMatchingCountAndReportsProgressPerRead() throws Exception {
        FileIdentity id = new FileIdentity(uri, 3, 1, FileIdentity.TimeSource.COMMAND, null);
        List<Long> progress = new ArrayList<>();
        try (DigestingInputStream input = store.digesting(new ByteArrayInputStream(new byte[3]), id, progress::add)) {
            assertEquals(0, input.read());
            assertEquals(2, input.read(new byte[4]));
            assertNull(id.digest());
            assertEquals(-1, input.read());
            assertNotNull(id.digest());
            assertFalse(input.markSupported());
            assertThrows(java.io.IOException.class, input::reset);
        }
        assertEquals(Arrays.asList(1L, 2L), progress);
        try (DigestingInputStream input = store.digesting(new ByteArrayInputStream(new byte[2]), id, null)) {
            while (input.read() >= 0) { }
            assertNull(id.digest());
        }
        assertEquals(1024, FileDigest.of(io, uri).count);
        Files.delete(data);
        assertEquals(DigestResult.Kind.GONE, FileDigest.of(io, uri).kind);
    }
}
