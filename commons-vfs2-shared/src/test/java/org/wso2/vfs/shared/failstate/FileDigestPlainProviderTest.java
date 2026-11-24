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
package org.wso2.vfs.shared.failstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.ram.RamFileProvider;
import org.wso2.vfs.shared.lock.LockIo;

/**
 * A provider with no atomic calls, such as RAM or the jcifs SMB of the base library, gets the plain look of the base
 * library for the stat around a digest read, so its files can still be digested.
 */
class FileDigestPlainProviderTest {

    private static void write(final DefaultFileSystemManager manager, final String uri, final String text)
            throws Exception {
        try (FileObject file = manager.resolveFile(uri);
                OutputStream out = file.getContent().getOutputStream()) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void fileOfAProviderWithNoAtomicCallsIsDigested() throws Exception {
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("ram", new RamFileProvider());
            manager.init();
            final String folder = "ram:///in/" + UUID.randomUUID() + "/";
            write(manager, folder + "a.txt", "content");
            write(manager, folder + "b.txt", "content");
            write(manager, folder + "c.txt", "other content");
            final LockIo io = new LockIo(manager, null);

            final DigestResult a = FileDigest.of(io, folder + "a.txt");
            assertEquals(DigestResult.Kind.DONE, a.kind, String.valueOf(a.failure));
            assertNotNull(a.digest);
            assertEquals(7, a.count);
            assertEquals(a.digest, FileDigest.of(io, folder + "b.txt").digest);
            assertNotEquals(a.digest, FileDigest.of(io, folder + "c.txt").digest);
            assertEquals(DigestResult.Kind.GONE, FileDigest.of(io, folder + "missing.txt").kind);
        }
    }
}
