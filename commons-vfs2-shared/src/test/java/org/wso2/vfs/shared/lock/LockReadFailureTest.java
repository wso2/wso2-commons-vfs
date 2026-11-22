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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileObject;

class LockReadFailureTest {
    @TempDir Path folder;

    private long descriptors() throws IOException {
        try (Stream<Path> entries = Files.list(Paths.get("/proc/self/fd"))) {
            return entries.filter(path -> {
                try {
                    return Files.readSymbolicLink(path).startsWith(folder);
                } catch (IOException closed) {
                    return false;
                }
            }).count();
        }
    }

    @Test
    void failedSecondReadClosesBothObjectsOneThousandTimes() throws Exception {
        Path lock = Files.write(folder.resolve("read.lock"), new byte[300]);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closedStreams = new AtomicInteger();
        AtomicInteger closedFiles = new AtomicInteger();
        FileContent content = (FileContent) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{FileContent.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getInputStream")) { throw new AssertionError(method); }
                    InputStream input = Files.newInputStream(lock);
                    opens.incrementAndGet();
                    return new FilterInputStream(input) {
                        private boolean first = true;
                        @Override public int read(byte[] buffer, int start, int size) throws IOException {
                            if (!first) { throw new IOException("Failed second read"); }
                            first = false;
                            return super.read(buffer, start, 1);
                        }
                        @Override public void close() throws IOException {
                            super.close();
                            closedStreams.incrementAndGet();
                        }
                    };
                });
        long baseline = descriptors();
        for (int attempt = 0; attempt < 1000; attempt++) {
            FileObject file = (FileObject) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{FileObject.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getContent")) { return content; }
                        if (method.getName().equals("close")) { closedFiles.incrementAndGet(); return null; }
                        throw new AssertionError(method);
                    });
            assertThrows(IOException.class, () -> LockValue.read(file));
        }
        assertEquals(1000, opens.get());
        assertEquals(opens.get(), closedStreams.get());
        assertEquals(opens.get(), closedFiles.get());
        assertEquals(baseline, descriptors());
    }
}
