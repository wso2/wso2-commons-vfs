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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;

/**
 * Part 3 section 4.6 point 10 for FTP. The provider gives the start of the window that {@code getFileSystem} carries
 * to the first connect of the wrapper. So the connect loop of proxy mode runs in the window of the probe when the
 * probe builds the file system again, and opens no window of its own.
 */
public class FtpProbeWindowTest {

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private DefaultFileSystemManager manager;

    private WindowProvider provider;

    private ServerSocket proxy;

    private final List<Long> connects = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void start() throws Exception {
        Files.write(new File(home, "small.txt").toPath(), "small file".getBytes(StandardCharsets.UTF_8));
        server = ScriptedFtpServer.plain(home);
        provider = new WindowProvider();
        manager = new DefaultFileSystemManager();
        manager.addProvider("ftp", provider);
        manager.init();
        // A proxy that takes each connect and closes it at once, so each try of the loop fails fast.
        proxy = new ServerSocket(0, 200, InetAddress.getLoopbackAddress());
        final Thread acceptor = new Thread(() -> {
            try {
                while (true) {
                    final Socket socket = proxy.accept();
                    connects.add(System.nanoTime());
                    socket.close();
                }
            } catch (final IOException e) {
                // The proxy closed.
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    public void stop() throws IOException {
        proxy.close();
        manager.close();
        server.close();
    }

    @Test
    public void windowThatIsOverAllowsOnlyTheFirstConnect() throws Exception {
        final FileName root = rootOf(5);
        assertThrows(FileSystemException.class,
                () -> provider.open(root, System.nanoTime() - TimeUnit.SECONDS.toNanos(10)));
        Thread.sleep(200);
        assertEquals(1, connects.size(), "connects " + connects);
    }

    @Test
    public void openWindowAllowsTheRetryCount() throws Exception {
        final FileName root = rootOf(5);
        final long start = System.nanoTime();
        assertThrows(FileSystemException.class, () -> provider.open(root, start));
        Thread.sleep(200);
        assertEquals(5, connects.size(), "connects " + connects);
        for (final long at : connects) {
            assertTrue(at - start < TimeUnit.SECONDS.toNanos(5), "a connect started after the window");
        }
    }

    private FileName rootOf(final int retries) throws FileSystemException {
        final String uri = server.uri("ftp", "/small.txt?proxyServer=127.0.0.1&proxyPort=" + proxy.getLocalPort()
                + "&retryCount=" + retries);
        return manager.resolveName(provider.parseUri(null, uri), FileName.ROOT_PATH);
    }

    /**
     * The FTP provider, with a way for the test to create a file system with a given start of the window, as the
     * probe of {@code findFile} does for its rebuild.
     */
    private static final class WindowProvider extends FtpFileProvider {

        FileSystem open(final FileName root, final long windowStart) throws FileSystemException {
            return getFileSystem(root, null, null, windowStart);
        }
    }
}
