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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.local.CapabilityClockTest;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InOrder;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.FileNameParser;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.URLFileName;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

/**
 * The calls of AtomicOps on SFTP, with a channel double. One test per row of the link routine, of
 * the replace test, of the take away, of the looks and of the memory of the file system.
 */
public class SftpAtomicOpsTest {

    // Each test has folders of its own, because the WARN of a folder is given once per copy of the fork.
    private final String in = "in" + UUID.randomUUID().toString().substring(0, 8);

    private final String out = "out" + UUID.randomUUID().toString().substring(0, 8);

    private static GenericFileName name(final String path, final FileType type) {
        return new GenericFileName("sftp", "localhost", 22, 22, "user", "pass", path, type) {
        };
    }

    private static final GenericFileName ROOT = name("/", FileType.FOLDER);

    /** A file system whose channels come from a queue, and which records each give back and each discard. */
    private static final class Channels extends SftpFileSystem {

        private final Deque<ChannelSftp> queue = new ArrayDeque<>();

        private final List<ChannelSftp> putBack = new ArrayList<>();

        private final List<ChannelSftp> discarded = new ArrayList<>();

        // When true, the caller is interrupted while it takes its channel.
        private boolean interruptOnTake;

        Channels(final ChannelSftp... channels) {
            super(ROOT, connectedSession(), new SocketGroup(), new FileSystemOptions());
            queue.addAll(Arrays.asList(channels));
        }

        @Override
        protected ChannelSftp getChannel() {
            if (interruptOnTake) {
                Thread.currentThread().interrupt();
            }
            return queue.remove();
        }

        @Override
        protected void releaseChannel(final ChannelSftp channel, final Throwable error) {
            if (isReplyOrSuccess(error) && !Thread.currentThread().isInterrupted()) {
                putBack.add(channel);
            } else {
                discarded.add(channel);
            }
        }

        @Override
        protected void discardChannel(final ChannelSftp channel) {
            discarded.add(channel);
        }

        void announced(final Boolean hardlink, final Boolean posixRename) throws Exception {
            set("hardlinkAnnounced", hardlink);
            set("posixRenameAnnounced", posixRename);
        }

        private void set(final String field, final Object value) throws Exception {
            final Field declared = SftpFileSystem.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(this, value);
        }
    }

    private static Session connectedSession() {
        final Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        return session;
    }

    private static ChannelSftp channel(final boolean hardlink, final boolean posixRename) {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.getExtension(SftpFileSystem.HARDLINK)).thenReturn(hardlink ? "1" : null);
        when(channel.getExtension(SftpFileSystem.POSIX_RENAME)).thenReturn(posixRename ? "1" : null);
        return channel;
    }

    private static SftpException reply(final int id) {
        return new SftpException(id, "reply " + id);
    }

    private static SftpException broken() {
        return new SftpException(ChannelSftp.SSH_FX_FAILURE, "broken", new IOException("the connection is gone"));
    }

    private static SftpATTRS attrs(final boolean folder, final long size, final int mtime) {
        final SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.isDir()).thenReturn(folder);
        when(attrs.getSize()).thenReturn(size);
        when(attrs.getMTime()).thenReturn(mtime);
        return attrs;
    }

    private SftpFileObject file(final Channels fileSystem, final String folder, final String base)
            throws FileSystemException {
        return new SftpFileObject(name("/" + folder + "/" + base, FileType.FILE), fileSystem);
    }

    // An unconfirmed change throws UnclearResultException with the phase and the temp path of the public call. It
    // keeps the original cause, has the fault TRANSPORT and no deleted old target.
    private static UnclearResultException unclear(final MovePhase phase, final String tempPath, final Throwable cause,
            final Executable call) {
        final UnclearResultException error = assertThrows(UnclearResultException.class, call);
        assertEquals(phase, error.getPhase());
        assertEquals(tempPath, error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertFalse(error.isCommitConfirmed());
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(cause, error.getCause());
        return error;
    }

    @Test
    public void quotesTheWholePathForJsch() {
        assertEquals("in\\*/a\\\\b\\?.csv", SftpFileSystem.quote("in*/a\\b?.csv"));
        assertEquals("in/name.csv", SftpFileSystem.quote("in/name.csv"));
    }

    @Test
    public void encodedBackslashStaysLiteralAndRawSeparatorsStillSeparateFolders() throws Exception {
        final FileNameParser parser = SftpFileNameParser.getInstance();
        final URLFileName literal = (URLFileName) parser.parseUri(null, null, "sftp://host/in/a%5Cb%3F%25?x=1");
        assertEquals("/in/a\\b?%", literal.getPathDecoded());
        final URLFileName reparsed = (URLFileName) parser.parseUri(null, null, literal.getURI());
        assertEquals(literal.getPathDecoded(), reparsed.getPathDecoded());
        assertEquals("x=1", reparsed.getQueryString());
        assertEquals("x=1", literal.getQueryString());
        assertEquals("/in/a/b", parser.parseUri(null, null, "sftp://host/in/a\\b").getPath());
        assertEquals("/in/a%5Cb", parser.parseUri(null, null, "sftp://host/in/a%255Cb").getPathDecoded());
    }

    @AfterEach
    public void clearInterrupt() {
        Thread.interrupted();
    }

    // The memory of the file system, before any call.

    @Test
    public void beforeTheFirstChannelEveryValueIsNotKnown() throws Exception {
        final Channels fileSystem = new Channels();
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void serverWithTheExtensionsIsNotKnownForALinkAndStatesTheReplace() throws Exception {
        final Channels fileSystem = new Channels();
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.YES, lock.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void serverWithNoHardLinkSaysNoAndTheReplaceFollowsTheFolderState() throws Exception {
        final Channels fileSystem = new Channels();
        fileSystem.announced(false, false);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void rootIsItsOwnFolderInBothQueryFormsAndTakesNoChannel() throws Exception {
        final Channels fileSystem = new Channels();
        fileSystem.announced(true, false);
        final SftpFileObject root = new SftpFileObject(ROOT, fileSystem);
        final SftpFileObject file = file(fileSystem, in, "a.csv");
        assertEquals(Answer.NOT_KNOWN, root.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NOT_KNOWN, root.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, root.atomicCapabilities().getReplacingRename());
        // A folder can not be hard linked.
        assertEquals(Answer.NO, root.atomicCapabilities(file).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, root.atomicCapabilities(file).getReplacingRename());
        assertEquals(Answer.NOT_KNOWN, file.atomicCapabilities(root).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, file.atomicCapabilities(root).getReplacingRename());
        // The queue is empty, so a channel taken by a query would fail the test.
        assertTrue(fileSystem.putBack.isEmpty());
        assertTrue(fileSystem.discarded.isEmpty());
    }

    // The link routine, through createExclusive.

    @Test
    public void serverWithNoHardLinkGetsNoScratchFileAndNoCall() throws Exception {
        final ChannelSftp channel = channel(false, false);
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.UNSUPPORTED, result.getOutcome());
        verify(channel, never()).put(any(InputStream.class), anyString());
        verify(channel, never()).hardlink(anyString(), anyString());
        assertEquals(1, fileSystem.putBack.size());
    }

    @Test
    public void linkThatReturnsIsDoneAndRemovesTheScratchName() throws Exception {
        final ChannelSftp channel = channel(true, true);
        final Channels fileSystem = new Channels(channel);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        final AtomicResult result = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        assertTrue(result.getLeftovers().isEmpty());
        final InOrder order = inOrder(channel);
        order.verify(channel).put(any(InputStream.class), eq(in + "/s.lock"));
        order.verify(channel).hardlink(in + "/s.lock", in + "/a.lock");
        order.verify(channel).rm(in + "/s.lock");
        verify(channel, never()).lstat(anyString());
        fileSystem.announced(true, true);
        assertEquals(Answer.YES, lock.atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void refusedLinkWithTheNameThereIsExistsWithNoProbe() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doReturn(attrs(false, 1, 1)).when(channel).lstat(in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.EXISTS, result.getOutcome());
        verify(channel, never()).hardlink(in + "/s.lock", in + "/p.lock");
        verify(channel).rm(in + "/s.lock");
    }

    @Test
    public void releasedBetweenTheLinkAndTheLookInAFolderThatLinkedIsExistsWithNoProbe() throws Exception {
        final ChannelSftp first = channel(true, true);
        final ChannelSftp second = channel(true, true);
        doThrow(reply(11)).when(second).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(second).lstat(in + "/a.lock");
        final Channels fileSystem = new Channels(first, second);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(AtomicOutcome.DONE, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        assertEquals(AtomicOutcome.EXISTS, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        verify(second, never()).hardlink(in + "/s.lock", in + "/p.lock");
    }

    @Test
    public void probeThatWorksIsExistsAndMarksTheFolderLinks() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        final AtomicResult result = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.EXISTS, result.getOutcome());
        verify(channel).hardlink(in + "/s.lock", in + "/p.lock");
        verify(channel).rm(in + "/p.lock");
        verify(channel).rm(in + "/s.lock");
        assertEquals(Answer.YES, lock.atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void probeThatIsRefusedDemotesTheFolderAndTheNextCallSendsNothing() throws Exception {
        final ChannelSftp first = channel(true, true);
        doThrow(reply(4)).when(first).hardlink(anyString(), anyString());
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(first).lstat(in + "/a.lock");
        final ChannelSftp second = channel(true, true);
        final Channels fileSystem = new Channels(first, second);
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(AtomicOutcome.UNSUPPORTED, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(AtomicOutcome.UNSUPPORTED, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        verify(second, never()).put(any(InputStream.class), anyString());
        verify(second, never()).hardlink(anyString(), anyString());
        // The demotion is of this folder only.
        assertEquals(Answer.NOT_KNOWN, file(fileSystem, out, "a.lock").atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void probeThatSaysNoSuchFileIsGoneAndDecidesNothing() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).hardlink(in + "/s.lock", in + "/p.lock");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(AtomicOutcome.GONE, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void linkThatSaysNoSuchFileIsGoneWithNoLook() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.GONE,
                file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        verify(channel, never()).lstat(anyString());
    }

    @Test
    public void lookThatIsRefusedIsRefused() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(3)).when(channel).lstat(in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.REFUSED,
                file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        verify(channel, never()).hardlink(in + "/s.lock", in + "/p.lock");
    }

    @Test
    public void linkWithACauseDiscardsTheChannelAndMakesNoFurtherCall() throws Exception {
        final ChannelSftp channel = channel(true, true);
        final SftpException cause = broken();
        doThrow(cause).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        // The link may have worked. The call throws UnclearResultException in the phase COMMIT with the scratch path,
        // and the scratch name is not removed.
        unclear(MovePhase.COMMIT, "/" + in + "/s.lock", cause,
                () -> file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock"));
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
        assertTrue(fileSystem.putBack.isEmpty());
        verify(channel, never()).lstat(anyString());
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void lookWithACauseDiscardsTheChannelAndDecidesNothing() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        final SftpException cause = broken();
        doThrow(cause).when(channel).lstat(in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        unclear(MovePhase.COMMIT, "/" + in + "/s.lock", cause,
                () -> lock.createExclusive(new byte[] {1}, "s.lock", "p.lock"));
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
        verify(channel, never()).hardlink(in + "/s.lock", in + "/p.lock");
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void probeWithACauseDoesNotDemoteTheFolder() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(in + "/a.lock");
        final SftpException cause = broken();
        doThrow(cause).when(channel).hardlink(in + "/s.lock", in + "/p.lock");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, true);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        unclear(MovePhase.COMMIT, "/" + in + "/s.lock", cause,
                () -> lock.createExclusive(new byte[] {1}, "s.lock", "p.lock"));
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getExclusiveCreate());
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void refusalOnAnInterruptedThreadIsNotReadAsARefusal() throws Exception {
        final ChannelSftp channel = channel(true, true);
        final SftpException refusal = reply(4);
        doAnswer(call -> {
            Thread.currentThread().interrupt();
            throw refusal;
        }).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        final Channels fileSystem = new Channels(channel);
        unclear(MovePhase.COMMIT, "/" + in + "/s.lock", refusal,
                () -> file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock"));
        assertTrue(Thread.interrupted());
        verify(channel, never()).lstat(anyString());
        verify(channel, never()).rm(anyString());
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
    }

    // A call made on an interrupted thread, or interrupted while it takes its channel, sends nothing. It ends with a
    // plain error with the fault of an interrupt, and the flag stays set.

    private interface Mutation {
        void run(SftpFileObject source, SftpFileObject target) throws Exception;
    }

    private static final Mutation[] MUTATIONS = {
        (source, target) -> source.createExclusive(new byte[] {1}, "s.lock", "p.lock"),
        (source, target) -> source.linkExclusive(target, "p.part"),
        (source, target) -> source.publishExclusive(target, "p.part"),
        (source, target) -> source.renameReplacing(target, null, null),
        (source, target) -> source.takeAway(target)};

    private static void verifyNothingSent(final ChannelSftp channel) throws Exception {
        verify(channel, never()).put(any(InputStream.class), anyString());
        verify(channel, never()).hardlink(anyString(), anyString());
        verify(channel, never()).rename(anyString(), anyString());
        verify(channel, never()).rm(anyString());
        verify(channel, never()).lstat(anyString());
    }

    private static void assertNotSent(final FileSystemException error) {
        assertFalse(error instanceof UnclearResultException);
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertTrue(error.getCause() instanceof InterruptedIOException);
        assertTrue(Thread.interrupted());
    }

    @Test
    public void interruptBeforeEntryTakesNoChannelAndSendsNothing() throws Exception {
        for (final Mutation mutation : MUTATIONS) {
            final ChannelSftp channel = channel(true, false);
            final Channels fileSystem = new Channels(channel);
            final SftpFileObject source = file(fileSystem, in, "a.tmp");
            final SftpFileObject target = file(fileSystem, out, "a.csv");
            Thread.currentThread().interrupt();
            assertNotSent(assertThrows(FileSystemException.class, () -> mutation.run(source, target)));
            verifyNothingSent(channel);
            assertEquals(1, fileSystem.queue.size());
        }
    }

    @Test
    public void interruptWhileTheChannelIsTakenSendsNothingAndDiscardsIt() throws Exception {
        for (final Mutation mutation : MUTATIONS) {
            final ChannelSftp channel = channel(true, false);
            final Channels fileSystem = new Channels(channel);
            fileSystem.interruptOnTake = true;
            final SftpFileObject source = file(fileSystem, in, "a.tmp");
            final SftpFileObject target = file(fileSystem, out, "a.csv");
            assertNotSent(assertThrows(FileSystemException.class, () -> mutation.run(source, target)));
            verifyNothingSent(channel);
            assertEquals(Arrays.asList(channel), fileSystem.discarded);
            assertTrue(fileSystem.putBack.isEmpty());
        }
    }

    @Test
    public void cancelThatJschClearedStaysOnTheThread() throws Exception {
        final ChannelSftp cancelled = channel(true, false);
        final SftpException cancel = new SftpException(ChannelSftp.SSH_FX_FAILURE, "cancelled",
                new InterruptedIOException("the read was interrupted"));
        doThrow(cancel).when(cancelled).rename(anyString(), anyString());
        final ChannelSftp timedOut = channel(true, false);
        final SftpException timeout = new SftpException(ChannelSftp.SSH_FX_FAILURE, "timeout",
                new SocketTimeoutException("read timed out"));
        doThrow(timeout).when(timedOut).rename(anyString(), anyString());
        final Channels fileSystem = new Channels(cancelled, timedOut);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        final SftpFileObject away = file(fileSystem, in, "a.x1.lock");
        // The rename was sent, so its result is not known. The cancel is put back on the thread.
        unclear(MovePhase.RENAME, null, cancel, () -> lock.takeAway(away));
        assertTrue(Thread.interrupted());
        // A timeout of a socket is no cancel.
        unclear(MovePhase.RENAME, null, timeout, () -> lock.takeAway(away));
        assertFalse(Thread.interrupted());
    }

    @Test
    public void probeWhoseRemovalLosesTheConnectionKeepsTheOutcomeWithTwoLeftovers() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/s.lock", in + "/a.lock");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(in + "/a.lock");
        doThrow(broken()).when(channel).rm(in + "/p.lock");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.EXISTS, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertEquals(Arrays.asList(in + "/p.lock", in + "/s.lock"), result.getLeftovers());
        verify(channel, never()).rm(in + "/s.lock");
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
    }

    @Test
    public void scratchWriteRefusedIsRefusedAndWithACauseThrows() throws Exception {
        final ChannelSftp refused = channel(true, true);
        doThrow(reply(4)).when(refused).put(any(InputStream.class), anyString());
        final ChannelSftp cut = channel(true, true);
        final SftpException cause = broken();
        doThrow(cause).when(cut).put(any(InputStream.class), anyString());
        final Channels fileSystem = new Channels(refused, cut);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        assertEquals(AtomicOutcome.REFUSED, lock.createExclusive(new byte[] {1}, "s.lock", "p.lock").getOutcome());
        verify(refused, never()).hardlink(anyString(), anyString());
        unclear(MovePhase.COMMIT, "/" + in + "/s.lock", cause,
                () -> lock.createExclusive(new byte[] {1}, "s.lock", "p.lock"));
        verify(cut, never()).rm(anyString());
        assertEquals(Arrays.asList(cut), fileSystem.discarded);
    }

    // A refused put of the scratch file. The server may have made the scratch name before it refused the write.

    @Test
    public void scratchOpenRefusedFindsNoScratchAndIsClean() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).put(any(InputStream.class), anyString());
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).rm(in + "/s.lock");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.getLeftovers().isEmpty());
        assertFalse(result.isConnectionLost());
        verify(channel).rm(in + "/s.lock");
        verify(channel, never()).rm(in + "/a.lock");
        verify(channel, never()).hardlink(anyString(), anyString());
        assertEquals(Arrays.asList(channel), fileSystem.putBack);
    }

    @Test
    public void scratchWriteRefusedAfterCreationRemovesTheScratch() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).put(any(InputStream.class), anyString());
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.getLeftovers().isEmpty());
        final InOrder order = inOrder(channel);
        order.verify(channel).put(any(InputStream.class), eq(in + "/s.lock"));
        order.verify(channel).rm(in + "/s.lock");
        verify(channel, never()).rm(in + "/a.lock");
        verify(channel, never()).hardlink(anyString(), anyString());
    }

    @Test
    public void scratchRemovalRefusedAfterARefusedWriteIsALeftover() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).put(any(InputStream.class), anyString());
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).rm(in + "/s.lock");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertEquals(Arrays.asList(in + "/s.lock"), result.getLeftovers());
        assertFalse(result.isConnectionLost());
        assertEquals(Arrays.asList(channel), fileSystem.putBack);
    }

    @Test
    public void scratchRemovalThatLosesTheConnectionKeepsTheRefusal() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(channel).put(any(InputStream.class), anyString());
        doThrow(broken()).when(channel).rm(in + "/s.lock");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.lock").createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertEquals("id 3 reply 3", result.getDetail());
        assertEquals(Arrays.asList(in + "/s.lock"), result.getLeftovers());
        assertTrue(result.isConnectionLost());
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
        assertTrue(fileSystem.putBack.isEmpty());
    }

    @Test
    public void scratchThatCanNotBeRemovedIsALeftover() throws Exception {
        final ChannelSftp refused = channel(true, true);
        doThrow(reply(3)).when(refused).rm(in + "/s.lock");
        final ChannelSftp cut = channel(true, true);
        doThrow(broken()).when(cut).rm(in + "/s.lock");
        final Channels fileSystem = new Channels(refused, cut);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        final AtomicResult first = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.DONE, first.getOutcome());
        assertEquals(Arrays.asList(in + "/s.lock"), first.getLeftovers());
        assertFalse(first.isConnectionLost());
        final AtomicResult second = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.DONE, second.getOutcome());
        assertEquals(Arrays.asList(in + "/s.lock"), second.getLeftovers());
        assertTrue(second.isConnectionLost());
        assertEquals(Arrays.asList(cut), fileSystem.discarded);
    }

    @Test
    public void cancelSeenInTheScratchCleanupStaysOnTheThread() throws Exception {
        final ChannelSftp cancelled = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(cancelled).put(any(InputStream.class), anyString());
        doThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "cancelled", new InterruptedIOException("interrupted")))
                .when(cancelled).rm(in + "/s.lock");
        final ChannelSftp timedOut = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(timedOut).put(any(InputStream.class), anyString());
        doThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "timeout", new SocketTimeoutException("timed out")))
                .when(timedOut).rm(in + "/s.lock");
        final Channels fileSystem = new Channels(cancelled, timedOut);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        // The refusal decided. The cleanup was cut by a cancel, so the scratch name is a leftover and the flag is set.
        final AtomicResult first = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, first.getOutcome());
        assertEquals(Arrays.asList(in + "/s.lock"), first.getLeftovers());
        assertTrue(first.isConnectionLost());
        assertTrue(Thread.interrupted());
        // A timeout of a socket is no cancel.
        final AtomicResult second = lock.createExclusive(new byte[] {1}, "s.lock", "p.lock");
        assertEquals(AtomicOutcome.REFUSED, second.getOutcome());
        assertTrue(second.isConnectionLost());
        assertFalse(Thread.interrupted());
        verify(cancelled, never()).rm(in + "/a.lock");
        assertEquals(Arrays.asList(cancelled, timedOut), fileSystem.discarded);
    }

    // linkExclusive and publishExclusive.

    @Test
    public void linkExclusiveLeavesTheSourceAndMarksThePairOnly() throws Exception {
        final ChannelSftp channel = channel(true, true);
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, true);
        final SftpFileObject source = file(fileSystem, in, "a.csv");
        final SftpFileObject target = file(fileSystem, out, "a.csv");
        final AtomicResult result = source.linkExclusive(target, "p.part");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        verify(channel).hardlink(in + "/a.csv", out + "/a.csv");
        verify(channel, never()).rm(anyString());
        assertEquals(Answer.YES, source.atomicCapabilities(target).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, target.atomicCapabilities(source).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, source.atomicCapabilities().getExclusivePublish());
        final SftpFileObject folder = new SftpFileObject(name("/" + in, FileType.FOLDER), fileSystem);
        folder.getName();
        assertEquals(Answer.NO, folder.atomicCapabilities(target).getExclusivePublish());
    }

    @Test
    public void linkProbeRunsInTheTargetFolder() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(4)).when(channel).hardlink(in + "/a.csv", out + "/a.csv");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(out + "/a.csv");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.csv").linkExclusive(file(fileSystem, out, "a.csv"),
                "p.part");
        assertEquals(AtomicOutcome.EXISTS, result.getOutcome());
        verify(channel).hardlink(in + "/a.csv", out + "/p.part");
    }

    @Test
    public void publishRemovesTheTempNameOnlyAfterDone() throws Exception {
        final ChannelSftp done = channel(true, true);
        final ChannelSftp exists = channel(true, true);
        doThrow(reply(4)).when(exists).hardlink(anyString(), anyString());
        doReturn(attrs(false, 1, 1)).when(exists).lstat(out + "/t.bin");
        final Channels fileSystem = new Channels(done, exists);
        final SftpFileObject temp = file(fileSystem, in, "a.tmp");
        final SftpFileObject target = file(fileSystem, out, "t.bin");
        assertEquals(AtomicOutcome.DONE, temp.publishExclusive(target, "p.part").getOutcome());
        verify(done).rm(in + "/a.tmp");
        assertEquals(AtomicOutcome.EXISTS, temp.publishExclusive(target, "p.part").getOutcome());
        verify(exists, never()).rm(anyString());
    }

    @Test
    public void linkExclusiveAndPublishWithACauseAreUnclearInTheirPhase() throws Exception {
        final ChannelSftp link = channel(true, true);
        final SftpException linkCause = broken();
        doThrow(linkCause).when(link).hardlink(anyString(), anyString());
        final ChannelSftp publish = channel(true, true);
        final SftpException publishCause = broken();
        doThrow(publishCause).when(publish).hardlink(anyString(), anyString());
        final Channels fileSystem = new Channels(link, publish);
        final SftpFileObject source = file(fileSystem, in, "a.tmp");
        final SftpFileObject target = file(fileSystem, out, "t.bin");
        unclear(MovePhase.RENAME, null, linkCause, () -> source.linkExclusive(target, "p.part"));
        unclear(MovePhase.COMMIT, "/" + in + "/a.tmp", publishCause, () -> source.publishExclusive(target, "p.part"));
        verify(link, never()).rm(anyString());
        verify(publish, never()).rm(anyString());
        assertEquals(Arrays.asList(link, publish), fileSystem.discarded);
    }

    @Test
    public void publishWhoseTempRemovalFailsIsDoneWithALeftover() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(3)).when(channel).rm(in + "/a.tmp");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "a.tmp").publishExclusive(file(fileSystem, out, "t.bin"),
                "p.part");
        assertEquals(AtomicOutcome.DONE, result.getOutcome());
        assertEquals(Arrays.asList(in + "/a.tmp"), result.getLeftovers());
    }

    // renameReplacing and its replace test.

    @Test
    public void renameThatReturnsIsDone() throws Exception {
        final ChannelSftp channel = channel(true, false);
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.DONE, file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                null, null).getOutcome());
        verify(channel).rename(in + "/t.tmp", in + "/t.csv");
    }

    @Test
    public void refusalWithThePosixRenameIsRefusedWithNoLookAndNoTest() throws Exception {
        final ChannelSftp channel = channel(true, true);
        doThrow(reply(3)).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        verify(channel, never()).lstat(anyString());
        verify(channel, never()).put(any(InputStream.class), anyString());
        verify(channel, never()).rm(anyString());
    }

    @Test
    public void refusalWithNoTargetIsRefusedAndRunsNoTest() throws Exception {
        final ChannelSftp channel = channel(true, false);
        doThrow(reply(4)).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).lstat(in + "/t.csv");
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.REFUSED, file(fileSystem, in, "t.tmp").renameReplacing(
                file(fileSystem, in, "t.csv"), file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"))
                .getOutcome());
        verify(channel, never()).put(any(InputStream.class), anyString());
    }

    @Test
    public void lookWithACauseAfterARefusedRenameIsRefusedWithTheConnectionLost() throws Exception {
        final ChannelSftp channel = channel(true, false);
        doThrow(reply(4)).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        doThrow(broken()).when(channel).lstat(in + "/t.csv");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
    }

    private ChannelSftp refusedWithTheTargetThere() throws SftpException {
        final ChannelSftp channel = channel(true, false);
        doThrow(reply(4)).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        doReturn(attrs(false, 1, 1)).when(channel).lstat(in + "/t.csv");
        return channel;
    }

    @Test
    public void refusalWithTheTargetThereAndNoProbeFilesIsRefused() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.REFUSED, file(fileSystem, in, "t.tmp").renameReplacing(
                file(fileSystem, in, "t.csv"), null, null).getOutcome());
        verify(channel, never()).put(any(InputStream.class), anyString());
    }

    @Test
    public void serverThatReplacesIsRememberedAndTheOutcomeIsRefused() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        final Channels fileSystem = new Channels(channel, refusedWithTheTargetThere());
        fileSystem.announced(true, false);
        final SftpFileObject temp = file(fileSystem, in, "t.tmp");
        final AtomicResult result = temp.renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertEquals("id 4 reply 4", result.getDetail());
        verify(channel).rename(in + "/pa.part", in + "/pb.part");
        verify(channel).rm(in + "/pb.part");
        verify(channel, never()).rm(in + "/t.csv");
        assertEquals(Answer.YES, temp.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void serverThatRefusesOnlyWithTheTargetThereIsUnsupportedThenFromMemory() throws Exception {
        final ChannelSftp first = refusedWithTheTargetThere();
        doThrow(reply(4)).doNothing().when(first).rename(in + "/pa.part", in + "/pb.part");
        final ChannelSftp second = refusedWithTheTargetThere();
        final Channels fileSystem = new Channels(first, second);
        fileSystem.announced(true, false);
        final SftpFileObject temp = file(fileSystem, in, "t.tmp");
        final AtomicResult result = temp.renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.UNSUPPORTED, result.getOutcome());
        final InOrder order = inOrder(first);
        order.verify(first).rename(in + "/pa.part", in + "/pb.part");
        order.verify(first).rm(in + "/pb.part");
        order.verify(first).rename(in + "/pa.part", in + "/pb.part");
        order.verify(first).rm(in + "/pb.part");
        assertEquals(Answer.NO, temp.atomicCapabilities().getReplacingRename());
        assertEquals(AtomicOutcome.UNSUPPORTED, temp.renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part")).getOutcome());
        verify(second, never()).put(any(InputStream.class), anyString());
    }

    @Test
    public void folderThatRefusesEveryRenameRemembersNothing() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        doThrow(reply(4)).when(channel).rename(in + "/pa.part", in + "/pb.part");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, false);
        final SftpFileObject temp = file(fileSystem, in, "t.tmp");
        final AtomicResult result = temp.renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        verify(channel).rm(in + "/pa.part");
        assertTrue(result.getLeftovers().isEmpty());
        assertEquals(Answer.NOT_KNOWN, temp.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void removalOfTheSecondProbeRefusedInTheControlIsRefusedWithALeftover() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        doThrow(reply(4)).when(channel).rename(in + "/pa.part", in + "/pb.part");
        doThrow(reply(3)).when(channel).rm(in + "/pb.part");
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertEquals(Arrays.asList(in + "/pb.part"), result.getLeftovers());
        verify(channel).rm(in + "/pa.part");
    }

    @Test
    public void probeWriteRefusedDecidesNothing() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        doThrow(reply(4)).when(channel).put(any(InputStream.class), eq(in + "/pb.part"));
        final Channels fileSystem = new Channels(channel);
        final AtomicResult result = file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        verify(channel).rm(in + "/pa.part");
        verify(channel, never()).rename(in + "/pa.part", in + "/pb.part");
    }

    @Test
    public void connectionThatDiesInTheTestDecidesNothingAndLeavesTheProbeNames() throws Exception {
        final ChannelSftp channel = refusedWithTheTargetThere();
        doThrow(broken()).when(channel).rename(in + "/pa.part", in + "/pb.part");
        final Channels fileSystem = new Channels(channel);
        fileSystem.announced(true, false);
        final SftpFileObject temp = file(fileSystem, in, "t.tmp");
        final AtomicResult result = temp.renameReplacing(file(fileSystem, in, "t.csv"),
                file(fileSystem, in, "pa.part"), file(fileSystem, in, "pb.part"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertEquals(Arrays.asList(in + "/pa.part", in + "/pb.part"), result.getLeftovers());
        assertEquals(Answer.NOT_KNOWN, temp.atomicCapabilities().getReplacingRename());
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
    }

    @Test
    public void renameWithACauseThrowsAndMakesNoFurtherCall() throws Exception {
        final ChannelSftp channel = channel(true, false);
        final SftpException cause = broken();
        doThrow(cause).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        final Channels fileSystem = new Channels(channel);
        unclear(MovePhase.COMMIT, "/" + in + "/t.tmp", cause,
                () -> file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"), null, null));
        verify(channel, never()).lstat(anyString());
        assertEquals(Arrays.asList(channel), fileSystem.discarded);
    }

    @Test
    public void renameOfAMissingSourceIsGone() throws Exception {
        final ChannelSftp channel = channel(true, false);
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(channel).rename(in + "/t.tmp", in + "/t.csv");
        final Channels fileSystem = new Channels(channel);
        assertEquals(AtomicOutcome.GONE, file(fileSystem, in, "t.tmp").renameReplacing(file(fileSystem, in, "t.csv"),
                null, null).getOutcome());
    }

    // takeAway.

    @Test
    public void takeAwayGivesDoneGoneRefusedOrThrows() throws Exception {
        final ChannelSftp done = channel(true, true);
        final ChannelSftp gone = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(gone).rename(anyString(), anyString());
        final ChannelSftp refused = channel(true, true);
        doThrow(reply(3)).when(refused).rename(anyString(), anyString());
        final ChannelSftp cut = channel(true, true);
        final SftpException cause = broken();
        doThrow(cause).when(cut).rename(anyString(), anyString());
        final Channels fileSystem = new Channels(done, gone, refused, cut);
        final SftpFileObject lock = file(fileSystem, in, "a.lock");
        final SftpFileObject away = file(fileSystem, in, "a.x1.lock");
        assertEquals(AtomicOutcome.DONE, lock.takeAway(away).getOutcome());
        verify(done).rename(in + "/a.lock", in + "/a.x1.lock");
        assertEquals(AtomicOutcome.GONE, lock.takeAway(away).getOutcome());
        assertEquals(AtomicOutcome.REFUSED, lock.takeAway(away).getOutcome());
        unclear(MovePhase.RENAME, null, cause, () -> lock.takeAway(away));
        assertEquals(Arrays.asList(cut), fileSystem.discarded);
    }

    // The looks.

    @Test
    public void lookIsFalseOnlyForNoSuchFile() throws Exception {
        final ChannelSftp there = channel(true, true);
        doReturn(attrs(false, 7, 100)).when(there).lstat(in + "/a.csv");
        final ChannelSftp missing = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(missing).lstat(in + "/a.csv");
        final ChannelSftp denied = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED)).when(denied).lstat(in + "/a.csv");
        final ChannelSftp failure = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(failure).lstat(in + "/a.csv");
        final ChannelSftp cut = channel(true, true);
        doThrow(broken()).when(cut).lstat(in + "/a.csv");
        final Channels fileSystem = new Channels(there, missing, denied, failure, cut);
        final SftpFileObject file = file(fileSystem, in, "a.csv");
        assertTrue(file.existsNow());
        assertFalse(file.existsNow());
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, file::existsNow).getFault());
        // An id 4 is thrown. The look does not use the stat retry.
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, file::existsNow).getFault());
        final FileSystemException lost = assertThrows(FileSystemException.class, file::existsNow);
        assertEquals(Fault.TRANSPORT, lost.getFault());
        assertFalse(lost instanceof UnclearResultException);
        assertEquals(Arrays.asList(there, missing, denied, failure), fileSystem.putBack);
        assertEquals(Arrays.asList(cut), fileSystem.discarded);
        verify(there, never()).stat(anyString());
    }

    @Test
    public void folderLookAndStatReadTheOneLstat() throws Exception {
        final ChannelSftp folder = channel(true, true);
        doReturn(attrs(true, 0, 0)).when(folder).lstat(anyString());
        final ChannelSftp file = channel(true, true);
        doReturn(attrs(false, 7, 100)).when(file).lstat(anyString());
        final ChannelSftp stat = channel(true, true);
        doReturn(attrs(false, 7, 100)).when(stat).lstat(anyString());
        final ChannelSftp missing = channel(true, true);
        doThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE)).when(missing).lstat(anyString());
        final Channels fileSystem = new Channels(folder, file, stat, missing);
        final SftpFileObject object = file(fileSystem, in, "x");
        assertTrue(object.folderExistsNow());
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, object::folderExistsNow).getFault());
        final StatNow found = object.statNow();
        assertTrue(found.isThere());
        assertEquals(7, found.getSize());
        assertEquals(100000, found.getLastModifiedMillis());
        assertFalse(found.isFromListing());
        assertFalse(object.statNow().isThere());
    }

    @Test
    public void statReadsTheTimeAsUnsignedSeconds() throws Exception {
        final long[] seconds = {2147483647L, 2147483648L, 2208988800L};
        final ChannelSftp[] channels = new ChannelSftp[seconds.length];
        for (int i = 0; i < seconds.length; i++) {
            channels[i] = channel(true, true);
            // JSch keeps the 32 bit field of the protocol in a signed int.
            doReturn(attrs(false, 1, (int) seconds[i])).when(channels[i]).lstat(anyString());
        }
        final Channels fileSystem = new Channels(channels);
        final SftpFileObject object = file(fileSystem, in, "t.csv");
        for (final long second : seconds) {
            assertEquals(second * 1000, object.statNow().getLastModifiedMillis());
        }
    }

    @Test
    public void noLinkPersistsAfterAnHour() throws Exception {
        final Channels fs = new Channels();
        fs.announced(true, false);
        final SftpFileObject file = file(fs, in, "a.csv");
        fs.markNoLink(file.getName().getParent().getPath());
        CapabilityClockTest.assertPersistent(() -> file.atomicCapabilities().getExclusiveCreate());
        assertTrue(fs.putBack.isEmpty());
        assertTrue(fs.discarded.isEmpty());
    }

    @Test
    public void sftpViewsProtectBothBusinessEntriesAndAllowAnotherEndpoint() throws Exception {
        try (SftpTestServer one = new SftpTestServer(); SftpTestServer two = new SftpTestServer();
                StandardFileSystemManager manager = new StandardFileSystemManager()) {
            manager.init();
            FileSystemOptions options = SftpTestServer.options();
            SftpFileSystemConfigBuilder config = SftpFileSystemConfigBuilder.getInstance();
            config.setAvoidPermissionCheck(options, "true");
            FileSystemOptions changed = (FileSystemOptions) options.clone();
            config.setSessionTimeout(changed, Duration.ofSeconds(8));
            Path src = one.write("in/source", "source");
            Path dst = one.write("in/dest", "old target");
            SftpFileObject source = (SftpFileObject) FileObjectUtils.getAbstractFileObject(
                    manager.resolveFile(one.uri("in/source"), options));
            FileObject alias = new DecoratedFileObject(manager.resolveFile(one.uri("in/source"), changed));
            FileObject target = manager.resolveFile(one.uri("in/dest"), changed);
            assertEquals("SAME", String.valueOf(source.entryRelation(alias)));
            source.moveTo(alias, new MoveOptions(true, null, null, null, null));
            assertTrue(assertThrows(MoveFailedException.class, () -> source.moveTo(alias,
                    new MoveOptions(false, null, null, null, null))).isTargetExists());
            for (FileObject temp : new FileObject[] {alias, new DecoratedFileObject(target)}) {
                assertThrows(MoveFailedException.class, () -> source.moveTo(target, temp));
                assertEquals("source", Files.readString(src));
                assertEquals("old target", Files.readString(dst));
            }
            FileSystemOptions opaque = (FileSystemOptions) changed.clone();
            DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(opaque,
                    new StaticUserAuthenticator(null, SftpTestServer.USER, SftpTestServer.USER));
            FileObject overlap = manager.resolveFile(one.uri("in/source"), opaque);
            assertEquals("UNKNOWN", String.valueOf(source.entryRelation(overlap)));
            assertFalse(assertThrows(MoveFailedException.class, () -> source.moveTo(overlap, new MoveOptions(true, null, null, null, null))).isTargetExists());
            assertEquals("DISTINCT", String.valueOf(source.entryRelation(manager.resolveFile(one.uri("in/dest"), opaque))));
            FileObject opaqueDest = manager.resolveFile(one.uri("in/dest"), opaque);
            assertFalse(assertThrows(MoveFailedException.class, () -> source.moveTo(opaqueDest, overlap)).isTargetExists());
            assertEquals("source", Files.readString(src));
            assertEquals("old target", Files.readString(dst));
            Path unrelated = one.write("in/unrelated", "different bytes");
            FileObjectUtils.getAbstractFileObject(manager.resolveFile(one.uri("in/unrelated"), options))
                    .moveTo(opaqueDest, new MoveOptions(true, null, null, null, null));
            assertFalse(Files.exists(unrelated));
            assertEquals("different bytes", Files.readString(dst));
            Path separate = two.write("in/source", "separate");
            FileObject different = manager.resolveFile(two.uri("in/source"), changed);
            assertEquals("DISTINCT", String.valueOf(source.entryRelation(different)));
            source.moveTo(different, new MoveOptions(true, null, null, null, null));
            assertEquals("source", Files.readString(separate));
            assertFalse(Files.exists(src));
            assertEquals("different bytes", Files.readString(dst));
        }
    }
}
