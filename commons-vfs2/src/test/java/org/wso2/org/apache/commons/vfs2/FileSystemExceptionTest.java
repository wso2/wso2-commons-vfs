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
package org.wso2.org.apache.commons.vfs2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemException;

/**
 * Tests the {@link FileSystemException}.
 */
public class FileSystemExceptionTest {

    /**
     * Tests a {@link FileSystemException} containing info with a URL containing only the user information.
     */
    @Test
    public void testDoesNotModifyUrlsWithoutPassword() {
        final FileSystemException fse = new FileSystemException("vfs.provider/delete.error",
                "http://foo@junit.org/test.bin");
        assertEquals("http://foo@junit.org/test.bin", fse.getInfo()[0]);
    }

    /**
     * Tests a {@link FileSystemException} containing info with a URL containing a complete basic authentication.
     */
    @Test
    public void testMasksPasswordOfUrlsWithBasicAuthentication() {
        final FileSystemException fse = new FileSystemException("vfs.provider/rename.error", "file://test.bin",
                "http://foo:bar@junit.org/test.bin");

        assertEquals("file://test.bin", fse.getInfo()[0]);
        assertEquals("http://foo:***@junit.org/test.bin", fse.getInfo()[1]);
    }

    /**
     * A password with white space or a slash is masked to the last @ before the host, as the log mask does.
     */
    @Test
    public void testMasksPasswordWithWhiteSpaceOrSlash() {
        final FileSystemException fse = new FileSystemException("vfs.provider/rename.error",
                "sftp://user:my pass@host/in", "sftp://rig:pa/ss@host/in");
        assertEquals("sftp://user:***@host/in", fse.getInfo()[0]);
        assertEquals("sftp://rig:***@host/in", fse.getInfo()[1]);
        final FileSystemException more = new FileSystemException("vfs.provider/rename.error",
                "sftp://user:pass @host/in", "smb2://John Smith:pw@server/share");
        assertEquals("sftp://user:***@host/in", more.getInfo()[0]);
        assertEquals("smb2://John Smith:***@server/share", more.getInfo()[1]);
    }

    // Runs the work on a thread with a small stack, so a pattern that recurses once per word fails here.
    private static String onSmallStack(final Callable<String> work) throws InterruptedException {
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread thread = new Thread(null, () -> {
            try {
                result.set(work.call());
            } catch (final Throwable error) {
                result.set(error);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (result.get() instanceof Throwable) {
            throw new AssertionError("The mask failed on a small stack", (Throwable) result.get());
        }
        return (String) result.get();
    }

    @Test
    public void testManyWordsWithNoSlashDoNotOverflowASmallStack() throws Exception {
        final StringBuilder text = new StringBuilder("Could not connect to sftp://host:22");
        for (int i = 0; i < 5000; i++) {
            text.append(" word");
        }
        assertEquals(text.toString(), onSmallStack(() -> new FileSystemException("vfs.provider/rename.error",
                text.toString()).getInfo()[0]));
    }

    /**
     * Tests a {@link FileSystemException} containing info with a nested URL containing a complete basic authentication.
     */
    @Test
    public void testProperDetectionOfUrl() {
        final FileSystemException fse = new FileSystemException("vfs.provider/delete.error",
                "zip:http://foo:bar@junit.org/test.bin");
        assertEquals("zip:http://foo:***@junit.org/test.bin", fse.getInfo()[0]);
    }


    /**
     * Tests that a secret in the query of a URL, such as the proxy password of FTP, is masked too.
     */
    @Test
    public void testMasksSecretQueryValues() {
        final FileSystemException fse = new FileSystemException("vfs.provider/delete.error",
                "ftp://foo:bar@junit.org/test.bin?proxyPassword=secret&passive=true");
        assertEquals("ftp://foo:***@junit.org/test.bin?proxyPassword=***&passive=true", fse.getInfo()[0]);
    }

}
