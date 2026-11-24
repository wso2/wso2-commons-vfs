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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

/**
 * The points of the link routine and of the publish on SFTP: link.after-refusal, link.after-look and
 * publish.after-link. A hold forces the order in which a holder releases its lock.
 */
@Tag("testbuild")
public class SftpLinkPointsTest {

    private static final String DIR = "vfs.testpoints.dir";

    @TempDir
    Path base;

    private Path copy;

    private static GenericFileName name(final String path, final FileType type) {
        return new GenericFileName("sftp", "localhost", 22, 22, "user", "pass", path, type) {
        };
    }

    private static final class OneChannel extends SftpFileSystem {

        private final ChannelSftp channel;

        OneChannel(final ChannelSftp channel) {
            super(name("/", FileType.FOLDER), session(), new SocketGroup(), new FileSystemOptions());
            this.channel = channel;
        }

        @Override
        protected ChannelSftp getChannel() {
            return channel;
        }

        @Override
        protected void releaseChannel(final ChannelSftp released, final Throwable error) {
            // The channel of the test stays.
        }
    }

    private static Session session() {
        final Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        return session;
    }

    @BeforeEach
    public void start() throws Exception {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        TestPoints.event("link-test", null);
        try (Stream<Path> folders = Files.list(base)) {
            copy = folders.filter(folder -> Files.exists(folder.resolve("copy"))).findFirst().get();
        }
    }

    @AfterEach
    public void stop() {
        System.clearProperty(DIR);
    }

    private static String hash(final String key) throws Exception {
        final StringBuilder hex = new StringBuilder();
        for (final byte b : MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 16);
    }

    private Path hold(final String point, final String key) throws Exception {
        final Path hold = copy.resolve(point + "@k" + hash(key) + ".hold");
        Files.write(hold, "wait=30\n".getBytes(StandardCharsets.UTF_8));
        return hold;
    }

    private void waitFor(final String point, final String key) throws Exception {
        final Path reached = copy.resolve(point + "@k" + hash(key) + ".1.reached");
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(reached)) {
            assertTrue(System.nanoTime() < end, point + " was not reached");
            Thread.sleep(10);
        }
        assertTrue(new String(Files.readAllBytes(reached), StandardCharsets.UTF_8).contains("key=" + key));
    }

    private static Thread run(final Runnable body) {
        final Thread thread = new Thread(body, "link-caller");
        thread.start();
        return thread;
    }

    private static SftpException reply(final int id) {
        return new SftpException(id, "reply " + id);
    }

    // The SFTP requests that reach the server.
    private static final List<String> WIRE = Arrays.asList("put", "hardlink", "lstat", "stat", "rm", "rename");

    private static long wireCalls(final ChannelSftp channel) {
        return mockingDetails(channel).getInvocations().stream()
                .filter(call -> WIRE.contains(call.getMethod().getName())).count();
    }

    // Runs createExclusive on a caller thread that a hold stops, interrupts it there, and gives the result and the
    // number of requests sent after the interrupt.
    private Object interruptAt(final String point, final String key, final ChannelSftp channel,
            final Callable call, final long[] after) throws Exception {
        hold(point, key);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = run(() -> {
            try {
                result.set(call.call());
            } catch (final Exception e) {
                result.set(e);
            }
        });
        waitFor(point, key);
        final long before = wireCalls(channel);
        caller.interrupt();
        caller.join(10000);
        assertFalse(caller.isAlive());
        after[0] = wireCalls(channel) - before;
        return result.get();
    }

    private interface Callable {
        Object call() throws Exception;
    }

    private static void assertInterruptedBefore(final Object result, final MovePhase phase, final String tempPath) {
        assertTrue(result instanceof UnclearResultException, String.valueOf(result));
        final UnclearResultException error = (UnclearResultException) result;
        assertEquals(phase, error.getPhase());
        assertEquals(tempPath, error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertTrue(error.getCause() instanceof InterruptedIOException);
    }

    @Test
    public void holderReleasesBetweenTheRefusedLinkAndTheLookAndTheProbeDecides() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        final AtomicBoolean released = new AtomicBoolean();
        final SftpATTRS lock = mock(SftpATTRS.class);
        doAnswer(call -> {
            if (released.get()) {
                throw reply(ChannelSftp.SSH_FX_NO_SUCH_FILE);
            }
            return lock;
        }).when(channel).lstat("in/a.lock");
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final String key = "sftp://localhost/in/a.lock";
        final Path hold = hold("link.after-refusal", key);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = run(() -> {
            try {
                result.set(file.createExclusive(new byte[] {1}, "s.lock", "p.lock"));
            } catch (final Exception e) {
                result.set(e);
            }
        });
        waitFor("link.after-refusal", key);
        verify(channel, never()).lstat(anyString());
        // The holder releases its lock now.
        released.set(true);
        Files.delete(hold);
        caller.join(10000);
        assertEquals(AtomicOutcome.EXISTS, ((AtomicResult) result.get()).getOutcome());
        verify(channel).hardlink("in/s.lock", "in/p.lock");
        verify(channel).rm("in/p.lock");
    }

    @Test
    public void lookPointComesAfterTheLookAndBeforeTheProbe() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat("in/a.lock");
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final String key = "sftp://localhost/in/a.lock";
        final Path hold = hold("link.after-look", key);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = run(() -> {
            try {
                result.set(file.createExclusive(new byte[] {1}, "s.lock", "p.lock"));
            } catch (final Exception e) {
                result.set(e);
            }
        });
        waitFor("link.after-look", key);
        verify(channel).lstat("in/a.lock");
        verify(channel, never()).hardlink("in/s.lock", "in/p.lock");
        Files.delete(hold);
        caller.join(10000);
        assertEquals(AtomicOutcome.EXISTS, ((AtomicResult) result.get()).getOutcome());
        verify(channel).hardlink("in/s.lock", "in/p.lock");
    }

    @Test
    public void publishPointComesAfterTheLinkAndBeforeTheRemovalOfTheTempName() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        final OneChannel fileSystem = new OneChannel(channel);
        final SftpFileObject temp = new SftpFileObject(name("/in/.part/a.tmp", FileType.FILE), fileSystem);
        final SftpFileObject target = new SftpFileObject(name("/in/t.bin", FileType.FILE), fileSystem);
        final String key = "sftp://localhost/in/t.bin";
        final Path hold = hold("publish.after-link", key);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = run(() -> {
            try {
                result.set(temp.publishExclusive(target, "p.part"));
            } catch (final Exception e) {
                result.set(e);
            }
        });
        waitFor("publish.after-link", key);
        verify(channel).hardlink("in/.part/a.tmp", "in/t.bin");
        verify(channel, never()).rm(anyString());
        Files.delete(hold);
        caller.join(10000);
        assertEquals(AtomicOutcome.DONE, ((AtomicResult) result.get()).getOutcome());
        verify(channel).rm("in/.part/a.tmp");
        verify(channel, never()).put(any(InputStream.class), anyString());
    }

    // An interrupt that comes while the caller waits at a point. The caller sends no further request.

    @Test
    public void interruptAfterTheRefusedLinkSendsNoLook() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final long[] after = new long[1];
        final Object result = interruptAt("link.after-refusal", "sftp://localhost/in/a.lock", channel,
                () -> file.createExclusive(new byte[] {1}, "s.lock", "p.lock"), after);
        // The refusal decides no outcome yet, so the call ends unclear with the scratch path.
        assertInterruptedBefore(result, MovePhase.COMMIT, "/in/s.lock");
        assertEquals(0, after[0]);
        verify(channel, never()).lstat(anyString());
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void interruptAfterTheLookSendsNoProbe() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat("in/a.lock");
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final long[] after = new long[1];
        final Object result = interruptAt("link.after-look", "sftp://localhost/in/a.lock", channel,
                () -> file.createExclusive(new byte[] {1}, "s.lock", "p.lock"), after);
        assertInterruptedBefore(result, MovePhase.COMMIT, "/in/s.lock");
        assertEquals(0, after[0]);
        verify(channel, never()).hardlink("in/s.lock", "in/p.lock");
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void interruptAfterALookThatDecidedKeepsTheOutcomeAndLeavesTheScratch() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        when(channel.lstat("in/a.lock")).thenReturn(mock(SftpATTRS.class));
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final long[] after = new long[1];
        final Object result = interruptAt("link.after-look", "sftp://localhost/in/a.lock", channel,
                () -> file.createExclusive(new byte[] {1}, "s.lock", "p.lock"), after);
        final AtomicResult decided = (AtomicResult) result;
        assertEquals(AtomicOutcome.EXISTS, decided.getOutcome());
        assertEquals(Collections.singletonList("in/s.lock"), decided.getLeftovers());
        assertTrue(decided.isConnectionLost());
        assertEquals(0, after[0]);
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void interruptAfterARefusedLookKeepsTheRefusal() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        doThrow(reply(4)).when(channel).hardlink("in/s.lock", "in/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).lstat("in/a.lock");
        final SftpFileObject file = new SftpFileObject(name("/in/a.lock", FileType.FILE), new OneChannel(channel));
        final long[] after = new long[1];
        final Object result = interruptAt("link.after-look", "sftp://localhost/in/a.lock", channel,
                () -> file.createExclusive(new byte[] {1}, "s.lock", "p.lock"), after);
        final AtomicResult refused = (AtomicResult) result;
        assertEquals(AtomicOutcome.REFUSED, refused.getOutcome());
        assertEquals(Collections.singletonList("in/s.lock"), refused.getLeftovers());
        assertTrue(refused.isConnectionLost());
        assertEquals(0, after[0]);
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void interruptAfterThePublishedLinkKeepsDoneAndSendsNoRemoval() throws Exception {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn("1");
        final OneChannel fileSystem = new OneChannel(channel);
        final SftpFileObject temp = new SftpFileObject(name("/in/.part/a.tmp", FileType.FILE), fileSystem);
        final SftpFileObject target = new SftpFileObject(name("/in/t.bin", FileType.FILE), fileSystem);
        final long[] after = new long[1];
        final Object result = interruptAt("publish.after-link", "sftp://localhost/in/t.bin", channel,
                () -> temp.publishExclusive(target, "p.part"), after);
        final AtomicResult done = (AtomicResult) result;
        assertEquals(AtomicOutcome.DONE, done.getOutcome());
        assertEquals(Collections.singletonList("in/.part/a.tmp"), done.getLeftovers());
        assertTrue(done.isConnectionLost());
        assertEquals(0, after[0]);
        verify(channel).hardlink("in/.part/a.tmp", "in/t.bin");
        verify(channel, never()).rm(anyString());
    }
}
