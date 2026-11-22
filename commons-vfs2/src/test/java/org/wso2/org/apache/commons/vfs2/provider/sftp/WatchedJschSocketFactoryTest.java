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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedOutputStream;

/**
 * The socket factory of the JSch session: each socket is in the group before it connects, the connect has the bound,
 * and JSch writes through the watched stream.
 */
public class WatchedJschSocketFactoryTest {

    // Reserved for documentation, so a SYN to it is never answered.
    private static final String BLACK_HOLE = "192.0.2.1";

    @Test
    public void socketIsInTheGroupAndJschWritesThroughTheWatchedStream() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final SocketGroup group = new SocketGroup();
            final WatchedJschSocketFactory factory = new WatchedJschSocketFactory(group, 5000, 60000);
            final Socket socket = factory.createSocket("127.0.0.1", listener.getLocalPort());
            try {
                assertEquals(1, group.getSockets().size());
                assertSame(socket, group.getSockets().get(0));
                assertTrue(factory.getOutputStream(socket) instanceof WatchedOutputStream);
                assertSame(socket.getInputStream(), factory.getInputStream(socket));
            } finally {
                socket.close();
            }
            assertTrue(group.isEmpty());
        }
    }

    @Test
    public void connectThatIsNeverAnsweredEndsAtTheConnectTimeoutAndLeavesNothing() throws Exception {
        final SocketGroup group = new SocketGroup();
        final WatchedJschSocketFactory factory = new WatchedJschSocketFactory(group, 1000, 60000);
        final long start = System.nanoTime();
        final IOException error = assertThrows(IOException.class, () -> factory.createSocket(BLACK_HOLE, 22));
        final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(error instanceof SocketTimeoutException || error.getMessage() != null, error.toString());
        assertTrue(elapsed < 3000, "elapsed " + elapsed);
        assertTrue(group.isEmpty());
    }

    @Test
    public void groupThatIsAbortedClosesANewSocketAtOnce() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final SocketGroup group = new SocketGroup();
            group.abort("stopped");
            final WatchedJschSocketFactory factory = new WatchedJschSocketFactory(group, 5000, 60000);
            assertThrows(IOException.class, () -> factory.createSocket("127.0.0.1", listener.getLocalPort()));
            assertTrue(group.isEmpty());
        }
    }

    @Test
    public void sftpConnectToAnAddressThatNeverAnswersFailsAtTheConnectTimeout() throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, Duration.ofMillis(1500));
        try (StandardFileSystemManager manager = new StandardFileSystemManager()) {
            manager.init();
            final long start = System.nanoTime();
            final FileSystemException error = assertThrows(FileSystemException.class,
                    () -> manager.resolveFile("sftp://user:pass@" + BLACK_HOLE + ":22/in/small.txt", options));
            final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsed >= 1400 && elapsed < 4000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            boolean timeout = false;
            for (Throwable link = error; link != null; link = link.getCause()) {
                timeout |= link instanceof SocketTimeoutException;
            }
            assertTrue(timeout, "a socket timeout in the cause chain");
            assertEquals(0, manager.getOpenClientCount());
        }
    }
}
