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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

class CrashTableTest {
    private final String folder = "file:///tmp/" + UUID.randomUUID();
    private final String data = folder + "/x";
    private final String lock = data + ".lock";
    private final AtomicLong wall = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong nanos = new AtomicLong(System.nanoTime());
    private final ShareLock owner = new ShareLock(folder, true, 60000,
            HealthRegistry.register(HealthRegistry.Kind.INBOUND, folder, 5000),
            NamedLog.inbound(LogFactory.getLog(CrashTableTest.class), "crash", new RepeatLimiter()),
            wall::get, nanos::get);

    private void advance(long ms) {
        wall.addAndGet(ms);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private LockOutcome acquire(LockIo io) {
        return owner.acquire(io, data, ShareLock.Mode.WRITER, null);
    }

    private int sweep(ShareLockTest.Server server) throws IOException {
        Map<String, FolderListing.Metadata> names = new LinkedHashMap<>();
        for (String path : server.files.keySet()) {
            names.put(path.substring(path.lastIndexOf('/') + 1), new FolderListing.Metadata(0,0));
        }
        return owner.sweepOrphans(server, FolderListing.of(folder, names));
    }

    private static class Cut extends ShareLockTest.Server {
        String stop;
        String changeTo;
        boolean failed;
        boolean linkSent;
        boolean probeSent;
        String data;

        private void cut(String step) throws IOException {
            if (failed) { throw new AssertionError("Used the failed connection at " + step); }
            if (step.equals(stop)) {
                failed = true;
                throw ShareLockTest.failure(Fault.TRANSPORT);
            }
        }

        @Override boolean exists(String name) throws IOException {
            cut("registry");
            return super.exists(name);
        }

        @Override AtomicResult create(String name, LockValue value, Runnable beforeLink) throws IOException {
            data = name;
            if ("beforePutBack".equals(stop)) {
                assertEquals(changeTo, value.text());
                cut("beforePutBack");
            }
            assertTrue(PathRegistry.held(value));
            if ("empty".equals(stop) || "partial".equals(stop)) {
                files.put(name + ".lock", "empty".equals(stop) ? new byte[0]
                        : java.util.Arrays.copyOf(value.bytes, value.bytes.length - 1));
                cut(stop);
            }
            if (stop != null && stop.startsWith("sftp")) {
                files.put(name + ".token.u.lock", value.bytes);
                cut("sftpUnique");
                if (linkSent) { files.put(name + ".lock", value.bytes); }
                if (probeSent) { files.put(name + ".token.p.lock", value.bytes); }
                cut(stop);
            }
            return super.create(name, value, beforeLink);
        }

        @Override LockValue read(String name) throws IOException {
            if (data != null && name.equals(data + ".lock")) { cut("readBack"); }
            if (name.endsWith(".t.lock")) { cut("afterRename"); }
            return super.read(name);
        }

        @Override void write(String name, byte[] bytes) throws IOException {
            super.write(name, bytes);
            cut("weakWrite");
        }

        @Override AtomicOutcome take(String from, String to) throws IOException {
            if (from.equals(data + ".lock") && changeTo != null) {
                files.put(from, changeTo.getBytes(StandardCharsets.UTF_8));
            }
            return super.take(from,to);
        }
    }

    private ShareLockTest.Server recover(Cut cut) {
        assertTrue(cut.failed);
        ShareLockTest.Server next = new ShareLockTest.Server();
        next.files.putAll(cut.files);
        return next;
    }

    @Test
    void afterRegistryEnterLeavesNothing() {
        Cut cut = new Cut();
        cut.stop = "registry";
        assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
        assertTrue(cut.files.isEmpty());
        assertEquals(LEFT, owner.enterLocalOnly(data,1,0).handle.abandon().kind);
    }

    @Test
    void afterExclusiveCreateAnEmptyOrPartialValueWaitsFromFirstSight() {
        for (String state : new String[]{"empty","partial"}) {
            Cut cut = new Cut();
            cut.stop = state;
            assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
            ShareLockTest.Server next = recover(cut);
            assertTrue(next.files.containsKey(lock));
            advance(120000);
            assertEquals(BUSY_SHARE, acquire(next).kind);
            advance(59999);
            assertEquals(BUSY_SHARE, acquire(next).kind);
            advance(2);
            assertEquals(RELEASED, acquire(next).handle.releaseChecked(next).kind);
        }
    }

    @Test
    void completeLockBeforeReadBackIsReleasedByItsCopy() {
        Cut cut = new Cut();
        cut.stop = "readBack";
        assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
        ShareLockTest.Server next = recover(cut);
        assertEquals(PathRegistry.INSTANCE, LockValue.parse(next.files.get(lock)).instance);
        HeldLock held = acquire(next).handle;
        assertTrue(held.tookOver());
        held.releaseChecked(next);
    }

    @Test
    void uniqueBeforeTheSftpLinkIsRecoveredBySweep() throws Exception {
        Cut cut = new Cut();
        cut.stop = "sftpUnique";
        assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
        ShareLockTest.Server next = recover(cut);
        assertEquals(Collections.singleton(data + ".token.u.lock"), next.files.keySet());
        assertEquals(1,sweep(next));
        assertTrue(next.files.isEmpty());
    }

    @Test
    void lostSftpLinkReplyCoversEitherServerOutcome() throws Exception {
        for (boolean sent : new boolean[]{false,true}) {
            Cut cut = new Cut();
            cut.stop = "sftpUnclear";
            cut.linkSent = sent;
            assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
            ShareLockTest.Server next = recover(cut);
            next.files.put(data, new byte[]{1});
            assertEquals(sent, next.files.containsKey(lock));
            assertEquals(1, sweep(next));
            HeldLock held = acquire(next).handle;
            assertEquals(sent, held.tookOver());
            held.releaseChecked(next);
            assertEquals(Collections.singleton(data), next.files.keySet());
        }
    }

    @Test
    void probeInterruptedLeavesOnlyItsPrivateNames() throws Exception {
        for (boolean sent : new boolean[]{false,true}) {
            Cut cut = new Cut();
            cut.stop = "sftpProbe";
            cut.probeSent = sent;
            assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
            ShareLockTest.Server next = recover(cut);
            assertFalse(next.files.containsKey(lock));
            assertEquals(sent, next.files.containsKey(data + ".token.p.lock"));
            assertEquals(sent ? 2 : 1,sweep(next));
            assertTrue(next.files.isEmpty());
        }
    }

    @Test
    void weakWriteBeforeVerificationIsOwnedByThisCopy() {
        Cut cut = new Cut();
        cut.stop = "weakWrite";
        cut.weak = true;
        assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
        ShareLockTest.Server next = recover(cut);
        assertEquals(PathRegistry.INSTANCE, LockValue.parse(next.files.get(lock)).instance);
        assertEquals(FREE,owner.isLockedByOther(next,data).kind);
        assertFalse(next.files.containsKey(lock));
    }

    @Test
    void afterTakeAwayTheStaleFileIsUnderItsPrivateName() throws Exception {
        Cut cut = new Cut();
        cut.data = data;
        cut.stop = "afterRename";
        byte[] stale = "12:h:addr:1".getBytes(StandardCharsets.UTF_8);
        cut.files.put(lock,stale);
        assertEquals(TRANSPORT_ERROR, acquire(cut).kind);
        ShareLockTest.Server next = recover(cut);
        assertFalse(next.files.containsKey(lock));
        assertArrayEquals(stale,next.files.values().iterator().next());
        assertEquals(1,sweep(next));
    }

    @Test
    void interruptedPutBackOfFreshValueIsSeenByItsOwner() throws Exception {
        Cut cut = new Cut();
        HeldLock other = acquire(cut).handle;
        cut.changeTo = other.entry.value.text();
        // The second party judges an old value. The actual rename takes the current owner's value.
        cut.files.put(lock, "12:1".getBytes(StandardCharsets.UTF_8));
        cut.stop = "beforePutBack";
        assertEquals(TRANSPORT_ERROR, owner.isLockedByOther(cut,data).kind);
        ShareLockTest.Server next = recover(cut);
        assertFalse(next.files.containsKey(lock));
        assertArrayEquals(other.entry.value.bytes,next.files.values().iterator().next());
        assertEquals(0,sweep(next));
        assertEquals(TAKEN_OVER,other.verifyOwner(next).kind);
        assertEquals(1,sweep(next));
        assertTrue(next.files.isEmpty());
    }

    @Test
    void crashDuringMediationOrAfterTheOwnerCheckNeedsForeignAge() {
        for (boolean checked : new boolean[]{false,true}) {
            ShareLockTest.Server next = new ShareLockTest.Server();
            next.files.put(data,new byte[]{1});
            HeldLock handle = acquire(next).handle;
            if (checked) { assertEquals(OURS,handle.verifyOwner(next).kind); }
            handle.abandon();
            // A restarted JVM has a different UUID. Use that relationship at the reader.
            next.files.put(lock,LockValue.create(wall.get(),UUID.randomUUID().toString()).bytes);
            assertEquals(BUSY_SHARE,acquire(next).kind);
            advance(60000);
            assertEquals(BUSY_SHARE,acquire(next).kind);
            advance(1);
            assertEquals(RELEASED,acquire(next).handle.releaseChecked(next).kind);
            assertArrayEquals(new byte[]{1},next.files.get(data));
        }
    }

    @Test
    void crashAfterActionLeavesAnOrphanOrProtectsARedeliveredFile() throws Exception {
        ShareLockTest.Server next = new ShareLockTest.Server();
        next.files.put(data,new byte[]{1});
        HeldLock held = acquire(next).handle;
        assertEquals(OURS,held.verifyOwner(next).kind);
        next.files.remove(data);
        held.abandon();
        assertEquals(Collections.singleton(lock), next.files.keySet());
        next.files.put(data,new byte[]{2});
        assertEquals(0,sweep(next));
        next.files.remove(data);
        assertEquals(1,sweep(next));
        assertTrue(next.files.isEmpty());
    }

    @Test
    void pendingStateWriteKeepsOwnershipAcrossShareFailure() {
        ShareLockTest.Server next = new ShareLockTest.Server();
        next.files.put(data,new byte[]{1});
        HeldLock held = acquire(next).handle;
        held.pendingStateWrite();
        ShareLockTest.Server unavailable = new ShareLockTest.Server() {
            @Override LockValue read(String uri) throws IOException {
                throw ShareLockTest.failure(Fault.TRANSPORT);
            }
        };
        LockOutcome check = held.verifyOwner(unavailable);
        assertEquals(CHECK_FAILED, check.kind);
        assertTrue(check.classification.getKind().isTransport());
        assertEquals(BUSY_LOCAL,acquire(next).kind);
        assertTrue(next.files.containsKey(lock));
        assertTrue(PathRegistry.held(held.entry.value));
        assertEquals(OURS,held.verifyOwner(next).kind);
        assertEquals(RELEASED,held.releaseChecked(next).kind);
        assertFalse(next.files.containsKey(lock));
    }
}
