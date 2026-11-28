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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

/**
 * A server that offers the hard link extension and answers each link with no such file while the old path is there.
 * Where the folder never linked and the probe link answers the same, the folder is marked as one with no link, and
 * the steps take the rule of a server with no hard link.
 */
public class SftpLinkAnsweredNoSuchFileTest {

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    // One set of options, so that every name of a test is on one file system object and shares its folder states.
    private FileSystemOptions options;

    @BeforeEach
    public void start() throws Exception {
        server = SftpTestServer.startingIn("/home/" + SftpTestServer.USER);
        manager = new StandardFileSystemManager();
        manager.init();
        options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, false);
        // As in the inbound and the connector, the server decides a move and no mode bits are read.
        SftpFileSystemConfigBuilder.getInstance().setAvoidPermissionCheck(options, "true");
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        server.close();
    }

    @Test
    public void lockTakesTheRuleOfNoHardLinkAndTheNextCreateSendsNoLink() throws Exception {
        server.write("data/f.txt", "f");
        server.answerNoSuchFileForLinks(true);
        final FileObject file = resolve("data/f.txt.lock");
        final AtomicOps lock = AtomicOps.of(file);
        final AtomicResult first = lock.createExclusive(bytes("owner"), "f.txt.s.lock", "f.txt.p.lock");
        assertEquals(AtomicOutcome.UNSUPPORTED, first.getOutcome(), String.valueOf(first));
        assertEquals(2, server.linkRequests());
        assertFalse(Files.exists(server.getRoot().resolve("data/f.txt.s.lock")));
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusiveCreate());
        // The WARN of the folder was given.
        assertFalse(((SftpFileSystem) file.getFileSystem()).firstWarning("link /data"));
        assertEquals(AtomicOutcome.UNSUPPORTED,
                lock.createExclusive(bytes("owner"), "f.txt.s.lock", "f.txt.p.lock").getOutcome());
        assertEquals(2, server.linkRequests());
    }

    @Test
    public void sourceThatIsGoneStaysGoneAndMarksNothing() throws Exception {
        server.write("data/keep.txt", "k");
        server.answerNoSuchFileForLinks(true);
        final FileObject target = resolve("data/t.txt");
        final AtomicResult result = AtomicOps.of(resolve("data/missing.txt")).linkExclusive(target, "t.txt.p");
        assertEquals(AtomicOutcome.GONE, result.getOutcome(), String.valueOf(result));
        assertEquals(1, server.linkRequests());
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(resolve("data/keep.txt")).atomicCapabilities(target)
                .getExclusivePublish());
    }

    @Test
    public void folderThatLinkedBeforeStaysAnError() throws Exception {
        server.write("data/a.txt", "a");
        final AtomicOps first = AtomicOps.of(resolve("data/a.txt.lock"));
        assertEquals(AtomicOutcome.DONE, first.createExclusive(bytes("a"), "a.txt.s.lock", "a.txt.p.lock").getOutcome());
        server.answerNoSuchFileForLinks(true);
        final AtomicOps second = AtomicOps.of(resolve("data/b.txt.lock"));
        final AtomicResult result = second.createExclusive(bytes("b"), "b.txt.s.lock", "b.txt.p.lock");
        assertEquals(AtomicOutcome.GONE, result.getOutcome(), String.valueOf(result));
        assertEquals(2, server.linkRequests());
        assertEquals(Answer.YES, second.atomicCapabilities().getExclusiveCreate());
    }

    @Test
    public void moveWithNoReplaceTakesTheLookThenTheRename() throws Exception {
        server.write("data/s.txt", "source");
        server.answerNoSuchFileForLinks(true);
        final FileObject source = resolve("data/s.txt");
        FileObjectUtils.getAbstractFileObject(source).moveTo(resolve("data/t.txt"),
                new MoveOptions(false, null, null, null, null));
        assertEquals("source", read("data/t.txt"));
        assertFalse(Files.exists(server.getRoot().resolve("data/s.txt")));
        assertTrue(server.linkRequests() >= 2);
    }

    private FileObject resolve(final String name) throws FileSystemException {
        return manager.resolveFile(server.uri(name), options);
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String read(final String name) throws IOException {
        return new String(Files.readAllBytes(server.getRoot().resolve(name)), StandardCharsets.UTF_8);
    }
}
