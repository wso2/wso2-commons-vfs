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
package org.wso2.org.apache.commons.vfs2.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableFunction;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.VFS;
import org.junit.jupiter.api.Test;

/**
 * {@code DefaultFileContentTest} tests for bug-VFS-614. This bug involves the stream implementation closing the stream
 * after reading to the end of the buffer, which broke marking.
 */
public class DefaultFileContentTest {

    private static final String expected = "testing";

    /**
     * Test VFS-724 should be done on a website which render a page with no content size. Note the getSize() is
     * currently the value sent back by the server then zero usually means no content length attached.
     */
    @Test
    public void testGetZeroContents() throws IOException {
        final FileSystemManager fsManager = VFS.getManager();
        try (FileObject fo = fsManager.resolveFile(new File("."), "src/test/resources/test-data/size-0-file.bin");
                final FileContent content = fo.getContent()) {
            assertEquals(0, content.getSize());
            assertTrue(content.isEmpty());
            assertEquals(StringUtils.EMPTY, content.getString(StandardCharsets.UTF_8));
            assertEquals(StringUtils.EMPTY, content.getString(StandardCharsets.UTF_8.name()));
            assertArrayEquals(ArrayUtils.EMPTY_BYTE_ARRAY, content.getByteArray());
        }
    }

    private void testInputStreamBufferSize(final int bufferSize) throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            file.getContent().getInputStream(bufferSize);
        }
    }

    @Test
    public void testInputStreamBufferSize0() throws Exception {
        testInputStreamBufferSize(0);
    }

    @Test
    public void testInputStreamBufferSize1() throws Exception {
        testInputStreamBufferSize(1);
    }

    @Test
    public void testInputStreamBufferSizeNegative() {
        assertThrows(IllegalArgumentException.class, () -> testInputStreamBufferSize(-2));
    }

    @Test
    public void testInputStreamClosedInADifferentThread() throws Exception {
        testStreamClosedInADifferentThread(FileContent::getInputStream);
    }

    @Test
    public void testMarkingWhenReadingEOS() throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            try (OutputStream outputStream = file.getContent().getOutputStream()) {
                outputStream.write(expected.getBytes());
                outputStream.flush();
            }
            try (InputStream stream = file.getContent().getInputStream()) {
                assertTrue(stream.markSupported());
                stream.mark(expected.length());
                final byte[] data = new byte[expected.length()];
                assertEquals(data.length, stream.read(data));
                assertEquals(expected, new String(data, StandardCharsets.UTF_8));
                assertEquals(-1, stream.read());
                assertThrows(IOException.class, stream::reset);
                assertEquals(-1, stream.read(data));
            }
        }
    }

    @Test
    public void testMarkingWorks() throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            try (OutputStream outputStream = file.getContent().getOutputStream()) {
                outputStream.write(expected.getBytes());
                outputStream.flush();
            }
            try (InputStream stream = file.getContent().getInputStream()) {
                assertTrue(stream.markSupported());
                final byte[] data = new byte[expected.length()];
                for (int round = 0; round < 10; round++) {
                    stream.mark(data.length);
                    assertEquals(data.length, stream.read(data));
                    assertEquals(expected, new String(data, StandardCharsets.UTF_8));
                    stream.reset();
                }
            }
        }
    }

    private void testOutputStreamBufferSize(final int bufferSize) throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            file.getContent().getOutputStream(bufferSize).close();
        }
    }

    @Test
    public void testOutputStreamBufferSize0() throws Exception {
        testOutputStreamBufferSize(0);
    }

    @Test
    public void testOutputStreamBufferSize1() throws Exception {
        testOutputStreamBufferSize(1);
    }

    @Test
    public void testOutputStreamBufferSizeNegative() {
        assertThrows(IllegalArgumentException.class, () -> testOutputStreamBufferSize(-1));
    }

    @Test
    public void testOutputStreamBufferSizeNegativeWithAppendFlag() throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            assertThrows(IllegalArgumentException.class, () -> file.getContent().getOutputStream(true, -1));
        }
    }

    @Test
    public void testOutputStreamClosedInADifferentThread() throws Exception {
        testStreamClosedInADifferentThread(FileContent::getOutputStream);
    }

    private <T extends Closeable> void testStreamClosedInADifferentThread(final FailableFunction<FileContent, T, IOException> getStream) throws Exception {
        final Path temp = Files.createTempFile("temp-file-name", ".tmp");
        final FileSystemManager fileSystemManager = VFS.getManager();

        try (FileObject file = fileSystemManager.resolveFile(temp.toAbsolutePath().toString())) {
            final T stream = getStream.apply(file.getContent());
            final AtomicBoolean check = new AtomicBoolean();
            final Thread thread = new Thread(() -> {
                try {
                    stream.close();
                } catch (final IOException exception) {
                    // ignore
                }
                check.set(true);
            });
            thread.start();
            thread.join();
            assertTrue(check.get());
        }
    }

}
