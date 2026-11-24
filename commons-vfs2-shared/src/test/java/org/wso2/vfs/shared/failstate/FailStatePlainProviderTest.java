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
package org.wso2.vfs.shared.failstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.ram.RamFileProvider;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;

/**
 * A failed file of a provider with no atomic calls, such as RAM or the jcifs SMB of the base library, gets its fail
 * state through the plain calls of the base library: the marker is written, renamed into place and read back.
 */
class FailStatePlainProviderTest {

    private final String endpoint = "plain" + UUID.randomUUID();

    @Test
    void failedFileOfAProviderWithNoAtomicCallsGetsItsFailState() throws Exception {
        final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 10000);
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("ram", new RamFileProvider());
            manager.init();
            final String uri = "ram:///in/" + UUID.randomUUID() + "/b.csv";
            try (FileObject file = manager.resolveFile(uri); OutputStream out = file.getContent().getOutputStream()) {
                out.write(new byte[1024]);
            }
            final LockIo io = new LockIo(manager, null);
            final ShareLock locks = ShareLock.create(endpoint, true, 60000, health);
            final FailStateStore.Parameters params = new FailStateStore.Parameters();
            final AtomicLong wall = new AtomicLong(1700000000000L);
            final AtomicLong monotonic = new AtomicLong();
            final FailStateStore store = new FailStateStore(endpoint, params, locks, health, wall::get,
                    monotonic::get);
            final ActionResult refused = new ActionResult(ActionResult.Step.NONE, true, null, false, null);

            final LockOutcome first = locks.acquire(io, uri, ShareLock.Mode.LISTENER, null);
            assertEquals(LockOutcome.Kind.ACQUIRED, first.kind);
            final HeldLock held = first.handle;
            final FileIdentity id = store.beginFile(io, held, uri).identity;
            assertNotNull(id, "The digest works by the plain look");
            assertEquals(FailStateStore.Write.WRITTEN, store.recordFailureResult(io, held, id,
                    FailState.Outcome.FAILURE, FailState.Action.DELETE, null, refused).disposition);
            try (FileObject marker = manager.resolveFile(uri + ".fail")) {
                assertTrue(marker.exists(), "The marker is renamed into place by the plain calls");
            }

            wall.addAndGet(1000);
            final LockOutcome second = locks.acquire(io, uri, ShareLock.Mode.LISTENER, null);
            assertEquals(LockOutcome.Kind.ACQUIRED, second.kind);
            assertNotNull(store.beginFile(io, second.handle, uri).state, "The fail state is read back");
            second.handle.releaseChecked(io);
        } finally {
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
        }
    }
}
