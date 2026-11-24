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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ACQUIRED;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.CHECK_FAILED;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.TAKEN_OVER;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.ram.RamFileProvider;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;

/**
 * The owner check of a lock that another node took over. On SFTP, SMB2 and RAM the read of a lock file that is gone
 * fails with an error that does not say "missing". A fresh look then tells that the lock was taken over. A lock that is
 * there and can not be read, and a transport error, stay a failed check.
 */
class TakenOverLockTest {

    private final String endpoint = "taken" + UUID.randomUUID();

    private final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, endpoint, 5000);

    private final ShareLock locks = ShareLock.create(endpoint, true, 900000, health);

    @AfterEach
    void stop() {
        HealthRegistry.unregister(HealthRegistry.Kind.INBOUND, endpoint);
    }

    // The read of a lock that is gone fails as the providers fail it: SftpFileObject and Smb2FileObject check the type
    // first and throw this error when the file has no content.
    private static FileSystemException goneOnRead(final String uri) {
        return new FileSystemException("vfs.provider/read-not-file.error", uri);
    }

    @Test
    void ramLockThatTheTakerRemovedIsTakenOver() throws Exception {
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("ram", new RamFileProvider());
            manager.init();
            final String data = "ram:///in/" + UUID.randomUUID() + ".txt";
            try (FileObject file = manager.resolveFile(data)) {
                file.createFile();
            }
            final LockIo io = new LockIo(manager, null);
            final LockOutcome taken = locks.acquire(io, data, ShareLock.Mode.WRITER, null);
            assertEquals(ACQUIRED, taken.kind);
            // The other node took the lock over and released it after it handled the file.
            try (FileObject lock = manager.resolveFile(data + ".lock")) {
                assertTrue(lock.delete());
            }
            assertEquals(TAKEN_OVER, taken.handle.verifyOwner(io).kind);
            assertEquals(1L, health.snapshot().get("locksTakenOver"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sftp://host/in/", "smb2://host/share/in/"})
    void goneLockOnARemoteServerIsTakenOver(final String folder) {
        final String data = folder + UUID.randomUUID() + ".txt";
        final ShareLockTest.Server server = new ShareLockTest.Server() {
            @Override
            LockValue read(final String uri) throws IOException {
                calls++;
                if (!files.containsKey(uri)) {
                    throw goneOnRead(uri);
                }
                return super.read(uri);
            }
        };
        final HeldLock held = locks.acquire(server, data, ShareLock.Mode.WRITER, null).handle;
        server.files.remove(data + ".lock");
        assertEquals(TAKEN_OVER, held.verifyOwner(server).kind);
        assertEquals(1L, health.snapshot().get("locksTakenOver"));
    }

    @Test
    void lockThatIsThereAndCanNotBeReadStaysAFailedCheck() {
        final String data = "sftp://host/in/" + UUID.randomUUID() + ".txt";
        final AtomicBoolean failing = new AtomicBoolean();
        final ShareLockTest.Server server = new ShareLockTest.Server() {
            @Override
            LockValue read(final String uri) throws IOException {
                if (failing.get()) {
                    calls++;
                    throw ShareLockTest.failure(Fault.APPLICATION);
                }
                return super.read(uri);
            }
        };
        final HeldLock held = locks.acquire(server, data, ShareLock.Mode.WRITER, null).handle;
        failing.set(true);
        assertTrue(server.files.containsKey(data + ".lock"));
        assertEquals(CHECK_FAILED, held.verifyOwner(server).kind);
        assertEquals(0L, health.snapshot().get("locksTakenOver"));
    }

    @Test
    void transportErrorInTheReadIsNeverTakenOver() {
        final String data = "sftp://host/in/" + UUID.randomUUID() + ".txt";
        final AtomicBoolean failing = new AtomicBoolean();
        final ShareLockTest.Server server = new ShareLockTest.Server() {
            @Override
            LockValue read(final String uri) throws IOException {
                if (failing.get()) {
                    calls++;
                    throw ShareLockTest.failure(Fault.TRANSPORT);
                }
                return super.read(uri);
            }
        };
        final HeldLock held = locks.acquire(server, data, ShareLock.Mode.WRITER, null).handle;
        failing.set(true);
        // The lock is gone, but a transport error decides nothing, so no look follows.
        server.files.remove(data + ".lock");
        final int calls = server.calls;
        assertEquals(CHECK_FAILED, held.verifyOwner(server).kind);
        assertEquals(calls + 1, server.calls);
        assertEquals(0L, health.snapshot().get("locksTakenOver"));
    }
}
