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
package org.apache.commons.vfs2.provider.ftp.test;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.vfs2.*;
import org.apache.ftpserver.ftplet.FtpException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.sql.Timestamp;

import static org.junit.Assert.fail;

public class ListChildrenFromEmptyFolderTest {

    @BeforeClass
    public static void setUpClass() throws FtpException, IOException {
        FtpProviderTestCase.setUpClass(FtpProviderTestCase.getTestDirectory(), null);
    }

    @AfterClass
    public static void tearDownClass() throws MalformedURLException, FtpException {
        FtpProviderTestCase.tearDownClass();
    }

    private FileObject resolveRoot() throws FileSystemException {
        return VFS.getManager().resolveFile(FtpProviderTestCase.getConnectionUri()
                + "/" + new Timestamp(System.currentTimeMillis()).getTime());
    }

    @Test
    public void testListChildren() throws IOException {
        FileObject rootDir = resolveRoot();
        Assert.assertTrue(!rootDir.exists());
        rootDir.createFolder();
        Assert.assertTrue(rootDir.exists());
        Assert.assertSame(FileType.FOLDER, rootDir.getType());
        try {
            Assert.assertEquals(0, rootDir.getChildren().length);
        } catch (FileNotFolderException e) {
            fail(e.getMessage());
        }
    }
}
