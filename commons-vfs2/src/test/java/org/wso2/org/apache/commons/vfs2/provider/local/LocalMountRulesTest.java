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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;

/**
 * What part 3 section 3.6 rules for a local path and a mount. The JVM holds no socket for a local path, so the file
 * system has no client to count, its abort does nothing, and it reports no bound.
 */
public class LocalMountRulesTest {

    @TempDir
    File folder;

    private StandardFileSystemManager manager;

    private String uri;

    @BeforeEach
    public void start() throws Exception {
        final File file = new File(folder, "small.txt");
        Files.write(file.toPath(), "small file".getBytes(StandardCharsets.UTF_8));
        uri = file.toURI().toString();
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
    }

    @Test
    public void abortOfALocalFileSystemDoesNothing() throws Exception {
        final FileObject file = manager.resolveFile(uri);
        final AbstractFileSystem fileSystem = (AbstractFileSystem) file.getFileSystem();
        assertTrue(fileSystem instanceof LocalFileSystem);
        assertEquals(0, fileSystem.getHandedOutCount());
        assertEquals(0, fileSystem.getOpenClientCount());

        fileSystem.abort();
        assertTrue(fileSystem.isAliveLocally());
        assertEquals("small file", new String(file.getContent().getByteArray(), StandardCharsets.UTF_8));
        assertSame(fileSystem, manager.resolveFile(uri).getFileSystem());
    }

    @Test
    public void localFileSystemReportsNoBound() throws Exception {
        final AbstractFileSystem fileSystem = (AbstractFileSystem) manager.resolveFile(uri).getFileSystem();
        assertEquals(EffectiveBounds.of("file", new FileSystemOptions()), fileSystem.getEffectiveBounds());
        assertEquals("file", fileSystem.getEffectiveBounds().getScheme());
    }

    @Test
    public void abortAndCloseOfALocalFileSystemReturnsAtOnceAndTheNextResolveBuildsANewOne() throws Exception {
        final AbstractFileSystem fileSystem = (AbstractFileSystem) manager.resolveFile(uri).getFileSystem();
        final long start = System.nanoTime();
        manager.abortAndCloseFileSystem(fileSystem);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
        final FileObject again = manager.resolveFile(uri);
        assertNotSame(fileSystem, again.getFileSystem());
        assertEquals("small file", new String(again.getContent().getByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    public void abortAllOfAManagerWithALocalFileSystemReturnsAtOnce() throws Exception {
        manager.resolveFile(uri).getContent().getByteArray();
        assertTrue(manager.isAliveLocally());
        final long start = System.nanoTime();
        manager.abortAll();
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000);
        assertFalse(manager.isAliveLocally());
    }
}
