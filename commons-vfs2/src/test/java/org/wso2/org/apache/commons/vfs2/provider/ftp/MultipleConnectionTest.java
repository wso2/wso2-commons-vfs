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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.wso2.org.apache.commons.vfs2.VfsTestUtils.getTestDirectory;

import java.io.IOException;
import java.net.SocketException;

import org.apache.commons.net.ftp.FTPClient;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.VFS;
import org.apache.ftpserver.ftplet.FtpException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class MultipleConnectionTest {

    @BeforeAll
    public static void setUpClass() throws FtpException {
        FtpProviderTestCase.setUpClass(getTestDirectory(), null, null);
    }

    @AfterAll
    public static void tearDownClass() {
        FtpProviderTestCase.tearDownClass();
    }

    private FileObject resolveRoot() throws FileSystemException {
        return VFS.getManager().resolveFile(FtpProviderTestCase.getConnectionUri());
    }

    @Test
    public void testConnectRoot() throws IOException {
        // The global manager keeps these file systems and their connected clients. Each one is closed, also when the
        // second resolve fails, so that no later test in this JVM finds their sockets.
        final FileSystem first = resolveRoot().getFileSystem();
        try {
            final FileSystem second = resolveRoot().getFileSystem();
            if (second != first) {
                VFS.getManager().closeFileSystem(second);
            }
        } finally {
            VFS.getManager().closeFileSystem(first);
        }
    }

    @Test
    public void testUnderlyingConnect() throws SocketException, IOException {
        final FTPClient client1 = new FTPClient();
        final FTPClient client2 = new FTPClient();
        try {
            final String hostname = "localhost";
            client1.connect(hostname, FtpProviderTestCase.getSocketPort());
            client2.connect(hostname, FtpProviderTestCase.getSocketPort());
        } finally {
            client1.disconnect();
            client2.disconnect();
        }
    }

}
