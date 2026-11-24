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

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * A close of the link alone, as freeUnusedResources does for a file system that stays in the cache, leaves the file
 * system usable, and the next call connects again, as in the base library. A close of the file system ends it.
 */
public class SftpLinkCloseTest {

    private SftpTestServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        server = new SftpTestServer();
        server.write("in/a.txt", "a");
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        server.close();
    }

    @Test
    public void closeOfTheLinkLeavesTheFileSystemUsable() throws Exception {
        final FileObject file = manager.resolveFile(SftpTestServer.uri(server.getPort(), "in/a.txt"),
                SftpTestServer.options());
        assertTrue(file.exists());
        final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
        final Object before = SftpTestServer.sessionOf(fileSystem);
        fileSystem.closeCommunicationLink();
        file.refresh();
        assertTrue(file.exists());
        assertNotSame(before, SftpTestServer.sessionOf(fileSystem));
    }

    @Test
    public void closeOfTheFileSystemEndsIt() throws Exception {
        final FileObject file = manager.resolveFile(SftpTestServer.uri(server.getPort(), "in/a.txt"),
                SftpTestServer.options());
        assertTrue(file.exists());
        final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
        fileSystem.close();
        file.refresh();
        assertThrows(FileSystemException.class, file::exists);
    }
}
