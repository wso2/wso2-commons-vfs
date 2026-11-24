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
package org.wso2.vfs.shared.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.wso2.vfs.shared.failstate.FileIdentity;
import org.wso2.vfs.shared.failstate.DigestingInputStream;
import java.io.ByteArrayInputStream;
import java.net.SocketException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.log.LoggingTest.Lines;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

class EndpointHealthTest {
    private final AtomicLong clock = new AtomicLong();
    private final Lines lines = new Lines();
    private final NamedLog log = NamedLog.inbound(lines, "input", new RepeatLimiter());

    private EndpointHealth health(final long interval) {
        return new EndpointHealth(HealthRegistry.Kind.INBOUND, "input", interval, log, clock::get,
                () -> 1234000 + clock.get(), () -> {}, new EndpointHealth.Publication());
    }

    static Classification transport(final NamedLog log) {
        return new ErrorClassifier(log).classify(new SocketException("sftp://u:private@host/path"),
                10000, ErrorClassifier.CallType.READ);
    }

    private void at(final EndpointHealth health, final long millis, final EndpointHealth.State expected) {
        at(health, millis, expected, null);
    }

    private void at(EndpointHealth endpoint, long time, EndpointHealth.State state, String assertion) {
        clock.set(time);
        endpoint.refresh(true);
        assertEquals(state, endpoint.state(), assertion);
    }

    @Test
    void pausedBeforeTheFirstPoll() {
        EndpointHealth endpoint = health(10000);
        at(endpoint, 0, EndpointHealth.State.PAUSED, "[a new endpoint is PAUSED until its first poll]");
        at(endpoint, 1000000, EndpointHealth.State.PAUSED, "[a new endpoint is PAUSED until its first poll]");
    }

    @Test
    void inFlightPollUsesTheStallFloor() {
        EndpointHealth endpoint = health(10000);
        endpoint.resumed();
        at(endpoint, 1000000, EndpointHealth.State.OK, "[a poll with no progress is DOWN after 180 s]");
        endpoint.pollStarted();
        at(endpoint, 1179000, EndpointHealth.State.OK, "[a poll with no progress is DOWN after 180 s]");
        at(endpoint, 1179999, EndpointHealth.State.OK, "[a poll with no progress is DOWN after 180 s]");
        at(endpoint, 1180000, EndpointHealth.State.DOWN, "[a poll with no progress is DOWN after 180 s]");
    }

    @Test
    void idleIntervalRunsFromPollCompletion() {
        EndpointHealth endpoint = health(100000);
        endpoint.pollStarted();
        long finish = 45000;
        clock.set(finish);
        endpoint.pollFinished(true, null);
        at(endpoint, finish + 299000, EndpointHealth.State.OK, "[DOWN three intervals after the last poll ended]");
        at(endpoint, finish + 299999, EndpointHealth.State.OK, "[DOWN three intervals after the last poll ended]");
        at(endpoint, finish + 300000, EndpointHealth.State.DOWN, "[DOWN three intervals after the last poll ended]");
    }

    @Test
    void skippedTickTracksSchedulerProgressWithoutReplacingTheLastOutcome() {
        EndpointHealth endpoint = health(10000);
        endpoint.pollStarted();
        clock.set(10);
        endpoint.pollSkipped();
        assertEquals(0L, endpoint.snapshot().get("lastSuccess"));
        at(endpoint, 180009, EndpointHealth.State.OK, "first skip arms the idle timer");
        at(endpoint, 180010, EndpointHealth.State.DOWN, "stall at the idle bound");
        endpoint.pollStarted();
        endpoint.pollFinished(true, null);
        at(endpoint, clock.get(), EndpointHealth.State.OK);
        Map<String, Object> afterSuccess = endpoint.snapshot();
        clock.incrementAndGet();
        endpoint.pollStarted();
        endpoint.pollSkipped();
        at(endpoint, clock.get(), EndpointHealth.State.OK);
        assertEquals(afterSuccess, endpoint.snapshot());
        endpoint.pollStarted();
        endpoint.pollFinished(false, transport(log));
        at(endpoint, clock.get(), EndpointHealth.State.DOWN);
        Map<String, Object> afterFailure = endpoint.snapshot();
        clock.incrementAndGet();
        endpoint.pollStarted();
        endpoint.pollSkipped();
        at(endpoint, clock.get(), EndpointHealth.State.DOWN);
        assertEquals(afterFailure, endpoint.snapshot());
        clock.incrementAndGet();
        endpoint.pollStarted();
        endpoint.pollFinished(true, null);
        at(endpoint, clock.get(), EndpointHealth.State.OK);
        assertTrue((Long) endpoint.snapshot().get("lastSuccess") > (Long) afterSuccess.get("lastSuccess"));
    }

    @Test
    void digestReadsKeepThePollHealthyForTenScanWindows() throws Exception {
        EndpointHealth endpoint = health(10000);
        endpoint.pollStarted();
        FileIdentity identity = new FileIdentity(
                "file:///test", 10, 1, FileIdentity.TimeSource.COMMAND, null);
        try (DigestingInputStream input =
                new DigestingInputStream(new ByteArrayInputStream(new byte[10]), identity, endpoint::progress)) {
            for (int tick = 1; tick <= 10; tick++) {
                at(endpoint, tick * 100000L, EndpointHealth.State.OK, "[bytes read by the digest count as progress]");
                assertEquals(0, input.read(), "[bytes read by the digest count as progress]");
            }
            assertEquals(-1, input.read(), "[bytes read by the digest count as progress]");
            assertNotNull(identity.digest(), "[bytes read by the digest count as progress]");
        }
    }

    @Test
    void cronStallsOnlyWhileAPollIsRunning() {
        EndpointHealth endpoint = health(0);
        endpoint.pollStarted();
        clock.set(45000);
        endpoint.pollFinished(true, null);
        long next = 45000 + 10000000;
        at(endpoint, next, EndpointHealth.State.OK, "[a cron endpoint is DOWN only while a poll runs]");
        endpoint.pollStarted();
        at(endpoint, next + 179999, EndpointHealth.State.OK, "[a cron endpoint is DOWN only while a poll runs]");
        at(endpoint, next + 180000, EndpointHealth.State.DOWN, "[a cron endpoint is DOWN only while a poll runs]");
    }

    @Test
    void pauseAndResumeRearmTheNextPoll() {
        EndpointHealth endpoint = health(10000);
        endpoint.pollStarted();
        at(endpoint, 180000, EndpointHealth.State.DOWN, "[pause and resume reset the stall check]");
        endpoint.paused();
        assertEquals(EndpointHealth.State.PAUSED, endpoint.state(), "[pause and resume reset the stall check]");
        endpoint.resumed();
        at(endpoint, 400000, EndpointHealth.State.OK, "[pause and resume reset the stall check]");
        endpoint.pollStarted();
        at(endpoint, 579999, EndpointHealth.State.OK, "[pause and resume reset the stall check]");
        at(endpoint, 580000, EndpointHealth.State.DOWN, "[pause and resume reset the stall check]");
    }

    @Test
    void fileOutcomesAndPositiveByteReadsMoveTheDeadline() {
        final EndpointHealth endpoint = health(10000);
        endpoint.pollStarted();
        for (int file = 0; file < 5000; file++) {
            clock.addAndGet(100);
            endpoint.fileDone();
            if (file % 1000 == 999) {
                at(endpoint, clock.get(), EndpointHealth.State.OK);
            }
        }
        at(endpoint, 600000, EndpointHealth.State.OK);
        endpoint.fileFailed(transport(log));
        for (int chunk = 0; chunk < 16; chunk++) {
            clock.addAndGet(100000);
            endpoint.progress(128L * 1024 * 1024);
            endpoint.refresh(false);
            assertEquals(EndpointHealth.State.OK, endpoint.state());
        }
        at(endpoint, 2379999, EndpointHealth.State.OK);
        endpoint.progress(0);
        at(endpoint, 2380000, EndpointHealth.State.DOWN);
        assertEquals(5000L, endpoint.snapshot().get("filesDone"));
        assertEquals(1L, endpoint.snapshot().get("filesFailed"));
    }

    @Test
    void rootAndFileFaultsKeepTheMaskedCauseAndCounters() {
        final EndpointHealth endpoint = health(10000);
        final Classification cause = transport(log);
        endpoint.pollStarted();
        endpoint.transportFault(cause);
        endpoint.pollFinished(false, cause);
        at(endpoint, 1, EndpointHealth.State.DOWN);
        at(endpoint, 2, EndpointHealth.State.DOWN);
        final Map<?, ?> error = (Map<?, ?>) endpoint.snapshot().get("lastError");
        assertEquals("TRANSPORT", error.get("kind"));
        assertEquals(1234000L, error.get("time"));
        assertEquals("sftp://u:***@host/path", error.get("text"));
        endpoint.pollStarted();
        endpoint.fileFailed(cause);
        endpoint.transportFault(cause);
        endpoint.pollFinished(true, cause);
        at(endpoint, 3, EndpointHealth.State.DEGRADED);
        endpoint.lockContention();
        endpoint.lockTakenOver();
        endpoint.lockHeldLong();
        endpoint.lateThread();
        endpoint.inferredMove();
        endpoint.openSessions(3);
        endpoint.failStatesPending(4);
        endpoint.stateWritesPending(2);
        endpoint.pollStarted();
        endpoint.pollFinished(true, null);
        at(endpoint, 4, EndpointHealth.State.OK);
        final Map<String, Object> values = endpoint.snapshot();
        assertEquals(1234003L, values.get("lastSuccess"));
        assertEquals(2L, values.get("transportFaults"));
        for (final String key : new String[]{"filesFailed", "lockContention", "locksTakenOver", "locksHeldLong",
                "lateThreads", "inferredMoves"}) {
            assertEquals(1L, values.get(key), key);
        }
        assertEquals(3L, values.get("openSessions"));
        assertEquals(4L, values.get("failStatesPending"));
        assertEquals(2L, values.get("stateWritesPending"));
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("ERROR [inbound:input] Health changed to DOWN")).count());
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("WARN [inbound:input] Health changed to DEGRADED")).count());
        assertTrue(lines.lines.stream().noneMatch(line -> line.contains("private")));
    }

    @Test
    void exactFlagCountOutlivesTheLastSampleAndClearsOnTheLastTransition() {
        final EndpointHealth endpoint = health(10000);
        endpoint.resumed();
        final String base = "sftp://u:private@host/data/";
        for (int file = 0; file < 101; file++) {
            endpoint.flag(EndpointHealth.Flag.FAIL_STATE, base + file);
        }
        endpoint.flag(EndpointHealth.Flag.STATE_WRITE, base + "write");
        at(endpoint, 1, EndpointHealth.State.DEGRADED);
        assertEquals(101L, endpoint.snapshot().get("failStatesFlagged"));
        final Map<?, ?> flags = (Map<?, ?>) endpoint.snapshot().get("flags");
        final Map<?, ?> detail = (Map<?, ?>) flags.get("FAIL_STATE");
        final List<?> paths = (List<?>) detail.get("paths");
        assertEquals(100, paths.size());
        assertEquals("sftp://u:***@host/data/0", paths.get(0));
        assertTrue(lines.lines.stream().anyMatch(line -> line.startsWith("DEBUG [inbound:input] Health summary")
                && line.contains("filesDone=0") && line.contains("lastError=")
                && line.contains("sftp://u:***@host/data/0") && line.contains("count=101")));
        endpoint.clearFlag(EndpointHealth.Flag.STATE_WRITE, base + "write");
        for (int file = 0; file < 100; file++) {
            endpoint.clearFlag(EndpointHealth.Flag.FAIL_STATE, base + file);
        }
        at(endpoint, 2, EndpointHealth.State.DEGRADED);
        assertEquals(1L, endpoint.snapshot().get("failStatesFlagged"));
        assertEquals(0L, endpoint.snapshot().get("stateWritesFlagged"));
        endpoint.clearFlag(EndpointHealth.Flag.FAIL_STATE, base + 100);
        at(endpoint, 3, EndpointHealth.State.OK);
        assertFalse(lines.lines.toString().contains("private"));
    }

    @Test
    void allFlagCausesAffectStateAndTheSnapshot() {
        final EndpointHealth endpoint = health(10000);
        endpoint.pollStarted();
        for (final EndpointHealth.Flag flag : EndpointHealth.Flag.values()) {
            endpoint.flag(flag, "file:///sample");
            at(endpoint, 1, EndpointHealth.State.DEGRADED);
            assertTrue(((Map<?, ?>) endpoint.snapshot().get("flags")).containsKey(flag.name()));
            endpoint.clearFlag(flag, "file:///sample");
            at(endpoint, 2, EndpointHealth.State.OK);
        }
    }

    @Test
    void analyticsSetsTheBuiltInPropertyWithTheMaskedUriAndFileProtocol() {
        final Map<String, Object> properties = new HashMap<>();
        EndpointHealth.setStatisticsMetadata("sftp://u:secret@host/a?apiKey=private&ok=1", properties::put);
        assertEquals(1, properties.size());
        final Map<?, ?> metadata = (Map<?, ?>) properties.get("statisticsMetadata");
        assertEquals(2, metadata.size());
        assertEquals("file", metadata.get("protocol"));
        assertEquals("sftp://u:***@host/a?apiKey=***&ok=1", metadata.get("fileURI"));
    }
}
