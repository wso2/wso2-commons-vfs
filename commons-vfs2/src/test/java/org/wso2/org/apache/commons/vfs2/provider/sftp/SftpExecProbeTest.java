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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/**
 * The probe of the user id: no exec request on the connect path, one deadline for each command, and the state of
 * exec settled once per file system.
 */
public class SftpExecProbeTest {

    private SftpTestServer server;

    @BeforeEach
    public void start() throws Exception {
        server = new SftpTestServer();
        server.write("in/small.txt", "small");
        server.write("in/other.txt", "other");
    }

    @AfterEach
    public void stop() throws Exception {
        server.close();
    }

    private static StandardFileSystemManager manager() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        return manager;
    }

    private static long millisSince(final long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static List<String> probeWarnings(final LogCapture log) {
        return Arrays.asList(log.messages(Level.WARN).stream()
                .filter(text -> text.contains("the permission check over an exec channel within")).toArray(String[]::new));
    }

    @Test
    public void connectAndReadSendNoExecRequest() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            file.getContent().getInputStream().close();
            file.getParent().getChildren();
            assertEquals(0, server.getExecRequests());
        }
    }

    @Test
    public void execThatNeverAnswersEndsAtTheDeadlineWithOneWarning() throws Exception {
        server.setExec(SftpTestServer.Exec.NEVER_ANSWER);
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), options);
            assertTrue(file.exists());
            assertEquals(0, server.getExecRequests());
            final long start = System.nanoTime();
            assertTrue(file.isWriteable());
            final long elapsed = millisSince(start);
            assertTrue(elapsed >= 9500 && elapsed < 11000, "elapsed " + elapsed);
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            assertTrue(file.isWriteable());
            assertTrue(manager.resolveFile(server.uri("in/other.txt"), options).isWriteable());
            assertEquals(1, server.getExecRequests());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
        }
    }

    @Test
    public void serverThatRefusesExecGivesDisabledWithOneWarning() throws Exception {
        server.setExec(SftpTestServer.Exec.REFUSE);
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), options);
            final long start = System.nanoTime();
            assertTrue(file.isWriteable());
            assertTrue(millisSince(start) < 5000);
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            // From here the server would run a command, and the file system asks for none.
            server.setExec(SftpTestServer.Exec.ANSWER);
            assertTrue(file.isWriteable());
            assertTrue(manager.resolveFile(server.uri("in/other.txt"), options).isWriteable());
            assertEquals(0, server.getExecRequests());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
            // The refusal left the session in use.
            assertTrue(file.getFileSystem() instanceof SftpFileSystem
                    && ((SftpFileSystem) file.getFileSystem()).isAliveLocally());
        }
    }

    @Test
    public void avoidPermissionCheckSendsNoExecRequest() throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setAvoidPermissionCheck(options, "true");
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), options);
            assertTrue(file.exists());
            file.getParent().getChildren();
            final FileObject written = manager.resolveFile(server.uri("in/written.txt"), options);
            written.getContent().getOutputStream().close();
            assertTrue(written.isWriteable());
            assertTrue(file.isReadable());
        }
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(
                    server.uri("in/small.txt?transport.vfs.AvoidPermissionCheck=true"), SftpTestServer.options());
            assertTrue(file.isWriteable());
        }
        assertEquals(0, server.getExecRequests());
    }

    private long firstCheckMillis(final FileObject file) throws Exception {
        final long start = System.nanoTime();
        file.isWriteable();
        return millisSince(start);
    }

    @Test
    public void slowUidAndSlowGroupsShareOneDeadline() throws Exception {
        server.setDelays(0, 0, 6100);
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final long elapsed = firstCheckMillis(file);
            assertTrue(elapsed >= 9500 && elapsed <= 11000, "elapsed " + elapsed);
            // The user id came at 6.1 s. The groups did not come before the deadline, so exec counts as disabled.
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            assertEquals(Arrays.asList("id -u", "id -G"), server.getCommands());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
        }
    }

    @Test
    public void twoSlowAnswersInsideTheDeadlineEnableExec() throws Exception {
        server.setIds("4242", "4242 77");
        server.setDelays(0, 0, 3500);
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final long elapsed = firstCheckMillis(file);
            assertTrue(elapsed >= 6500 && elapsed <= 10000, "elapsed " + elapsed);
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(4242, fileSystem.getUId());
        }
    }

    @Test
    public void slowOpenAndSlowRequestOfBothCommandsStayInsideTheDeadline() throws Exception {
        server.setIds("4242", "4242 77");
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            server.setDelays(2000, 2000, 0);
            final long elapsed = firstCheckMillis(file);
            // Each command waits 2 s for the open and 2 s for the reply to its request, so 8 s in all.
            assertTrue(elapsed >= 7500 && elapsed <= 11000, "elapsed " + elapsed);
            assertFalse(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
        }
    }

    @Test
    public void openThatTakesMoreThanHalfOfTheTimeLeftIsNoAnswer() throws Exception {
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            server.setDelays(6000, 0, 0);
            final long elapsed = firstCheckMillis(file);
            // JSch waits for the open and for the request with one value, so the open gets half of the 10 s.
            assertTrue(elapsed >= 4500 && elapsed <= 11000, "elapsed " + elapsed);
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
        }
    }

    @Test
    public void replyThatNeverEndsIsCutAtTheDeadline() throws Exception {
        server.setExec(SftpTestServer.Exec.ENDLESS_OUTPUT);
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final long elapsed = firstCheckMillis(file);
            assertTrue(elapsed >= 9500 && elapsed <= 11000, "elapsed " + elapsed);
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
        }
    }

    // A permission check on a thread of its own, so a test can cut its session or interrupt it at a known point.
    private static final class Check {

        final AtomicReference<Throwable> error = new AtomicReference<>();

        final AtomicBoolean interruptedAfter = new AtomicBoolean();

        final AtomicLong ended = new AtomicLong();

        final Thread thread;

        Check(final FileObject file) {
            thread = new Thread(() -> {
                try {
                    file.isWriteable();
                } catch (final Throwable e) {
                    error.set(e);
                } finally {
                    interruptedAfter.set(Thread.currentThread().isInterrupted());
                    ended.set(System.nanoTime());
                }
            }, "permission-check");
            thread.start();
        }

        // Waits until the caller is in the poll of the probe, past the connect of the channel. JSch clears an interrupt
        // that comes while it waits in the connect, so the interrupt of a test comes only in the poll.
        boolean awaitPoll() throws InterruptedException {
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                for (final StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(SftpFileSystem.class.getName())
                            && frame.getMethodName().equals("pause")) {
                        return true;
                    }
                }
                Thread.sleep(5);
            }
            return false;
        }

        long endedAfter(final long fromNanos) throws InterruptedException {
            thread.join(20000);
            assertFalse(thread.isAlive(), "the check ended");
            return TimeUnit.NANOSECONDS.toMillis(ended.get() - fromNanos);
        }
    }

    // A failure of the session during a command leaves the state as it was. The server takes the command and holds
    // its reply, and only then the connection is cut. The next check probes again on a new session.
    private void failureDuringCommand(final String command, final List<String> after) throws Exception {
        server.setIds("4242", "4242 77");
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager()) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"), options);
            assertTrue(file.exists());
            server.delayReplyOf(command, 30000);
            final Check check = new Check(file);
            assertTrue(server.awaitHeld(command, 15000), "the server holds the reply of " + command);
            final long cut = System.nanoTime();
            relay.cutAll();
            final long failedAfter = check.endedAfter(cut);
            assertTrue(check.error.get() != null, "the check failed");
            assertEquals(Fault.TRANSPORT, Faults.of(check.error.get()));
            assertTrue(failedAfter < 3000, "the check failed " + failedAfter + " ms after the cut");
            server.setDelays(0, 0, 0);
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(4242, fileSystem.getUId());
            assertEquals(after, server.getCommands());
            assertEquals(2, relay.getAccepted());
        }
    }

    // An interrupt of the caller while the server holds the reply of a command ends the check and its session. The
    // caller keeps its interrupt, nothing is decided about exec, and the next check works on a new connection.
    private void interruptDuringCommand(final String command, final List<String> after) throws Exception {
        server.setIds("4242", "4242 77");
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup group = SftpTestServer.groupOf(fileSystem);
            server.delayReplyOf(command, 30000);
            final Check check = new Check(file);
            assertTrue(server.awaitHeld(command, 15000), "the server holds the reply of " + command);
            assertTrue(check.awaitPoll(), "the caller waits in the poll of the probe");
            final long interrupted = System.nanoTime();
            check.thread.interrupt();
            final long endedAfter = check.endedAfter(interrupted);
            assertTrue(endedAfter < 3000, "the check ended " + endedAfter + " ms after the interrupt");
            assertTrue(check.error.get() != null, "the check failed");
            assertEquals(Fault.TRANSPORT, Faults.of(check.error.get()));
            assertTrue(check.interruptedAfter.get(), "the caller keeps its interrupt");
            assertTrue(group.isAborted());
            assertFalse(before.isConnected());
            assertTrue(probeWarnings(log).isEmpty(), log.messages(Level.WARN).toString());
            server.setDelays(0, 0, 0);
            file.refresh();
            file.isWriteable();
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(4242, fileSystem.getUId());
            assertNotSame(before, SftpTestServer.sessionOf(fileSystem));
            assertEquals(after, server.getCommands());
            assertEquals(2, relay.getAccepted());
        }
    }

    @Test
    public void interruptDuringTheUidEndsTheSessionAndTheNextCheckWorks() throws Exception {
        interruptDuringCommand("id -u", Arrays.asList("id -u", "id -u", "id -G"));
    }

    @Test
    public void interruptDuringTheGroupsEndsTheSessionKeepsTheUidAndTheNextCheckWorks() throws Exception {
        interruptDuringCommand("id -G", Arrays.asList("id -u", "id -G", "id -G"));
    }

    // The session ends after the attributes were read and before the probe, and a new connection would get no
    // banner. The check makes no connection: it fails at once, and nothing is decided. When the server answers
    // again, the next call works on a new session.
    private void checkOnASessionThatIsGone(final Duration connectTimeout, final boolean directRecovery)
            throws Exception {
        server.setIds("4242", "4242 77");
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileSystemOptions options = SftpTestServer.options();
            SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, connectTimeout);
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            relay.holdNewConnections(true);
            relay.cutAll();
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (before.isConnected() && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertFalse(before.isConnected());
            final long start = System.nanoTime();
            final JSchException error = assertThrows(JSchException.class, fileSystem::isExecDisabledForCheck);
            final long elapsed = millisSince(start);
            assertTrue(elapsed < 1000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertEquals(1, relay.getAccepted(), "the check tried no connection");
            assertTrue(server.getCommands().isEmpty());
            assertTrue(probeWarnings(log).isEmpty(), log.messages(Level.WARN).toString());
            relay.holdNewConnections(false);
            if (directRecovery) {
                assertEquals(4242, fileSystem.getUId());
            } else {
                file.refresh();
                file.isWriteable();
            }
            assertFalse(fileSystem.isExecDisabled());
            assertNotSame(before, SftpTestServer.sessionOf(fileSystem));
            assertEquals(Arrays.asList("id -u", "id -G"), server.getCommands());
            assertEquals(2, relay.getAccepted());
        }
    }

    private interface PublicCall {
        void run(SftpFileSystem fileSystem) throws Exception;
    }

    // The session of a resolved file ends before any permission probe, and the replacement connection gets no banner.
    // The public call itself, timed from its start, ends within the probe timeout of 10 s plus the 1 s slack, with a
    // failure of the session and no command sent. When the server answers again, the next public call works.
    private void publicCallOnASessionThatIsGone(final PublicCall call, final Duration connectTimeout)
            throws Exception {
        server.setIds("4242", "4242 77");
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager()) {
            final FileSystemOptions options = SftpTestServer.options();
            SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, connectTimeout);
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            relay.holdNewConnections(true);
            relay.cutAll();
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (before.isConnected() && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertFalse(before.isConnected());
            final long start = System.nanoTime();
            final Exception error = assertThrows(Exception.class, () -> call.run(fileSystem));
            final long elapsed = millisSince(start);
            assertTrue(elapsed >= 9000 && elapsed <= 11000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertEquals(2, relay.getAccepted(), "one replacement connection was tried");
            assertTrue(server.getCommands().isEmpty());
            relay.holdNewConnections(false);
            assertEquals(4242, fileSystem.getUId());
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(Arrays.asList("id -u", "id -G"), server.getCommands());
        }
    }

    @Test
    public void publicUidOnASessionThatIsGoneEndsWithinTheProbeTimeoutWithALongConnectTimeout() throws Exception {
        publicCallOnASessionThatIsGone(SftpFileSystem::getUId, Duration.ofMillis(15000));
    }

    @Test
    public void publicGroupsOnASessionThatIsGoneEndsWithinTheProbeTimeoutWithALongConnectTimeout() throws Exception {
        publicCallOnASessionThatIsGone(SftpFileSystem::getGroupsIds, Duration.ofMillis(15000));
    }

    @Test
    public void publicExecStateOnASessionThatIsGoneEndsWithinTheProbeTimeoutWithNoConnectTimeout() throws Exception {
        publicCallOnASessionThatIsGone(SftpFileSystem::isExecDisabled, Duration.ZERO);
    }

    @Test
    public void publicUidOfACallerThatIsInterruptedAlreadyOpensNoConnectionAndTheNextCallWorks() throws Exception {
        server.setIds("4242", "4242 77");
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager()) {
            final FileSystemOptions options = SftpTestServer.options();
            SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, Duration.ofMillis(15000));
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            relay.holdNewConnections(true);
            relay.cutAll();
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (before.isConnected() && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            assertFalse(before.isConnected());
            Thread.currentThread().interrupt();
            final long start = System.nanoTime();
            final Exception error;
            final boolean kept;
            try {
                error = assertThrows(Exception.class, fileSystem::getUId);
            } finally {
                kept = Thread.interrupted();
            }
            final long elapsed = millisSince(start);
            assertTrue(kept, "the caller keeps its interrupt");
            assertTrue(elapsed < 1000, "elapsed " + elapsed);
            assertTrue(error instanceof InterruptedIOException, String.valueOf(error));
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertEquals(1, relay.getAccepted(), "no replacement connection was tried");
            assertTrue(server.getCommands().isEmpty());
            relay.holdNewConnections(false);
            assertEquals(4242, fileSystem.getUId());
            assertEquals(2, relay.getAccepted());
            assertEquals(Arrays.asList("id -u", "id -G"), server.getCommands());
        }
    }

    @Test
    public void checkOnASessionThatIsGoneFailsAtOnceWithALongConnectTimeout() throws Exception {
        checkOnASessionThatIsGone(Duration.ofMillis(15000), false);
    }

    @Test
    public void checkOnASessionThatIsGoneFailsAtOnceWithNoConnectTimeoutAndTheDirectCallRecovers() throws Exception {
        checkOnASessionThatIsGone(Duration.ZERO, true);
    }

    @Test
    public void failureOfTheSessionDuringTheUidIsNoDecision() throws Exception {
        failureDuringCommand("id -u", Arrays.asList("id -u", "id -u", "id -G"));
    }

    @Test
    public void failureOfTheSessionDuringTheGroupsAllowsAFreshProbe() throws Exception {
        failureDuringCommand("id -G", Arrays.asList("id -u", "id -G", "id -G"));
    }

    @Test
    public void openAndRequestThatEachTakeJustUnderHalfStillEndAtTheDeadline() throws Exception {
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            // The uid comes after 4.8 s of open and 4.8 s of request, inside the two halves of 5 s. The groups then
            // have less than 0.5 s, and their open does not come in time.
            server.setDelays(4800, 4800, 0);
            final long elapsed = firstCheckMillis(file);
            assertTrue(elapsed >= 9500 && elapsed <= 11000, "elapsed " + elapsed);
            assertTrue(((SftpFileSystem) file.getFileSystem()).isExecDisabled());
            assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
        }
    }

    @Test
    public void serverThatEndsTheSessionAtTheExecRequestIsATransportFailureAndTheNextCheckProbesAgain()
            throws Exception {
        server.setIds("4242", "4242 77");
        server.setExec(SftpTestServer.Exec.DISCONNECT);
        try (TestRelay relay = new TestRelay(server.getPort()); StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final FileObject file = manager.resolveFile(SftpTestServer.uri(relay.getPort(), "in/small.txt"),
                SftpTestServer.options());
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            final SocketGroup group = SftpTestServer.groupOf(fileSystem);
            final FileSystemException error = assertThrows(FileSystemException.class, file::isWriteable);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertEquals("The SFTP session ended before the command \"id -u\" gave its exit status.",
                cause.getMessage());
            // The failed session is closed with its group, and nothing was decided about exec.
            assertFalse(before.isConnected());
            assertTrue(group.isAborted());
            assertTrue(probeWarnings(log).isEmpty(), log.messages(Level.WARN).toString());
            // The next check probes again, on a new session.
            server.setExec(SftpTestServer.Exec.ANSWER);
            file.refresh();
            // The uid 4242 does not own the file, so the answer is the other bits. The check must run and not throw.
            file.isWriteable();
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(4242, fileSystem.getUId());
            assertNotSame(before, SftpTestServer.sessionOf(fileSystem));
            assertEquals(Arrays.asList("id -u", "id -u", "id -G"), server.getCommands());
            assertEquals(2, relay.getAccepted());
        }
    }

    @Test
    public void goodReplyIsCachedOncePerFileSystemAndTheUidDecides() throws Exception {
        server.setIds("4242", "4242 77");
        try (StandardFileSystemManager manager = manager()) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), options);
            final long start = System.nanoTime();
            file.isWriteable();
            assertTrue(millisSince(start) < 10000);
            file.isWriteable();
            manager.resolveFile(server.uri("in/other.txt"), options).isReadable();
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            assertFalse(fileSystem.isExecDisabled());
            assertEquals(4242, fileSystem.getUId());
            assertEquals(Arrays.asList("id -u", "id -G"), server.getCommands());
        }
    }
}
