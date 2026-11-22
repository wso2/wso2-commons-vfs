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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.vfs.shared.write.AtomicWriter;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.health.HealthRegistry;

class ShareLockTest {
    private final String data = "file:///tmp/" + UUID.randomUUID();
    private final String lock = data + ".lock";
    private final ShareLock owner = locks(true, 900000);
    private final Server server = new Server();
    @TempDir Path folder;

    private ShareLock locks(boolean auto, long age) {
        String name = UUID.randomUUID().toString();
        return ShareLock.create(name, auto, age, HealthRegistry.register(HealthRegistry.Kind.INBOUND, name, 5000));
    }

    static FileSystemException failure(Fault fault) {
        FileSystemException error = new FileSystemException("Fixture failure");
        error.setFault(fault);
        return error;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static class Server extends LockIo {
        final Map<String, byte[]> files = new HashMap<>();
        boolean weak;
        int deletes;
        int creates;
        int calls;

        Server() { super(null, null); }
        @Override Answer exclusive(String uri) { return weak ? Answer.NO : Answer.YES; }
        @Override boolean exists(String uri) throws IOException { calls++; return files.containsKey(uri); }
        @Override LockValue read(String uri) throws IOException {
            calls++;
            byte[] value = files.get(uri);
            if (value == null) { throw failure(Fault.MISSING); }
            return LockValue.parse(Arrays.copyOf(value, Math.min(value.length, 1025)));
        }
        @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) throws IOException {
            calls++;
            creates++;
            if (weak) { return new AtomicResult(AtomicOutcome.UNSUPPORTED,null); }
            return new AtomicResult(files.putIfAbsent(uri + ".lock", value.bytes) == null ? AtomicOutcome.DONE : AtomicOutcome.EXISTS,null);
        }
        @Override void write(String uri, byte[] value) throws IOException { calls++; files.put(uri, value); }
        @Override void delete(String uri) throws IOException { calls++; deletes++; files.remove(uri); }
        @Override AtomicOutcome take(String from, String to) throws IOException {
            calls++;
            byte[] value = files.remove(from);
            if (value == null) { return AtomicOutcome.GONE; }
            files.put(to, value);
            return AtomicOutcome.DONE;
        }
    }

    private LockOutcome acquire(LockIo io) {
        return owner.acquire(io, data, ShareLock.Mode.WRITER, null);
    }

    @Test
    void ageDecisionsAtEveryLockLength() {
        long now = 1800000000000L;
        org.wso2.vfs.shared.health.EndpointHealth health =
                HealthRegistry.register(HealthRegistry.Kind.INBOUND, data, 5000);
        ShareLock clocked = new ShareLock(data, true, 900000, health, health.namedLog(), () -> now, System::nanoTime);
        String id = UUID.randomUUID().toString();
        int width = 110;
        for (String format : Arrays.asList("", ":v2:" + id)) {
            for (int delta = -20; delta <= 20; delta++) {
                for (int seconds : new int[]{899,901}) {
                    String tail = ":10.0.0.9:" + (now - seconds * 1000L) + format;
                    String host = String.join("", Collections.nCopies(width + delta - tail.length() - 2, "h"));
                    String text = "1:" + host + tail;
                    assertEquals(width + delta, bytes(text).length);
                    assertEquals(seconds == 901, clocked.releasable(data, LockValue.parse(bytes(text))));
                }
            }
        }
    }

    @Test
    void guardPreservesYoungMalformedAndCutWriterLocks() {
        for (String text : Arrays.asList("", "1:host:x:1:v2:", "1:host:x:1:v2:" + PathRegistry.INSTANCE.substring(1),
                "foreign", "-7:host:addr:0", "host\naddress\npid")) {
            server.files.put(lock, bytes(text));
            assertEquals(LOCKED, owner.isLockedByOther(server, data).kind);
            assertEquals(0, server.deletes);
        }
    }

    @Test
    void genuineOwnerIsIndexedBeforeRemoteCreateAndAfterHandover() {
        Server checked = new Server() {
            @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) throws IOException {
                assertTrue(PathRegistry.held(value));
                assertEquals(LOCK_BUSY, owner.enterLocalOnly(uri, 1, 0).kind);
                return super.create(uri, value, beforeLink);
            }
        };
        HeldLock held = acquire(checked).handle;
        assertNotNull(held);
        held.pendingStateWrite();
        assertTrue(held.entry.pending);
        String alias = data + "alias";
        checked.files.put(alias + ".lock", checked.files.get(lock));
        assertEquals(LOCKED, owner.isLockedByOther(checked, alias).kind);
        assertEquals(LEFT, held.abandon().kind);
        assertEquals(FREE, owner.isLockedByOther(checked, alias).kind);
        assertFalse(checked.files.containsKey(alias + ".lock"));
        HeldLock replacement = acquire(checked).handle;
        assertTrue(replacement.tookOver());
        assertEquals(ENDED, held.abandon().kind);
        assertEquals(OURS, replacement.verifyOwner(checked).kind);
        assertEquals(RELEASED, replacement.releaseChecked(checked).kind);
    }

    @Test
    void releaseAndAbandonRaceOnceAndLateLoserLeavesReplacement() throws Exception {
        HeldLock held = acquire(server).handle;
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<LockOutcome> a = threads.submit(() -> { start.await(); return held.releaseChecked(server); });
            Future<LockOutcome> b = threads.submit(() -> { start.await(); return held.abandon(); });
            start.countDown();
            assertTrue((a.get().kind == ENDED) ^ (b.get().kind == ENDED));
            HeldLock next = acquire(server).handle;
            int calls = server.calls;
            assertEquals(ENDED, held.releaseChecked(server).kind);
            assertEquals(ENDED, held.verifyOwner(server).kind);
            assertEquals(ENDED, held.lockShare(server, 1, 0).kind);
            assertEquals(ENDED, held.unlockShare(server).kind);
            assertEquals(calls, server.calls);
            assertEquals(RELEASED, next.releaseChecked(server).kind);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void lostOwnerEndsItselfAndDoesNotTouchTheReplacement() {
        HeldLock old = acquire(server).handle;
        byte[] foreign = bytes("18:host:ip:" + System.currentTimeMillis());
        server.files.put(lock, foreign);
        assertEquals(TAKEN_OVER, old.verifyOwner(server).kind);
        int calls = server.calls;
        assertEquals(ENDED, old.abandon().kind);
        assertEquals(ENDED, old.releaseAfterAction(server, null).kind);
        assertEquals(calls, server.calls);
        assertArrayEquals(foreign, server.files.get(lock));
        assertEquals(LEFT, owner.enterLocalOnly(data, 1, 0).handle.abandon().kind);
    }

    @Test
    void missingReadNeedsADefiniteFreshLook() {
        HeldLock held = acquire(server).handle;
        Server denied = new Server() {
            @Override boolean exists(String uri) throws IOException { throw failure(Fault.APPLICATION); }
        };
        assertEquals(CHECK_FAILED, held.verifyOwner(denied).kind);
        assertEquals(LOCK_BUSY, owner.enterLocalOnly(data, 1, 0).kind);
        assertEquals(TAKEN_OVER, held.verifyOwner(new Server()).kind);
        assertEquals(ENDED, held.abandon().kind);
    }

    @Test
    void failedExclusiveWriteKeepsTheProviderCleanupDecision() {
        Server refused = new Server() {
            @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) {
                files.put(lock, new byte[0]);
                files.remove(lock);
                return new AtomicResult(AtomicOutcome.REFUSED,null);
            }
        };
        assertEquals(ERROR, acquire(refused).kind);
        assertTrue(refused.files.isEmpty());
        assertEquals(0, refused.deletes);
        assertEquals(LEFT, owner.enterLocalOnly(data, 1, 0).handle.abandon().kind);
    }

    @Test
    void failedWeakWriteNeverDeletesAndReturnsApplicationFailure() {
        Server refused = new Server() {
            @Override void write(String uri, byte[] value) throws IOException {
                files.put(uri, new byte[0]);
                throw failure(Fault.APPLICATION);
            }
        };
        refused.weak = true;
        LockOutcome error = acquire(refused);
        assertEquals(ERROR, error.kind);
        assertEquals(ErrorKind.APPLICATION, error.classification.getKind());
        assertEquals(0, refused.deletes);
        refused.files.clear();
        HeldLock local = owner.enterLocalOnly(data, 1, 0).handle;
        assertEquals(ERROR, local.lockShare(refused, 1, 0).kind);
        assertEquals(LOCK_BUSY, owner.enterLocalOnly(data, 1, 0).kind);
        assertEquals(RELEASED, local.releaseChecked(null).kind);
    }

    @Test
    void lostCreateReplyStopsTheConnectionAndClearsTheEntry() {
        AtomicInteger changes = new AtomicInteger();
        Server cut = new Server() {
            @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) throws IOException {
                changes.incrementAndGet();
                throw new org.wso2.org.apache.commons.vfs2.provider.UnclearResultException("lost", null,
                        org.wso2.org.apache.commons.vfs2.provider.MovePhase.COMMIT, null, false);
            }
            @Override LockValue read(String uri) { throw new AssertionError("Reused failed connection"); }
            @Override void delete(String uri) { throw new AssertionError("Reused failed connection"); }
        };
        LockOutcome got = acquire(cut);
        assertEquals(TRANSPORT_ERROR, got.kind);
        assertEquals(ErrorKind.UNCLEAR, got.classification.getKind());
        assertEquals(1, changes.get());
        assertEquals(LEFT, owner.enterLocalOnly(data, 1, 0).handle.abandon().kind);
    }

    @Test
    void writerReturnsConfirmedBusyAndLeavesTheFrameInChargeOfItsHandle() throws Exception {
        AtomicResult wire = new AtomicResult(AtomicOutcome.EXISTS, "confirmed", Arrays.asList("u.lock", "p.lock"), true);
        Server lost = new Server() {
            @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) {
                assertEquals(0, creates++);
                return wire;
            }
        };
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("file", new DefaultLocalFileProvider());
            manager.init();
            String uri = folder.resolve("output").toUri().toString();
            LockIo live = new LockIo(manager, null);
            AtomicWriter writer = new AtomicWriter(data, HealthRegistry.register(HealthRegistry.Kind.CONNECTION, data, 0),
                    owner, false, ".part", ".part");
            try (AtomicWriter.Temp temp = writer.prepare(live, uri)) {
                writer.transfer(live, temp, output -> output.write(1));
                HeldLock held = owner.enterLocalOnly(uri, 1, 0).handle;
                AtomicWriter.Result result = writer.commit(lost, held, temp, CommitStep.Mode.OVERWRITE,
                        true, 3, 0, true, () -> fail("Delete on a lost connection"));
                assertEquals(AtomicWriter.Outcome.LOCK_BUSY, result.outcome);
                assertSame(wire, result.lock.atomic);
                assertTrue(result.connectionLost());
                assertEquals(1, lost.creates);
                assertEquals(0, lost.deletes);
                assertTrue(Files.exists(folder.resolve(temp.name.name())));
                assertEquals(LOCK_BUSY, owner.enterLocalOnly(uri, 1, 0).kind);
                assertEquals(LEFT, held.abandon().kind);
            } finally {
                HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION, data);
            }
        }
    }

    @Test
    void confirmedContentionOnLostConnectionKeepsTheDecisionAndBothHelperNames() {
        for (String path : Arrays.asList("single","wait","separate","unverified")) {
            AtomicOutcome outcome = path.equals("unverified") ? AtomicOutcome.DONE : AtomicOutcome.EXISTS;
            AtomicResult wire = new AtomicResult(outcome, "confirmed", Arrays.asList("u.lock","p.lock"),true);
            Server lost = new Server() {
                @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) {
                    assertEquals(0,creates++);
                    return wire;
                }
                @Override LockValue read(String uri) { throw new AssertionError("Read on dead connection"); }
                @Override void delete(String uri) { throw new AssertionError("Delete on dead connection"); }
            };
            HeldLock held = null;
            LockOutcome result;
            if (path.equals("separate")) {
                held = owner.enterLocalOnly(data,1,0).handle;
                result = held.lockShare(lost,3,0);
            } else if (path.equals("wait")) {
                result = owner.acquireWithWait(lost,data,ShareLock.Mode.WRITER,3,0);
            } else {
                result = acquire(lost);
            }
            assertEquals(path.equals("single") ? BUSY_SHARE : path.equals("unverified") ? TRANSPORT_ERROR : LOCK_BUSY,
                    result.kind);
            assertSame(wire,result.atomic);
            assertTrue(result.connectionLost());
            assertEquals(Arrays.asList("u.lock","p.lock"),result.atomic.getLeftovers());
            assertEquals(ErrorKind.TRANSPORT,result.classification.getKind());
            assertEquals(1,lost.creates);
            if (held != null) {
                assertEquals(LOCK_BUSY,owner.enterLocalOnly(data,1,0).kind);
                assertEquals(RELEASED,held.releaseChecked(null).kind);
            }
            assertEquals(LEFT,owner.enterLocalOnly(data,1,0).handle.abandon().kind);
        }
    }

    @Test
    void healthyContentionKeepsOneLocalEntryAcrossTheRetryBudget() {
        Server busy = new Server() {
            LockValue first;
            @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) {
                assertTrue(PathRegistry.held(value));
                assertEquals(LOCK_BUSY,owner.enterLocalOnly(uri,1,0).kind);
                if (creates++ == 0) {
                    first = value;
                } else {
                    assertSame(first,value);
                }
                return new AtomicResult(AtomicOutcome.EXISTS,null);
            }
        };
        assertEquals(LOCK_BUSY,owner.acquireWithWait(busy,data,ShareLock.Mode.WRITER,3,1).kind);
        assertEquals(3,busy.creates);
        assertEquals(LEFT,owner.enterLocalOnly(data,1,0).handle.abandon().kind);
    }

    @Test
    void oversizedChangedTakeoverRestoresAllBytesOnce() {
        byte[] replacement = new byte[2048];
        replacement[1701] = 42;
        Server changed = new Server() {
            @Override AtomicOutcome take(String from, String to) throws IOException {
                if (from.equals(lock)) { files.put(lock, replacement.clone()); }
                return super.take(from, to);
            }
        };
        changed.files.put(lock, bytes("12:h:ip:1"));
        assertEquals(BUSY_SHARE, acquire(changed).kind);
        assertArrayEquals(replacement, changed.files.get(lock));
        assertEquals(1, changed.files.size());
        assertEquals(0, changed.creates);
    }

    @Test
    void releaseAfterActionRetriesOnceAndChecksTheOwnerOnFreshIo() {
        for (String scenario : Arrays.asList("gone","ours","other","failed","none","slow")) {
            AtomicInteger callbacks = new AtomicInteger();
            Server first = new Server() {
                @Override void delete(String uri) throws IOException {
                    if (scenario.equals("slow")) {
                        try { Thread.sleep(5050); } catch (InterruptedException e) { throw new IOException(e); }
                    }
                    throw failure(Fault.TRANSPORT);
                }
            };
            HeldLock handle = acquire(first).handle;
            Server fresh = new Server() {
                @Override LockValue read(String key) throws IOException {
                    if (scenario.equals("failed")) { throw failure(Fault.TRANSPORT); }
                    return super.read(key);
                }
            };
            if (scenario.equals("ours")) { fresh.files.put(lock, first.files.get(lock)); }
            if (scenario.equals("other")) { fresh.files.put(lock, bytes("123:1")); }
            LockOutcome result = handle.releaseAfterAction(first, () -> {
                assertEquals(LOCK_BUSY, owner.enterLocalOnly(data, 1, 0).kind);
                callbacks.incrementAndGet();
                return scenario.equals("none") ? null : fresh;
            });
            assertEquals(scenario.equals("slow") ? 0 : 1, callbacks.get());
            assertEquals(scenario.equals("other") ? NOT_OURS :
                    scenario.equals("ours") || scenario.equals("gone") ? RELEASED : LEFT, result.kind);
            assertEquals(scenario.equals("ours") ? 1 : 0, fresh.deletes);
            assertEquals(ENDED, handle.abandon().kind);
            assertEquals(LEFT, owner.enterLocalOnly(data, 1, 0).handle.abandon().kind);
        }
    }

    @Test
    void folderWriterUsesOneSiblingAndOneKeyForBothSpellings() throws Exception {
        DefaultFileSystemManager manager = new DefaultFileSystemManager();
        manager.addProvider("file", new DefaultLocalFileProvider());
        manager.init();
        try {
            Path dest = Files.createDirectory(folder.resolve("destination"));
            String slash = dest.toUri().toString();
            String plain = slash.substring(0, slash.length() - 1);
            LockIo io = new LockIo(manager, new FileSystemOptions());
            for (String name : Arrays.asList(slash,plain)) {
                LockOutcome got = owner.acquire(io, name, ShareLock.Mode.WRITER, null);
                assertEquals(ACQUIRED, got.kind);
                assertTrue(Files.isRegularFile(folder.resolve("destination.lock")));
                assertFalse(Files.exists(dest.resolve(".lock")));
                assertEquals(BUSY_LOCAL, owner.acquire(io, name.equals(slash) ? plain : slash,
                        ShareLock.Mode.WRITER, null).kind);
                assertEquals(RELEASED, got.handle.releaseChecked(io).kind);
            }
            String query = "sftp://name@HOST:22/data/folder/?opt=value";
            assertEquals("sftp://name@HOST:22/data/folder?opt=value", PathRegistry.dataUri(query));
            assertEquals("sftp://host:22/data/folder", PathRegistry.key(query));
            assertEquals("sftp://host/", PathRegistry.dataUri("sftp://host/"));
        } finally {
            manager.close();
        }
    }

    @Test
    void literalVfsChildrenUseTheSameSiblingAndRegistryAcrossBothSpellings() throws Exception {
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("file", new DefaultLocalFileProvider());
            manager.init();
            LockIo io = new LockIo(manager, new FileSystemOptions());
            for (String name : new String[]{"plain", "invoice 1", "invoice[1]", "invoice#1", "invoice%1", "invoice%20"}) {
                Path target = folder.resolve(name);
                Files.write(target, new byte[]{1});
                String uri = target.toUri().toString();
                try (org.wso2.org.apache.commons.vfs2.FileObject object = manager.resolveFile(uri)) {
                    String leaf = object.getName().getBaseName();
                    FolderListing listing = FolderListing.of(folder.toUri().toString(),
                            Collections.singletonMap(leaf, new FolderListing.Metadata(1, 0)));
                    assertTrue(listing.lists(object.getName().getURI()), name);
                    HeldLock first = owner.acquire(io, object.getName().getURI(), ShareLock.Mode.LISTENER, listing).handle;
                    assertNotNull(first, name);
                    assertEquals(BUSY_LOCAL, owner.acquire(io, uri, ShareLock.Mode.WRITER, null).kind);
                    first.abandon();
                    Files.delete(target);
                    assertEquals(1, owner.sweepOrphans(io, FolderListing.of(folder.toUri().toString(),
                            Collections.singletonMap(leaf + ".lock", new FolderListing.Metadata(1, 0)))));
                    assertFalse(Files.exists(folder.resolve(name + ".lock")));
                }
            }
            FolderListing remote = FolderListing.of("sftp://u@SERVER/path/?mode=x",
                    Collections.singletonMap("invoice[1]", new FolderListing.Metadata(1, 0)));
            assertEquals("sftp://u@SERVER/path/invoice%5B1%5D?mode=x", remote.child("invoice[1]"));
            assertTrue(remote.lists("sftp://u@server/path/invoice[1]?mode=x"));
            assertFalse(remote.lists("sftp://u@server/other/invoice[1]?mode=x"));
        }
    }
}
