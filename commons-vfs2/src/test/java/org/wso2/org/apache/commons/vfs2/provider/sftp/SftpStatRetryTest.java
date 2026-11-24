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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileNotFoundException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

/**
 * The stat with its one retry: id 2 with no cause is missing, a slow failure gets no retry, a fast failure gets one
 * retry on a fresh channel, and on the retry a failure reply on a channel that is still up is missing too. The channel
 * that failed is never handed back.
 */
public class SftpStatRetryTest {

    // The constructor of a generic name is protected, so the names are anonymous subclasses.
    private static final GenericFileName ROOT = new GenericFileName("sftp", "localhost", 22, 22, "user", "pass", "/",
            FileType.FOLDER) {
    };

    private static final GenericFileName FOLDER = new GenericFileName("sftp", "localhost", 22, 22, "user", "pass",
            "/in", FileType.FOLDER) {
    };

    private static final GenericFileName FILE = new GenericFileName("sftp", "localhost", 22, 22, "user", "pass",
            "/in/a.txt", FileType.FILE) {
    };

    /** A file system whose channels come from a queue, and which records each release. */
    private static final class Channels extends SftpFileSystem {

        private final Deque<ChannelSftp> queue = new ArrayDeque<>();

        private final List<Object[]> released = new ArrayList<>();

        Channels(final ChannelSftp... channels) {
            super(ROOT, connectedSession(), new SocketGroup(), new FileSystemOptions());
            for (final ChannelSftp channel : channels) {
                queue.add(channel);
            }
        }

        @Override
        protected ChannelSftp getChannel() {
            return queue.remove();
        }

        @Override
        protected void releaseChannel(final ChannelSftp channel, final Throwable error) {
            released.add(new Object[] {channel, error});
        }

        boolean wentBack(final int index) {
            return SftpFileSystem.isReplyOrSuccess((Throwable) released.get(index)[1]);
        }
    }

    private static Session connectedSession() {
        final Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        return session;
    }

    private static ChannelSftp channel(final boolean connected) {
        final ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.isConnected()).thenReturn(connected);
        return channel;
    }

    private static SftpATTRS file() {
        final SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.getFlags()).thenReturn(SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS);
        when(attrs.isDir()).thenReturn(false);
        return attrs;
    }

    private static SftpException reply(final int id) {
        return new SftpException(id, "reply " + id);
    }

    private static SftpException broken() {
        return new SftpException(ChannelSftp.SSH_FX_FAILURE, "broken", new IOException("the connection is gone"));
    }

    private static FileType type(final Channels fileSystem) throws Exception {
        return new SftpFileObject(FILE, fileSystem).doGetType();
    }

    @Test
    public void missingFileWithNoCauseIsMissingAndItsChannelGoesBack() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE));
        final Channels fileSystem = new Channels(first);
        assertEquals(FileType.IMAGINARY, type(fileSystem));
        assertEquals(1, fileSystem.released.size());
        assertSame(first, fileSystem.released.get(0)[0]);
        assertTrue(fileSystem.wentBack(0));
    }

    @Test
    public void fastFailureWithACauseIsRetriedOnAFreshChannelAndTheFailedOneIsDiscarded() throws Exception {
        final ChannelSftp first = channel(false);
        final SftpATTRS attrs = file();
        when(first.stat(anyString())).thenThrow(broken());
        final ChannelSftp second = channel(true);
        when(second.stat(anyString())).thenReturn(attrs);
        final Channels fileSystem = new Channels(first, second);
        assertEquals(FileType.FILE, type(fileSystem));
        assertEquals(2, fileSystem.released.size());
        assertSame(first, fileSystem.released.get(0)[0]);
        assertTrue(!fileSystem.wentBack(0));
        assertSame(second, fileSystem.released.get(1)[0]);
        assertNull(fileSystem.released.get(1)[1]);
    }

    @Test
    public void fastFailureReplyIsRetriedOnAFreshChannelAndTheFailedOneIsNotPooled() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final ChannelSftp second = channel(true);
        when(second.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final Channels fileSystem = new Channels(first, second);
        assertEquals(FileType.IMAGINARY, type(fileSystem));
        verify(first).disconnect();
        verify(second, never()).disconnect();
        assertEquals(2, fileSystem.released.size());
    }

    @Test
    public void retryThatFindsNoSuchFileIsMissing() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED));
        final ChannelSftp second = channel(true);
        when(second.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE));
        assertEquals(FileType.IMAGINARY, type(new Channels(first, second)));
    }

    @Test
    public void retryFailureReplyOnAChannelThatIsDownIsThrown() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final ChannelSftp second = channel(false);
        when(second.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final SftpException error = assertThrows(SftpException.class, () -> type(new Channels(first, second)));
        assertEquals(ChannelSftp.SSH_FX_FAILURE, error.id);
    }

    @Test
    public void retryFailureWithACauseIsThrown() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final ChannelSftp second = channel(true);
        final SftpException cut = broken();
        when(second.stat(anyString())).thenThrow(cut);
        final Channels fileSystem = new Channels(first, second);
        assertSame(cut, assertThrows(SftpException.class, () -> type(fileSystem)));
        assertTrue(!fileSystem.wentBack(1));
    }

    @Test
    public void retryThatFindsNoSuchFileWithACauseIsThrownAndNotMissing() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final ChannelSftp second = channel(true);
        final SftpException cut = new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "cut",
                new IOException("the connection is gone"));
        when(second.stat(anyString())).thenThrow(cut);
        final Channels fileSystem = new Channels(first, second);
        assertSame(cut, assertThrows(SftpException.class, () -> type(fileSystem)));
        assertTrue(!fileSystem.wentBack(1));
    }

    @Test
    public void noSuchFileWithACauseIsRetriedAndNotMissing() throws Exception {
        final ChannelSftp first = channel(false);
        when(first.stat(anyString())).thenThrow(new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "cut",
                new IOException("the connection is gone")));
        final ChannelSftp second = channel(true);
        final SftpATTRS attrs = file();
        when(second.stat(anyString())).thenReturn(attrs);
        final Channels fileSystem = new Channels(first, second);
        assertEquals(FileType.FILE, type(fileSystem));
        assertTrue(!fileSystem.wentBack(0));
        assertTrue(fileSystem.wentBack(1));
    }

    @Test
    public void readThatFindsNoSuchFileWithACauseIsNoMissingFile() throws Exception {
        final ChannelSftp reader = channel(true);
        final SftpException cut = new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "cut",
                new IOException("the connection is gone"));
        when(reader.get(anyString())).thenThrow(cut);
        final ChannelSftp stat = channel(true);
        final SftpATTRS attrs = file();
        when(stat.stat(anyString())).thenReturn(attrs);
        final Channels fileSystem = new Channels(reader, stat);
        final FileSystemException error = assertThrows(FileSystemException.class,
            () -> new SftpFileObject(FILE, fileSystem).doGetInputStream(4096));
        assertFalse(error instanceof FileNotFoundException, error.toString());
        assertSame(cut, error.getCause());
        assertSame(reader, fileSystem.released.get(1)[0]);
        assertTrue(!fileSystem.wentBack(1));
    }

    @Test
    public void readThatFindsNoSuchFileWithNoCauseIsAMissingFile() throws Exception {
        final ChannelSftp reader = channel(true);
        when(reader.get(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_NO_SUCH_FILE));
        final ChannelSftp stat = channel(true);
        final SftpATTRS attrs = file();
        when(stat.stat(anyString())).thenReturn(attrs);
        final Channels fileSystem = new Channels(reader, stat);
        assertThrows(FileNotFoundException.class, () -> new SftpFileObject(FILE, fileSystem).doGetInputStream(4096));
        assertTrue(fileSystem.wentBack(1));
    }

    private static SftpATTRS folder() {
        final SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.getFlags()).thenReturn(SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS);
        when(attrs.isDir()).thenReturn(true);
        return attrs;
    }

    // The first channel answers the stat that tells the type. The second one lists.
    private static Channels listing(final ChannelSftp lister) throws Exception {
        final ChannelSftp stat = channel(true);
        final SftpATTRS attrs = folder();
        when(stat.stat(anyString())).thenReturn(attrs);
        return new Channels(stat, lister);
    }

    private static FileObject[] list(final Channels fileSystem) throws Exception {
        return new SftpFileObject(FOLDER, fileSystem).doListChildrenResolved();
    }

    @Test
    public void listingStopsAtTheFirstFailureWithACause() throws Exception {
        final ChannelSftp lister = channel(true);
        final SftpException cut = broken();
        doThrow(cut).when(lister).ls(anyString(), any(ChannelSftp.LsEntrySelector.class));
        final Channels fileSystem = listing(lister);
        assertSame(cut, assertThrows(SftpException.class, () -> list(fileSystem)));
        verify(lister, never()).pwd();
        verify(lister, never()).cd(anyString());
        verify(lister).ls(anyString(), any(ChannelSftp.LsEntrySelector.class));
        assertSame(lister, fileSystem.released.get(1)[0]);
        assertTrue(!fileSystem.wentBack(1));
    }

    @Test
    public void listingAfterAReplyChangesIntoTheFolderAndBack() throws Exception {
        final ChannelSftp lister = channel(true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(lister).ls(eq("in"), any(ChannelSftp.LsEntrySelector.class));
        when(lister.pwd()).thenReturn("/home");
        doNothing().when(lister).ls(eq("."), any(ChannelSftp.LsEntrySelector.class));
        final Channels fileSystem = listing(lister);
        assertEquals(0, list(fileSystem).length);
        verify(lister).cd("in");
        verify(lister).cd("/home");
        assertTrue(fileSystem.wentBack(1));
    }

    @Test
    public void listingAfterAReplyQuotesTheLiteralFolderAndRestoresTheLiteralWorkingDirectory() throws Exception {
        final ChannelSftp lister = channel(true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(lister).ls(eq("in\\*"), any(ChannelSftp.LsEntrySelector.class));
        when(lister.pwd()).thenReturn("/home?");
        doNothing().when(lister).ls(eq("."), any(ChannelSftp.LsEntrySelector.class));
        final Channels fileSystem = listing(lister);
        assertEquals(0, new SftpFileObject(
                (GenericFileName) FOLDER.createName("/in*", FileType.FOLDER),
                fileSystem).doListChildrenResolved().length);
        verify(lister).cd("in\\*");
        verify(lister).cd("/home\\?");
        assertTrue(fileSystem.wentBack(1));
    }

    @Test
    public void listingStopsAtAFailureWithACauseInTheFolderAndDoesNotChangeBack() throws Exception {
        final ChannelSftp lister = channel(true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(lister).ls(eq("in"), any(ChannelSftp.LsEntrySelector.class));
        when(lister.pwd()).thenReturn("/home");
        final SftpException cut = broken();
        doThrow(cut).when(lister).ls(eq("."), any(ChannelSftp.LsEntrySelector.class));
        final Channels fileSystem = listing(lister);
        assertSame(cut, assertThrows(SftpException.class, () -> list(fileSystem)));
        verify(lister, never()).cd("/home");
        assertTrue(!fileSystem.wentBack(1));
    }

    @Test
    public void listingThatCanNotChangeBackReportsTheChangeBackAndDropsTheChannel() throws Exception {
        final ChannelSftp lister = channel(true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(lister).ls(eq("in"), any(ChannelSftp.LsEntrySelector.class));
        when(lister.pwd()).thenReturn("/home");
        doNothing().when(lister).ls(eq("."), any(ChannelSftp.LsEntrySelector.class));
        final SftpException back = reply(ChannelSftp.SSH_FX_PERMISSION_DENIED);
        doThrow(back).when(lister).cd("/home");
        final Channels fileSystem = listing(lister);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> list(fileSystem));
        assertSame(back, error.getCause());
        assertEquals(0, error.getSuppressed().length);
        verify(lister).disconnect();
        assertSame(lister, fileSystem.released.get(1)[0]);
    }

    @Test
    public void listingThatFailsAndCanNotChangeBackReportsTheChangeBackWithTheListFailure() throws Exception {
        final ChannelSftp lister = channel(true);
        doThrow(reply(ChannelSftp.SSH_FX_FAILURE)).when(lister).ls(eq("in"), any(ChannelSftp.LsEntrySelector.class));
        when(lister.pwd()).thenReturn("/home");
        final SftpException listFailure = reply(ChannelSftp.SSH_FX_PERMISSION_DENIED);
        doThrow(listFailure).when(lister).ls(eq("."), any(ChannelSftp.LsEntrySelector.class));
        final SftpException back = reply(ChannelSftp.SSH_FX_PERMISSION_DENIED);
        doThrow(back).when(lister).cd("/home");
        final Channels fileSystem = listing(lister);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> list(fileSystem));
        assertSame(back, error.getCause());
        assertSame(listFailure, error.getSuppressed()[0]);
        verify(lister).disconnect();
    }

    @Test
    public void retryWithAnotherReplyIsThrown() throws Exception {
        final ChannelSftp first = channel(true);
        when(first.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_FAILURE));
        final ChannelSftp second = channel(true);
        when(second.stat(anyString())).thenThrow(reply(ChannelSftp.SSH_FX_PERMISSION_DENIED));
        final SftpException error = assertThrows(SftpException.class, () -> type(new Channels(first, second)));
        assertEquals(ChannelSftp.SSH_FX_PERMISSION_DENIED, error.id);
    }

    @Test
    public void slowFailureReplyGetsNoRetryAndItsChannelIsNotPooled() throws Exception {
        final ChannelSftp first = channel(true);
        final SftpException late = reply(ChannelSftp.SSH_FX_FAILURE);
        when(first.stat(anyString())).thenAnswer(invocation -> {
            Thread.sleep(5100);
            throw late;
        });
        final ChannelSftp second = channel(true);
        final Channels fileSystem = new Channels(first, second);
        assertSame(late, assertThrows(SftpException.class, () -> type(fileSystem)));
        verify(first).disconnect();
        assertEquals(1, fileSystem.released.size());
        assertEquals(1, fileSystem.queue.size());
    }

    @Test
    public void slowFailureGetsNoRetry() throws Exception {
        final ChannelSftp first = channel(false);
        final SftpException cut = broken();
        when(first.stat(anyString())).thenAnswer(invocation -> {
            Thread.sleep(5100);
            throw cut;
        });
        final ChannelSftp second = channel(true);
        final Channels fileSystem = new Channels(first, second);
        assertSame(cut, assertThrows(SftpException.class, () -> type(fileSystem)));
        assertEquals(1, fileSystem.released.size());
        assertTrue(!fileSystem.wentBack(0));
        assertEquals(1, fileSystem.queue.size());
    }
}
