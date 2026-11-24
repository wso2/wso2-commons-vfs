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
package org.wso2.vfs.shared.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.local.DefaultLocalFileProvider;

public class LocalLookTest {
    @Test
    public void localLookReportsEachOutcome(@TempDir final Path dir) throws Exception {
        final Path folder = Files.createDirectory(dir.resolve("d"));
        final Path file = Files.createFile(folder.resolve("f.csv"));
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("file", new DefaultLocalFileProvider());
            manager.init();
            try (FileObject found = manager.resolveFile(file.toUri());
                    FileObject missing = manager.resolveFile(folder.resolve("absent").toUri())) {
                Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("---------"));
                try {
                    final FileSystemException error = assertThrows(FileSystemException.class, () -> AtomicOps.existsNow(found));
                    assertTrue(error.getCause() instanceof AccessDeniedException);
                    assertEquals(Fault.APPLICATION, Faults.of(error));
                } finally {
                    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
                assertTrue(AtomicOps.existsNow(found));
                assertFalse(AtomicOps.existsNow(missing));
                try (FileObject child = manager.resolveFile(file.resolve("x").toUri())) {
                    System.out.println("look below file returned " + AtomicOps.existsNow(child));
                } catch (final FileSystemException error) {
                    System.out.println("look below file threw " + error.getClass().getName() + " caused by " + error.getCause());
                }
            }
        }
    }
}
