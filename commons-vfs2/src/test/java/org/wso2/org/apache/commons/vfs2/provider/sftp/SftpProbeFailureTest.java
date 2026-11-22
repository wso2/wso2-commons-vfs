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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;

/**
 * How the permission check tells a failure of the session from an answer of the server. A wrapped I/O failure, or a
 * channel that ended with no exit status after the session went away, is a failure of the session: the session is
 * discarded, the error goes to the caller, and the state stays not known. So is any other failure that JSch does not
 * give for a refused or unanswered open or request, such as the end of the wait for a key exchange. A refusal or no
 * answer in time on a session that is up is an answer: exec counts as disabled, with one warning, and the session
 * stays in use.
 */
public class SftpProbeFailureTest {

    private static final GenericFileName ROOT = new GenericFileName("sftp", "localhost", 22, 22, "user", "pass", "/",
            FileType.FOLDER) {
    };

    private final Session session = mock(Session.class);

    private final SocketGroup group = new SocketGroup();

    private final SftpFileSystem fileSystem = new SftpFileSystem(ROOT, session, group, new FileSystemOptions()) {
    };

    private ChannelExec exec(final String reply, final int status) throws Exception {
        final ChannelExec channel = mock(ChannelExec.class);
        when(channel.getInputStream()).thenReturn(new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)));
        when(channel.isClosed()).thenReturn(true);
        when(channel.getExitStatus()).thenReturn(status);
        return channel;
    }

    private ChannelExec failingConnect(final JSchException error) throws Exception {
        final ChannelExec channel = exec("", -1);
        doThrow(error).when(channel).connect(anyInt());
        return channel;
    }

    private String state() throws Exception {
        final Field field = SftpFileSystem.class.getDeclaredField("execState");
        field.setAccessible(true);
        return String.valueOf(field.get(fileSystem));
    }

    private static List<String> probeWarnings(final LogCapture log) {
        return log.messages(Level.WARN).stream().filter(m -> m.contains("the permission check over an exec channel"))
            .collect(Collectors.toList());
    }

    private void assertSessionFailure(final Class<? extends Exception> type, final LogCapture log) throws Exception {
        assertThrows(type, fileSystem::isExecDisabledForCheck);
        assertTrue(group.isAborted());
        verify(session).disconnect();
        assertEquals("NOT_KNOWN", state());
        assertTrue(probeWarnings(log).isEmpty());
    }

    private void assertAnswer(final LogCapture log) throws Exception {
        assertTrue(fileSystem.isExecDisabledForCheck());
        assertFalse(group.isAborted());
        verify(session, never()).disconnect();
        assertEquals("DISABLED", state());
        assertEquals(1, probeWarnings(log).size(), log.messages(Level.WARN).toString());
    }

    @Test
    public void sessionThatIsGoneBetweenTheUidAndTheGroupsIsASessionFailureAndTheUidStays() throws Exception {
        // Up for the uid, gone when the groups start. The check makes no new session.
        when(session.isConnected()).thenReturn(true, false);
        final ChannelExec uid = exec("4242\n", 0);
        when(session.openChannel("exec")).thenReturn(uid);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            final JSchException error = assertThrows(JSchException.class, fileSystem::isExecDisabledForCheck);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertTrue(group.isAborted());
            verify(session).disconnect();
            verify(session, times(1)).openChannel("exec");
            assertEquals("NOT_KNOWN", state());
            assertTrue(probeWarnings(log).isEmpty());
        }
        assertEquals(4242, fileSystem.getUIdForCheck());
    }

    // A reply that comes with the close of its channel at the worst moment: after the reader found the pipe empty and
    // before it looks at the close. JSch puts the reply into the pipe before it marks the channel closed.
    private static ChannelExec replyWithTheClose(final String reply) throws Exception {
        final byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean delivered = new AtomicBoolean();
        final InputStream stream = new InputStream() {
            private int position;

            @Override
            public int available() {
                if (delivered.compareAndSet(false, true)) {
                    return 0;
                }
                return bytes.length - position;
            }

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) {
                final int count = Math.min(length, bytes.length - position);
                if (count <= 0) {
                    return -1;
                }
                System.arraycopy(bytes, position, buffer, offset, count);
                position += count;
                return count;
            }
        };
        final ChannelExec channel = mock(ChannelExec.class);
        when(channel.getInputStream()).thenReturn(stream);
        when(channel.isClosed()).thenAnswer(invocation -> delivered.get());
        when(channel.getExitStatus()).thenReturn(0);
        return channel;
    }

    // A file of uid 99 with the given group and mode, as the stat of the permission check reads it.
    private void statGives(final int gid, final int mode) throws Exception {
        final SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.getFlags()).thenReturn(SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS | SftpATTRS.SSH_FILEXFER_ATTR_UIDGID);
        when(attrs.isDir()).thenReturn(false);
        when(attrs.getPermissions()).thenReturn(0100000 | mode);
        when(attrs.getUId()).thenReturn(99);
        when(attrs.getGId()).thenReturn(gid);
        final ChannelSftp sftp = mock(ChannelSftp.class);
        when(sftp.stat(anyString())).thenReturn(attrs);
        when(session.openChannel("sftp")).thenReturn(sftp);
    }

    private static final GenericFileName FILE = new GenericFileName("sftp", "localhost", 22, 22, "user", "pass",
            "/in/a.txt", FileType.FILE) {
    };

    private void assertTwoChecks(final boolean writable) throws Exception {
        final SftpFileObject file = new SftpFileObject(FILE, fileSystem);
        assertEquals(writable, file.doIsWriteable());
        assertEquals(writable, file.doIsWriteable());
        assertFalse(fileSystem.isExecDisabledForCheck());
        assertEquals(12345, fileSystem.getUIdForCheck());
        assertArrayEquals(new int[] {23456, 34567}, fileSystem.getGroupsIdsForCheck());
        // The answers are cached, so the second check sent no command.
        verify(session, times(2)).openChannel("exec");
    }

    // A reply that is whole in the pipe of a closed channel, read by a caller that is interrupted while it reads.
    private static ChannelExec readyReplyAndAnInterrupt(final String reply) throws Exception {
        final byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        final InputStream stream = new InputStream() {
            private int position;

            @Override
            public int available() {
                // The interrupt comes while the reply waits, all of it, in the pipe.
                Thread.currentThread().interrupt();
                return bytes.length - position;
            }

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) {
                final int count = Math.min(length, bytes.length - position);
                if (count <= 0) {
                    return -1;
                }
                System.arraycopy(bytes, position, buffer, offset, count);
                position += count;
                return count;
            }
        };
        final ChannelExec channel = mock(ChannelExec.class);
        when(channel.getInputStream()).thenReturn(stream);
        when(channel.isClosed()).thenReturn(true);
        when(channel.getExitStatus()).thenReturn(0);
        return channel;
    }

    private Throwable interruptedCheck() {
        try {
            fileSystem.isExecDisabledForCheck();
            return null;
        } catch (final Exception e) {
            return e;
        } finally {
            assertTrue(Thread.interrupted(), "the caller keeps its interrupt");
        }
    }

    private void assertInterruptEndsTheSession(final Throwable error) throws Exception {
        assertTrue(error instanceof InterruptedIOException, String.valueOf(error));
        assertEquals(Fault.TRANSPORT, Faults.of(error));
        assertTrue(group.isAborted());
        verify(session).disconnect();
        assertEquals("NOT_KNOWN", state());
    }

    @Test
    public void interruptBeforeTheCheckStartsEndsTheSessionKeepsATakenUidAndTheNextCheckAsksOnlyForTheGroups()
            throws Exception {
        when(session.isConnected()).thenReturn(true);
        final Field uidField = SftpFileSystem.class.getDeclaredField("uid");
        uidField.setAccessible(true);
        uidField.setInt(fileSystem, 12345);
        Thread.currentThread().interrupt();
        assertInterruptEndsTheSession(interruptedCheck());
        verify(session, never()).openChannel(anyString());
        assertEquals(12345, uidField.getInt(fileSystem), "the uid that was taken stays");
        final ChannelExec groups = exec("23456 34567\n", 0);
        when(session.openChannel("exec")).thenReturn(groups);
        assertFalse(fileSystem.isExecDisabledForCheck());
        assertArrayEquals(new int[] {23456, 34567}, fileSystem.getGroupsIdsForCheck());
        verify(session, times(1)).openChannel("exec");
    }

    @Test
    public void interruptBeforeTheHandOffToJschEndsTheSessionAndDecidesNothing() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec uid = exec("12345\n", 0);
        when(session.openChannel("exec")).thenAnswer(invocation -> {
            // The interrupt comes after the check started, before the channel goes to JSch.
            Thread.currentThread().interrupt();
            return uid;
        });
        assertInterruptEndsTheSession(interruptedCheck());
        verify(uid, never()).connect(anyInt());
        final Field uidField = SftpFileSystem.class.getDeclaredField("uid");
        uidField.setAccessible(true);
        assertEquals(-1, uidField.getInt(fileSystem), "no uid was taken");
    }

    @Test
    public void interruptWhileTheReplyOfTheUidIsReadyEndsTheSessionAndTheNextCheckWorks() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec uid = readyReplyAndAnInterrupt("12345\n");
        final ChannelExec uidAgain = exec("12345\n", 0);
        final ChannelExec groups = exec("23456 34567\n", 0);
        when(session.openChannel("exec")).thenReturn(uid, uidAgain, groups);
        assertInterruptEndsTheSession(interruptedCheck());
        // No answer of the interrupted command was kept. The next check probes again.
        assertFalse(fileSystem.isExecDisabledForCheck());
        assertEquals(12345, fileSystem.getUIdForCheck());
        assertArrayEquals(new int[] {23456, 34567}, fileSystem.getGroupsIdsForCheck());
        verify(session, times(3)).openChannel("exec");
    }

    @Test
    public void interruptWhileTheReplyOfTheGroupsIsReadyKeepsTheUidAndTheNextCheckWorks() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec uid = exec("12345\n", 0);
        final ChannelExec groups = readyReplyAndAnInterrupt("23456 34567\n");
        final ChannelExec groupsAgain = exec("23456 34567\n", 0);
        when(session.openChannel("exec")).thenReturn(uid, groups, groupsAgain);
        assertInterruptEndsTheSession(interruptedCheck());
        assertEquals(12345, fileSystem.getUIdForCheck());
        assertFalse(fileSystem.isExecDisabledForCheck());
        assertArrayEquals(new int[] {23456, 34567}, fileSystem.getGroupsIdsForCheck());
        verify(session, times(3)).openChannel("exec");
    }

    @Test
    public void interruptedPauseKeepsItsCauseAndTheInterrupt() throws Exception {
        final Method pause = SftpFileSystem.class.getDeclaredMethod("pause", String.class);
        pause.setAccessible(true);
        Thread.currentThread().interrupt();
        try {
            pause.invoke(null, "id -u");
            fail("the pause ended");
        } catch (final InvocationTargetException e) {
            assertTrue(e.getCause() instanceof InterruptedIOException, String.valueOf(e.getCause()));
            assertTrue(e.getCause().getCause() instanceof InterruptedException, String.valueOf(e.getCause().getCause()));
        } finally {
            assertTrue(Thread.interrupted(), "the caller keeps its interrupt");
        }
    }

    @Test
    public void uidThatComesWithTheCloseOfItsChannelIsRead() throws Exception {
        when(session.isConnected()).thenReturn(true);
        // Neither the owner nor in the group, so the other bits decide: not writable.
        statGives(0, 0600);
        final ChannelExec uid = replyWithTheClose("12345\n");
        final ChannelExec groups = exec("23456 34567\n", 0);
        when(session.openChannel("exec")).thenReturn(uid, groups);
        assertTwoChecks(false);
    }

    @Test
    public void groupsThatComeWithTheCloseOfTheirChannelAreRead() throws Exception {
        when(session.isConnected()).thenReturn(true);
        // In the group by the second group id, so the group bits decide: writable.
        statGives(34567, 0060);
        final ChannelExec uid = exec("12345\n", 0);
        final ChannelExec groups = replyWithTheClose("23456 34567\n");
        when(session.openChannel("exec")).thenReturn(uid, groups);
        assertTwoChecks(true);
    }

    @Test
    public void wrappedFailureAtConnectWhileTheSessionLooksUpIsASessionFailure() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("ChannelExec", new IOException("Pipe closed")));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(JSchException.class, log);
        }
    }

    @Test
    public void failureAtConnectAfterTheSessionWentAwayIsASessionFailure() throws Exception {
        // Up for the check before the command, gone when the connect fails.
        when(session.isConnected()).thenReturn(true, false);
        final ChannelExec channel = failingConnect(new JSchException("channel is not opened."));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(JSchException.class, log);
        }
    }

    @Test
    public void refusedRequestIsAnAnswer() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("failed to send channel request"));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertAnswer(log);
        }
    }

    @Test
    public void requestWithNoReplyInTimeIsAnAnswer() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("channel request: timeout"));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertAnswer(log);
        }
    }

    @Test
    public void refusedOpenIsAnAnswer() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("channel is not opened."));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertAnswer(log);
        }
    }

    @Test
    public void endOfTheWaitForAKeyExchangeIsASessionFailure() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("timeout in waiting for rekeying process."));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(JSchException.class, log);
        }
    }

    @Test
    public void otherFailureWithNoCauseOnASessionThatLooksUpIsASessionFailure() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = failingConnect(new JSchException("session is down"));
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(JSchException.class, log);
        }
    }

    @Test
    public void channelThatEndedWithNoExitStatusAfterTheSessionWentAwayIsASessionFailure() throws Exception {
        // Up for the check before the command, gone when the channel ended.
        when(session.isConnected()).thenReturn(true, false);
        final ChannelExec channel = exec("", -1);
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(IOException.class, log);
        }
    }

    @Test
    public void channelThatEndedWithNoExitStatusOnASessionThatIsUpIsAnAnswer() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec channel = exec("", -1);
        when(session.openChannel("exec")).thenReturn(channel);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertAnswer(log);
        }
    }

    @Test
    public void wrappedFailureInTheGroupsKeepsTheUidAndTheStateNotKnown() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec uid = exec("4242\n", 0);
        final ChannelExec groups = failingConnect(new JSchException("ChannelExec", new IOException("Pipe closed")));
        when(session.openChannel("exec")).thenReturn(uid, groups);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertSessionFailure(JSchException.class, log);
        }
        assertEquals(4242, fileSystem.getUId());
    }

    @Test
    public void refusedGroupsDisableExec() throws Exception {
        when(session.isConnected()).thenReturn(true);
        final ChannelExec uid = exec("4242\n", 0);
        final ChannelExec groups = failingConnect(new JSchException("failed to send channel request"));
        when(session.openChannel("exec")).thenReturn(uid, groups);
        try (LogCapture log = new LogCapture(SftpFileSystem.class)) {
            assertAnswer(log);
        }
    }
}
