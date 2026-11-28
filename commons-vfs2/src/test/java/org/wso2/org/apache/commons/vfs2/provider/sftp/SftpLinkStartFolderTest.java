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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;

/**
 * The hard link steps on a server whose sessions start in a folder that is not the root, as OpenSSH with no chroot.
 * The server resolves a relative old path of a hard link in that start folder, so the old path has to go out absolute.
 */
public class SftpLinkStartFolderTest {

    private static final String START = "/home/" + SftpTestServer.USER;

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        server = SftpTestServer.startingIn(START);
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        server.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void lockCreateLinksItsScratchFile(final boolean userDirIsRoot) throws Exception {
        server.write(onDisk(userDirIsRoot, "f.txt"), "f");
        final FileObject lock = resolve(userDirIsRoot, "f.txt.lock");
        final AtomicResult result = AtomicOps.of(lock).createExclusive("owner".getBytes(StandardCharsets.UTF_8),
                "f.txt.s.lock", "f.txt.p.lock");
        assertEquals(AtomicOutcome.DONE, result.getOutcome(), String.valueOf(result));
        assertEquals("owner", read(onDisk(userDirIsRoot, "f.txt.lock")));
        assertFalse(Files.exists(server.getRoot().resolve(onDisk(userDirIsRoot, "f.txt.s.lock"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void moveWithNoReplaceLinksTheTarget(final boolean userDirIsRoot) throws Exception {
        server.write(onDisk(userDirIsRoot, "s.txt"), "source");
        final FileObject source = resolve(userDirIsRoot, "s.txt");
        final AtomicResult result = AtomicOps.of(source).linkExclusive(resolve(userDirIsRoot, "t.txt"), "t.txt.p");
        assertEquals(AtomicOutcome.DONE, result.getOutcome(), String.valueOf(result));
        assertEquals("source", read(onDisk(userDirIsRoot, "t.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void commitWithNoReplacePublishesTheTemp(final boolean userDirIsRoot) throws Exception {
        server.write(onDisk(userDirIsRoot, "p.part"), "temp");
        final FileObject temp = resolve(userDirIsRoot, "p.part");
        final AtomicResult result = AtomicOps.of(temp).publishExclusive(resolve(userDirIsRoot, "p.txt"), "p.txt.p");
        assertEquals(AtomicOutcome.DONE, result.getOutcome(), String.valueOf(result));
        assertEquals("temp", read(onDisk(userDirIsRoot, "p.txt")));
        assertFalse(Files.exists(server.getRoot().resolve(onDisk(userDirIsRoot, "p.part"))));
    }

    // With userDirIsRoot true a path of the address is under the start folder, else under the root.
    private static String onDisk(final boolean userDirIsRoot, final String name) {
        return (userDirIsRoot ? START.substring(1) + "/" : "") + "data/" + name;
    }

    private FileObject resolve(final boolean userDirIsRoot, final String name) throws FileSystemException {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, userDirIsRoot);
        return manager.resolveFile(server.uri("data/" + name), options);
    }

    private String read(final String name) throws IOException {
        return new String(Files.readAllBytes(server.getRoot().resolve(name)), StandardCharsets.UTF_8);
    }
}
