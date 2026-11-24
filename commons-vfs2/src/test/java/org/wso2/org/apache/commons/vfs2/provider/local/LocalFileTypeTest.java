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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;

/**
 * The type, the delete, the folder create and the open of a local file, by part 3 section 3.6 with the amendment of
 * D73 and section 5.7. A failed stat or open keeps its cause, and only a name that is not there is missing.
 */
public class LocalFileTypeTest {

    @TempDir
    Path folder;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
    }

    // A new manager each time, so the object is fresh and nothing of an earlier look is cached.
    private FileObject fresh(final Path path) throws Exception {
        manager.close();
        manager = new StandardFileSystemManager();
        manager.init();
        return manager.resolveFile(path.toUri().toString());
    }

    @Test
    public void typeFollowsLinksAndOnlyAMissingNameIsImaginary() throws Exception {
        final Path file = Files.write(folder.resolve("file.txt"), "content".getBytes(StandardCharsets.UTF_8));
        final Path dir = Files.createDirectory(folder.resolve("dir"));
        final Path linkToFile = Files.createSymbolicLink(folder.resolve("link-file"), file);
        final Path linkToDir = Files.createSymbolicLink(folder.resolve("link-dir"), dir);
        final Path dangling = Files.createSymbolicLink(folder.resolve("dangling"), folder.resolve("gone"));
        final Object[][] cases = {{file, FileType.FILE}, {dir, FileType.FOLDER}, {linkToFile, FileType.FILE},
                {linkToDir, FileType.FOLDER}, {dangling, FileType.IMAGINARY}, {folder.resolve("missing"),
                FileType.IMAGINARY}};
        for (final Object[] c : cases) {
            final Path path = (Path) c[0];
            assertEquals(c[1] != FileType.IMAGINARY, fresh(path).exists(), path.toString());
            assertEquals(c[1], fresh(path).getType(), path.toString());
        }
    }

    @Test
    public void failedStatIsThrownWithItsCause() throws Exception {
        final Path loop = folder.resolve("loop");
        Files.createSymbolicLink(loop, loop);
        for (int call = 0; call < 2; call++) {
            final FileObject object = fresh(loop);
            final FileSystemException thrown = assertThrows(FileSystemException.class,
                    call == 0 ? object::exists : object::getType);
            assertEquals(Fault.TRANSPORT, Faults.of(thrown));
            assertTrue(hasCause(thrown, java.nio.file.FileSystemException.class), String.valueOf(thrown));
        }
    }

    @Test
    public void nameBelowAFileIsAFailedStatAndNotAMissingName() throws Exception {
        // The JDK gives ENOTDIR as a plain FileSystemException, which the local table counts as a transport error.
        final Path file = Files.write(folder.resolve("plain.txt"), "x".getBytes(StandardCharsets.UTF_8));
        final FileSystemException thrown = assertThrows(FileSystemException.class,
                () -> fresh(file.resolve("below")).getType());
        assertTrue(hasCause(thrown, java.nio.file.FileSystemException.class), String.valueOf(thrown));
        assertEquals(Fault.TRANSPORT, Faults.of(thrown));
    }

    @Test
    public void deleteKeepsTheCauseOfAMissingNameAndOfAFolderThatIsNotEmpty() throws Exception {
        final Path file = Files.write(folder.resolve("gone.txt"), "x".getBytes(StandardCharsets.UTF_8));
        final LocalFile object = (LocalFile) fresh(file);
        assertTrue(object.exists());
        Files.delete(file);
        final NoSuchFileException missing = assertThrows(NoSuchFileException.class, object::doDelete);
        assertEquals(Fault.MISSING, Faults.of(missing));

        final Path full = Files.createDirectory(folder.resolve("full"));
        Files.write(full.resolve("child.txt"), "x".getBytes(StandardCharsets.UTF_8));
        // The public delete skips a folder with children, so the boundary is the delete of the provider itself.
        final LocalFile dir = (LocalFile) fresh(full);
        final DirectoryNotEmptyException notEmpty = assertThrows(DirectoryNotEmptyException.class, dir::doDelete);
        assertEquals(Fault.APPLICATION, Faults.of(notEmpty));
        assertTrue(Files.exists(full.resolve("child.txt")));
        assertFalse(dir.delete());
        assertTrue(Files.exists(full.resolve("child.txt")));
    }

    @Test
    public void createFolderMakesTheMissingParents() throws Exception {
        final Path deep = folder.resolve("a").resolve("b").resolve("c");
        fresh(deep).createFolder();
        assertTrue(Files.isDirectory(deep));
        // A folder that is there already is no error.
        fresh(deep).createFolder();
    }

    @Test
    public void openOfAMissingFileStaysMissingAndARefusalKeepsItsCause() throws Exception {
        final Path file = Files.write(folder.resolve("read.txt"), "content".getBytes(StandardCharsets.UTF_8));
        final LocalFile object = (LocalFile) fresh(file);
        assertEquals(FileType.FILE, object.getType());
        Files.delete(file);
        final FileNotFoundException missing = assertThrows(FileNotFoundException.class,
                () -> object.doGetInputStream(4096));
        assertTrue(missing.getCause() instanceof NoSuchFileException);
        final FileSystemException publicMissing = assertThrows(FileSystemException.class,
                () -> object.getContent().getInputStream());
        assertTrue(publicMissing instanceof org.wso2.org.apache.commons.vfs2.FileNotFoundException,
                String.valueOf(publicMissing));
        assertEquals(Fault.MISSING, Faults.of(publicMissing));

        // A folder is no readable file, as before.
        final FileObject dir = fresh(Files.createDirectory(folder.resolve("dir")));
        assertThrows(org.wso2.org.apache.commons.vfs2.FileNotFoundException.class, () -> dir.getContent().getInputStream());

        // A refusal is no missing file. Root reads every file, so this case needs another user.
        assumeFalse("root".equals(System.getProperty("user.name")));
        final Path locked = Files.write(folder.resolve("locked.txt"), "content".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        final FileObject refused = fresh(locked);
        final FileSystemException denied = assertThrows(FileSystemException.class,
                () -> refused.getContent().getInputStream());
        assertFalse(denied instanceof org.wso2.org.apache.commons.vfs2.FileNotFoundException, String.valueOf(denied));
        assertTrue(hasCause(denied, AccessDeniedException.class), String.valueOf(denied));
        assertEquals(Fault.APPLICATION, Faults.of(denied));
        assertTrue(new File(locked.toString()).exists());
    }

    private static boolean hasCause(final Throwable error, final Class<?> type) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return true;
            }
        }
        return false;
    }
}
