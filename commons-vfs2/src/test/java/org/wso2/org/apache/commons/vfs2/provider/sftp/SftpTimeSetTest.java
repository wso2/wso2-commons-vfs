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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;

public class SftpTimeSetTest {
    private final ChannelSftp channel = mock(ChannelSftp.class);
    private Throwable releasedWith;
    private final SftpATTRS attrs = mock(SftpATTRS.class);

    private SftpFileObject freshFile() throws Exception {
        final GenericFileName root = new GenericFileName("sftp", "host", 22, 22, null, null, "/", FileType.FOLDER) { };
        final SftpFileSystem system = new SftpFileSystem(root, mock(Session.class), new SocketGroup(),
                new FileSystemOptions()) {
            @Override
            protected ChannelSftp getChannel() {
                return channel;
            }

            @Override
            protected void releaseChannel(final ChannelSftp returned, final Throwable error) {
                assertSame(channel, returned);
                releasedWith = error;
            }
        };
        when(channel.stat("note")).thenReturn(attrs);
        return new SftpFileObject((GenericFileName) root.createName("/note", FileType.FILE), system);
    }

    @Test
    public void timeSetOnAnObjectWithNoAttributesRunsAStatFirst() throws Exception {
        final SftpFileObject file = freshFile();
        when(attrs.getATime()).thenReturn(311);
        file.doSetLastModifiedTime(1234567L);
        final InOrder calls = inOrder(channel, attrs);
        calls.verify(channel).stat("note");
        calls.verify(attrs).setACMODTIME(311, 1234);
        calls.verify(channel).setStat("note", attrs);
    }

    @Test
    public void refusedTimeSetKeepsItsCauseAndReleasesTheChannel() throws Exception {
        final SftpFileObject file = freshFile();
        final SftpException refused = new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "refused");
        doThrow(refused).when(channel).setStat("note", attrs);
        final Exception failure = assertThrows(Exception.class, () -> file.doSetLastModifiedTime(1000));
        assertSame(refused, failure);
        assertEquals(Fault.APPLICATION, Faults.of(failure));
        assertSame(refused, releasedWith);
    }

    @Test
    public void setsIndependentOwnerGroupAndOtherBits() throws Exception {
        final SftpFileObject file = freshFile();
        file.setPosixMode(0641);
        verify(channel).chmod(0641, "note");
        assertEquals(null, releasedWith);
    }

    @Test
    public void permissionRepliesRetainTheirClassification() throws Exception {
        final SftpFileObject file = freshFile();
        final SftpException denied = new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "refused");
        final SftpException lost = new SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "connection lost");
        doThrow(denied, lost).when(channel).chmod(0640, "note");
        final Exception first = assertThrows(java.io.IOException.class, () -> file.setPosixMode(0640));
        assertEquals(Fault.APPLICATION, Faults.of(first));
        assertSame(denied, releasedWith);
        final Exception second = assertThrows(java.io.IOException.class, () -> file.setPosixMode(0640));
        assertEquals(Fault.TRANSPORT, Faults.of(second));
        assertSame(lost, releasedWith);
    }
}
