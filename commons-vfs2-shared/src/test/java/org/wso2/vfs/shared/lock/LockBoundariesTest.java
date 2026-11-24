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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;

class LockBoundariesTest {
    @Test
    void warnOncePastHalfAgeOnlyForTheOwningEndpoint() {
        String name = UUID.randomUUID().toString();
        EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND,name,5000);
        AtomicLong nanos = new AtomicLong();
        ShareLock locks = new ShareLock(name,true,20,health,health.namedLog(),System::currentTimeMillis,nanos::get);
        ShareLock other = new ShareLock(name+"other",true,60000,health,health.namedLog(),System::currentTimeMillis,nanos::get);
        HeldLock held = locks.enterLocalOnly("file:///tmp/"+name,1,0).handle;
        try {
            nanos.set(held.entry.started+TimeUnit.MILLISECONDS.toNanos(30000));
            assertEquals(0,locks.checkHeldAges());
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            assertEquals(0,other.checkHeldAges());
            assertEquals(1,locks.checkHeldAges());
            assertEquals(0,locks.checkHeldAges());
            assertEquals(1L,health.snapshot().get("locksHeldLong"));
        } finally {
            held.abandon();
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND,name);
        }
    }

    @Test
    void releaseOfOurAbandonedValueIsIndependentOfAutoReleaseAndAge() {
        for (boolean auto : new boolean[]{false,true}) {
            String name = UUID.randomUUID().toString();
            String data = "file:///tmp/"+name;
            EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.CONNECTION,name,0);
            ShareLock locks = ShareLock.create(name,auto,-1,health);
            ShareLockTest.Server io = new ShareLockTest.Server();
            try {
                HeldLock held = locks.acquire(io,data,ShareLock.Mode.WRITER,null).handle;
                assertEquals(0,locks.checkHeldAges());
                assertEquals(LOCKED,locks.isLockedByOther(io,data).kind);
                held.abandon();
                HeldLock next=locks.acquire(io,data,ShareLock.Mode.WRITER,null).handle;
                assertTrue(next.tookOver());
                assertEquals(RELEASED,next.releaseChecked(io).kind);
                io.files.put(data+".lock",LockValue.create(1,UUID.randomUUID().toString()).bytes);
                assertEquals(BUSY_SHARE,locks.acquire(io,data,ShareLock.Mode.WRITER,null).kind);
            } finally {
                HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION,name);
            }
        }
    }

    @Test
    void oneListingCapsOnlyOrphanCandidatesAndNeverRemovesTheListedSibling() throws Exception {
        String name=UUID.randomUUID().toString();
        String folder="file:///tmp/"+name+"/";
        EndpointHealth health=HealthRegistry.register(HealthRegistry.Kind.INBOUND,name,5000);
        ShareLock locks=ShareLock.create(name,true,60000,health);
        ShareLockTest.Server io=new ShareLockTest.Server();
        Map<String,FolderListing.Metadata> files=new LinkedHashMap<>();
        FolderListing.Metadata metadata=new FolderListing.Metadata(1,1);
        try {
            for (int i=0;i<24;i++) {
                String base="f"+i;
                files.put(base+".lock",metadata);
                io.files.put(folder+base+".lock","1:1".getBytes(StandardCharsets.UTF_8));
                if (i==0) {
                    files.put(base,metadata);
                    io.files.put(folder+base,new byte[]{1});
                }
            }
            assertEquals(20,locks.sweepOrphans(io,FolderListing.of(folder,files)));
            assertTrue(io.files.containsKey(folder+"f0.lock"));
            assertEquals(5,io.files.size());
            files.keySet().removeIf(n -> !io.files.containsKey(folder+n));
            assertEquals(3,locks.sweepOrphans(io,FolderListing.of(folder,files)));
            assertEquals(2,io.files.size());
        } finally {
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND,name);
        }
    }

    @Test
    void twentyLiveWritersCannotHideALaterOrphanAcrossPolls() throws Exception {
        String id = UUID.randomUUID().toString();
        EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, id, 5000);
        ShareLock locks = ShareLock.create(id, true, 60000, health);
        java.util.Set<String> visited = new java.util.HashSet<>();
        ShareLockTest.Server io = new ShareLockTest.Server() {
            @Override LockValue read(String uri) throws java.io.IOException {
                if (!uri.endsWith(".t.lock")) { visited.add(uri); }
                return super.read(uri);
            }
        };
        String folder = "file:///tmp/" + id + "/";
        Map<String, FolderListing.Metadata> names = new LinkedHashMap<>();
        java.util.List<HeldLock> writers = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                String name = String.format(java.util.Locale.ROOT, "live%02d", i);
                writers.add(locks.acquire(io, folder + name, ShareLock.Mode.WRITER, null).handle);
                names.put(name + ".lock", new FolderListing.Metadata(0, 0));
            }
            names.put("zOrphan.lock", new FolderListing.Metadata(0, 0));
            io.files.put(folder + "zOrphan.lock", LockValue.create(1, UUID.randomUUID().toString()).bytes);
            FolderListing listing = FolderListing.of(folder, names);
            String otherFolder = folder + "other/";
            Map<String, FolderListing.Metadata> otherNames = new LinkedHashMap<>(names);
            for (String lock : otherNames.keySet()) {
                io.files.put(otherFolder + lock, LockValue.create(lock.equals("zOrphan.lock") ? 1 :
                        System.currentTimeMillis(), UUID.randomUUID().toString()).bytes);
            }
            FolderListing other = FolderListing.of(otherFolder, otherNames);
            String small = folder + "small/";
            io.files.put(small + "one.lock", LockValue.create(System.currentTimeMillis(), UUID.randomUUID().toString()).bytes);
            FolderListing one = FolderListing.of(small, java.util.Collections.singletonMap("one.lock", null));
            int removed = 0;
            for (int poll = 0; poll < 3; poll++) {
                visited.clear();
                java.util.List<FolderListing> all = poll % 2 == 0
                        ? java.util.Arrays.asList(listing, other, one) : java.util.Arrays.asList(one, other, listing);
                removed += locks.sweepOrphans(io, all);
                assertEquals(20, visited.size());
                if (poll == 0) {
                    assertTrue(io.files.containsKey(folder + "zOrphan.lock"));
                    assertTrue(io.files.containsKey(otherFolder + "zOrphan.lock"));
                }
                names.keySet().removeIf(n -> !io.files.containsKey(folder + n));
                otherNames.keySet().removeIf(n -> !io.files.containsKey(otherFolder + n));
                listing = FolderListing.of(folder, names);
                other = FolderListing.of(otherFolder, otherNames);
            }
            assertEquals(2, removed);
            assertFalse(io.files.containsKey(folder + "zOrphan.lock"));
            assertFalse(io.files.containsKey(otherFolder + "zOrphan.lock"));
            assertEquals(41, io.files.size());
        } finally {
            for (HeldLock writer : writers) { writer.releaseChecked(io); }
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, id);
        }
    }

    @Test
    void replacementWithTheSameNameAndEvenTheSameHealthCannotBillTheOldHandle() {
        String name = UUID.randomUUID().toString();
        EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.CONNECTION, name, 0);
        AtomicLong clock = new AtomicLong(System.nanoTime());
        ShareLock old = new ShareLock(name, true, -1, health, health.namedLog(), System::currentTimeMillis, clock::get);
        ShareLock next = new ShareLock(name, true, 60000, health, health.namedLog(), System::currentTimeMillis, clock::get);
        String uri = "file:///tmp/" + name;
        HeldLock prior = old.enterLocalOnly(uri, 1, 0).handle;
        try {
            clock.set(prior.entry.started + TimeUnit.MILLISECONDS.toNanos(30001));
            assertEquals(0, next.checkHeldAges());
            assertEquals(0, old.checkHeldAges());
            assertEquals(LOCK_BUSY, next.enterLocalOnly(uri, 1, 0).kind);
            prior.abandon();
            HeldLock own = next.enterLocalOnly(uri, 1, 0).handle;
            try {
                clock.set(own.entry.started + TimeUnit.MILLISECONDS.toNanos(30000));
                assertEquals(0, next.checkHeldAges());
                clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
                assertEquals(1, next.checkHeldAges());
                assertEquals(0, next.checkHeldAges());
            } finally {
                own.abandon();
            }
        } finally {
            prior.abandon();
            HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION, name);
        }
    }
}
