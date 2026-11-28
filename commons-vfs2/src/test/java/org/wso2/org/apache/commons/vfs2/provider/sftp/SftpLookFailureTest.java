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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;

/**
 * The look of the lock steps on a server that answers a name that is not there with a failure, id 4, as FileZilla
 * Server does. The look takes the rule of the stat of a file: a second failure on a fresh channel means not there.
 */
public class SftpLookFailureTest {

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        server = SftpTestServer.startingIn("/home/" + SftpTestServer.USER);
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        server.close();
    }

    @Test
    public void lockIsTakenWhereTheServerAnswersAMissingNameWithAFailure() throws Exception {
        server.answerFailureForMissingNames();
        server.write("data/f.txt", "f");
        final AtomicOps lock = AtomicOps.of(resolve("data/f.txt.lock"));
        assertFalse(lock.existsNow());
        final AtomicResult created = lock.createExclusive("owner".getBytes(StandardCharsets.UTF_8), "f.txt.s.lock",
                "f.txt.p.lock");
        assertEquals(AtomicOutcome.DONE, created.getOutcome(), String.valueOf(created));
        assertTrue(lock.existsNow());
        assertEquals("owner", read("data/f.txt.lock"));
    }

    @Test
    public void failureForALockThatIsThereGivesNoSecondHolder() throws Exception {
        server.write("data/g.txt", "g");
        server.write("data/g.txt.lock", "first");
        server.answerFailureForLook("/data/g.txt.lock");
        final AtomicOps lock = AtomicOps.of(resolve("data/g.txt.lock"));
        // By the rule the look reads the two failures as not there, and the exclusive step decides.
        assertFalse(lock.existsNow());
        final AtomicResult created = lock.createExclusive("second".getBytes(StandardCharsets.UTF_8), "g.txt.s.lock",
                "g.txt.p.lock");
        assertNotEquals(AtomicOutcome.DONE, created.getOutcome(), String.valueOf(created));
        assertEquals("first", read("data/g.txt.lock"));
    }

    private FileObject resolve(final String name) throws FileSystemException {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, false);
        return manager.resolveFile(server.uri(name), options);
    }

    private String read(final String name) throws IOException {
        return new String(Files.readAllBytes(server.getRoot().resolve(name)), StandardCharsets.UTF_8);
    }
}
