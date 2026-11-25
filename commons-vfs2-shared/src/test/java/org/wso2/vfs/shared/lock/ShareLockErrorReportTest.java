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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ACQUIRED;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.BUSY_SHARE;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.ERROR;
import static org.wso2.vfs.shared.lock.LockOutcome.Kind.TRANSPORT_ERROR;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.log.LoggingTest;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

/**
 * A lock that can not be taken for a reason that is neither busy nor a transport error is shown to the operator: one
 * WARN for its folder and the last error of the health record. The caller still gets the outcome it got before.
 */
class ShareLockErrorReportTest {

    private static final String SECRET = "SyntheticSecret";

    private static final String FOLDER = "sftp://unit:" + SECRET + "@host/in" + UUID.randomUUID();

    private final String name = UUID.randomUUID().toString();

    private final EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, name, 5000);

    private final LoggingTest.Lines sink = new LoggingTest.Lines();

    private final ShareLock owner = new ShareLock(name, true, 900000, health,
            NamedLog.inbound(sink, name, new RepeatLimiter()), System::currentTimeMillis, System::nanoTime);

    /** A server whose exclusive create of a lock gives the queued replies in turn, and DONE when none is left. */
    private static final class Replies extends ShareLockTest.Server {
        final Deque<AtomicResult> creates = new ArrayDeque<>();

        @Override
        AtomicResult create(final String uri, final LockValue value, final Runnable beforeLink) throws IOException {
            final AtomicResult next = creates.poll();
            return next == null ? super.create(uri, value, beforeLink) : next;
        }
    }

    /** A server where a lock file is there and its read fails with the given fault. */
    private static final class UnreadableLock extends ShareLockTest.Server {
        private final Fault fault;

        UnreadableLock(final Fault fault) {
            this.fault = fault;
        }

        @Override
        boolean exists(final String uri) {
            return true;
        }

        @Override
        LockValue read(final String uri) throws IOException {
            throw ShareLockTest.failure(fault);
        }
    }

    /**
     * A server where the look finds a lock of another party that its holder releases before the read. The read then
     * fails with the plain read error that SFTP and SMB2 give for a name that is not there.
     */
    private static final class ReleasedBeforeRead extends ShareLockTest.Server {
        private final String lock;

        private boolean released;

        ReleasedBeforeRead(final String lock) {
            this.lock = lock;
        }

        @Override
        boolean exists(final String uri) throws IOException {
            return lock.equals(uri) && !released || super.exists(uri);
        }

        @Override
        LockValue read(final String uri) throws IOException {
            if (lock.equals(uri) && !released) {
                released = true;
                throw ShareLockTest.failure(Fault.APPLICATION);
            }
            return super.read(uri);
        }
    }

    @Test
    void refusedExclusiveCreateNamesTheStepAndTheReplyOnceForTheFolder() {
        final Replies server = new Replies();
        server.creates.add(new AtomicResult(AtomicOutcome.GONE, "id 2 data/a.csv.u.lock"));
        server.creates.add(new AtomicResult(AtomicOutcome.REFUSED, "id 3 permission denied"));

        final LockOutcome gone = acquire(server, FOLDER + "/a.csv");
        assertEquals(ERROR, gone.kind);
        assertNotNull(gone.classification);
        final String text = gone.classification.getStatusText();
        assertTrue(text.contains("The exclusive create of"), text);
        assertTrue(text.contains("ended with GONE"), text);
        assertTrue(text.contains("id 2 data/a.csv.u.lock"), text);
        assertFalse(text.contains(SECRET), text);

        final LockOutcome refused = acquire(server, FOLDER + "/b.csv");
        assertEquals(ERROR, refused.kind);
        assertTrue(refused.classification.getStatusText().contains("ended with REFUSED"));

        assertEquals(1, warnings(), String.valueOf(sink.lines));
        assertFalse(String.valueOf(sink.lines).contains(SECRET), String.valueOf(sink.lines));
        final Map<?, ?> last = lastError();
        assertTrue(String.valueOf(last.get("text")).contains("ended with REFUSED"), String.valueOf(last));
        assertFalse(String.valueOf(last).contains(SECRET), String.valueOf(last));
        assertEquals(0L, health.snapshot().get("filesFailed"));
    }

    @Test
    void folderWarnsOnceInTheWindowAlsoWhenOtherFilesPass() {
        final Replies server = new Replies();
        server.creates.add(new AtomicResult(AtomicOutcome.REFUSED, "first"));
        assertEquals(ERROR, acquire(server, FOLDER + "/a.csv").kind);
        final LockOutcome taken = acquire(server, FOLDER + "/b.csv");
        assertEquals(ACQUIRED, taken.kind);
        taken.handle.abandon();
        server.creates.add(new AtomicResult(AtomicOutcome.REFUSED, "second"));
        assertEquals(ERROR, acquire(server, FOLDER + "/a.csv").kind);
        assertEquals(1, warnings(), String.valueOf(sink.lines));
        assertTrue(String.valueOf(lastError().get("text")).contains("second"), String.valueOf(lastError()));
    }

    @Test
    void lockReleasedBetweenTheLookAndTheReadIsTheRaceAndIsNotReported() {
        final String data = FOLDER + "/a.csv";
        final ReleasedBeforeRead server = new ReleasedBeforeRead(data + ".lock");
        final LockOutcome outcome = acquire(server, data);
        assertEquals(ACQUIRED, outcome.kind);
        outcome.handle.abandon();
        assertEquals(0, warnings(), String.valueOf(sink.lines));
        assertTrue(lastError().isEmpty(), String.valueOf(lastError()));
    }

    @Test
    void lockFileThatCanNotBeReadIsReportedAndStillLooksBusy() {
        final LockOutcome outcome = acquire(new UnreadableLock(Fault.APPLICATION), FOLDER + "/a.csv");
        assertEquals(BUSY_SHARE, outcome.kind);
        assertEquals(1, warnings(), String.valueOf(sink.lines));
        assertFalse(lastError().isEmpty());
        assertEquals(0L, health.snapshot().get("filesFailed"));
    }

    @Test
    void busyLockAndTransportErrorGiveNoWarning() {
        final ShareLockTest.Server busy = new ShareLockTest.Server();
        final String data = FOLDER + "/a.csv";
        busy.files.put(data + ".lock", LockValue.create(System.currentTimeMillis(), "other").bytes);
        assertEquals(BUSY_SHARE, acquire(busy, data).kind);

        assertEquals(TRANSPORT_ERROR, acquire(new UnreadableLock(Fault.TRANSPORT), FOLDER + "/b.csv").kind);

        assertEquals(0, warnings(), String.valueOf(sink.lines));
        assertTrue(lastError().isEmpty(), String.valueOf(lastError()));
    }

    private LockOutcome acquire(final LockIo io, final String data) {
        return owner.acquire(io, data, ShareLock.Mode.WRITER, null);
    }

    private long warnings() {
        return sink.lines.stream().filter(line -> line.startsWith("WARN") && line.contains("could not be taken"))
                .count();
    }

    private Map<?, ?> lastError() {
        return (Map<?, ?>) health.snapshot().get("lastError");
    }
}
