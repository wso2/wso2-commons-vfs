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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/**
 * The timeout ladder on the SFTP session: the builder keeps the values as durations, the factory applies what the
 * bounds in effect give, and the URI options work.
 */
public class SftpTimeoutLadderTest {

    private static final SftpFileSystemConfigBuilder BUILDER = SftpFileSystemConfigBuilder.getInstance();

    private static SftpTestServer server;

    @BeforeAll
    public static void startServer() throws Exception {
        server = new SftpTestServer();
        server.write("in/small.txt", "small");
    }

    @AfterAll
    public static void stopServer() throws Exception {
        server.close();
    }

    @AfterEach
    public void clearProperties() {
        System.clearProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY);
        System.clearProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY);
        System.clearProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY);
    }

    static Session sessionOf(final FileObject file) throws Exception {
        return SftpTestServer.sessionOf((SftpFileSystem) file.getFileSystem());
    }

    private static StandardFileSystemManager manager() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        return manager;
    }

    @Test
    public void oldSetterStoresADurationAndOldGetterReadsItsMillis() {
        final FileSystemOptions options = new FileSystemOptions();
        assertNull(BUILDER.getTimeout(options));
        assertFalse(BUILDER.isSessionTimeoutSet(options));
        BUILDER.setTimeout(options, 20000);
        assertEquals(Duration.ofMillis(20000), BUILDER.getSessionTimeout(options));
        assertEquals(Integer.valueOf(20000), BUILDER.getTimeout(options));
        assertTrue(BUILDER.isSessionTimeoutSet(options));
    }

    @Test
    public void guardsTellAnExplicitZeroFromNoValue() {
        final FileSystemOptions options = new FileSystemOptions();
        assertFalse(BUILDER.isConnectTimeoutSet(options));
        assertFalse(BUILDER.isKeepAliveCountMaxSet(options));
        assertEquals(Duration.ZERO, BUILDER.getConnectTimeout(options));
        BUILDER.setConnectTimeout(options, Duration.ZERO);
        BUILDER.setKeepAliveCountMax(options, 0);
        assertTrue(BUILDER.isConnectTimeoutSet(options));
        assertTrue(BUILDER.isKeepAliveCountMaxSet(options));
    }

    @Test
    public void factoryPutsTheBoundsInEffectOnTheSession() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(20000));
        BUILDER.setKeepAliveCountMax(options, 2);
        final Session session = new JSch().getSession("user", "localhost", 22);
        SftpClientFactory.applyBounds(session, EffectiveBounds.of("sftp", options));
        assertEquals(20000, session.getTimeout());
        assertEquals(2, session.getServerAliveCountMax());

        final Session plain = new JSch().getSession("user", "localhost", 22);
        SftpClientFactory.applyBounds(plain, EffectiveBounds.of("sftp", new FileSystemOptions()));
        assertEquals(30000, plain.getTimeout());
        assertEquals(3, plain.getServerAliveCountMax());
    }

    @Test
    public void propertyOfZeroAndADerivedCountLeaveTheValuesOfJsch() throws Exception {
        System.setProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "0");
        final Session session = new JSch().getSession("user", "localhost", 22);
        SftpClientFactory.applyBounds(session, EffectiveBounds.of("sftp", new FileSystemOptions()));
        assertEquals(0, session.getTimeout());
        clearProperties();

        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(150000));
        final Session derived = new JSch().getSession("user", "localhost", 22);
        SftpClientFactory.applyBounds(derived, EffectiveBounds.of("sftp", options));
        assertEquals(150000, derived.getTimeout());
        assertEquals(1, derived.getServerAliveCountMax());
    }

    @Test
    public void activeTimeoutWithAKeepAliveCountOfZeroWarnsOncePerCopy() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(20000));
        BUILDER.setKeepAliveCountMax(options, 0);
        // Another test of this JVM may have given the warning of this copy already.
        final Field warned = SftpClientFactory.class.getDeclaredField("BARE_TIMEOUT_WARNED");
        warned.setAccessible(true);
        ((AtomicBoolean) warned.get(null)).set(false);
        try (LogCapture log = new LogCapture(SftpClientFactory.class)) {
            SftpClientFactory.applyBounds(new JSch().getSession("user", "localhost", 22),
                    EffectiveBounds.of("sftp", options));
            SftpClientFactory.applyBounds(new JSch().getSession("user", "localhost", 22),
                    EffectiveBounds.of("sftp", options));
            final List<String> warnings = log.messages(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("keep alive count is 0"), warnings.get(0));
        }
    }

    @Test
    public void boundsInEffectEqualTheValuesOfALiveSession() throws Exception {
        final FileSystemOptions set = SftpTestServer.options();
        BUILDER.setSessionTimeout(set, Duration.ofMillis(20000));
        BUILDER.setKeepAliveCountMax(set, 2);
        BUILDER.setConnectTimeout(set, Duration.ofMillis(15000));
        System.setProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "25000");
        System.setProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "4");
        final FileSystemOptions byProperty = SftpTestServer.options();
        final FileSystemOptions none = SftpTestServer.options();
        try (StandardFileSystemManager manager = manager()) {
            final FileObject first = manager.resolveFile(server.uri("in/small.txt"), set);
            assertTrue(first.exists());
            final EffectiveBounds bounds = EffectiveBounds.of("sftp", set);
            assertEquals(bounds.getSessionTimeout().getValue(), sessionOf(first).getTimeout());
            assertEquals(bounds.getKeepAliveCount().getValue(), sessionOf(first).getServerAliveCountMax());
            assertEquals(Source.ENDPOINT, bounds.getSessionTimeout().getSource());
            // The live file system tells the same bounds, value and source, with no call to the server.
            assertEquals(bounds, ((SftpFileSystem) first.getFileSystem()).getEffectiveBounds());
            assertEquals(Source.ENDPOINT, ((SftpFileSystem) first.getFileSystem()).getEffectiveBounds()
                .getConnectTimeout().getSource());

            final FileObject second = manager.resolveFile(server.uri("in/small.txt"), byProperty);
            assertTrue(second.exists());
            assertEquals(25000, sessionOf(second).getTimeout());
            assertEquals(4, sessionOf(second).getServerAliveCountMax());
            assertEquals(Source.SYSTEM_PROPERTY, EffectiveBounds.of("sftp", byProperty).getSessionTimeout().getSource());
            assertEquals(EffectiveBounds.of("sftp", byProperty),
                ((SftpFileSystem) second.getFileSystem()).getEffectiveBounds());
        }
        clearProperties();
        try (StandardFileSystemManager manager = manager()) {
            final FileObject third = manager.resolveFile(server.uri("in/small.txt"), none);
            assertTrue(third.exists());
            assertEquals(30000, sessionOf(third).getTimeout());
            assertEquals(3, sessionOf(third).getServerAliveCountMax());
            final EffectiveBounds live = ((SftpFileSystem) third.getFileSystem()).getEffectiveBounds();
            assertEquals(EffectiveBounds.of("sftp", none), live);
            assertEquals(sessionOf(third).getTimeout(), live.getSessionTimeout().getValue());
            assertEquals("sftp", live.getScheme());
        }
    }

    @Test
    public void uriTimeoutIsStoredAsADurationAndConnects() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt?timeout=20000"),
                    SftpTestServer.options());
            assertTrue(file.exists());
            assertEquals(Duration.ofMillis(20000), BUILDER.getSessionTimeout(file.getFileSystem().getFileSystemOptions()));
            assertEquals(20000, sessionOf(file).getTimeout());
        }
    }

    @Test
    public void uriConnectTimeoutAndKeepAliveCountConnectAndAnExplicitZeroIsKept() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject zero = manager.resolveFile(server.uri("in/small.txt?connectTimeout=0"),
                    SftpTestServer.options());
            assertTrue(zero.exists());
            final FileSystemOptions options = zero.getFileSystem().getFileSystemOptions();
            assertTrue(BUILDER.isConnectTimeoutSet(options));
            assertEquals(0, EffectiveBounds.of("sftp", options).getConnectTimeout().getValue());
            assertEquals(Source.ENDPOINT, EffectiveBounds.of("sftp", options).getConnectTimeout().getSource());

            final FileObject count = manager.resolveFile(server.uri("in/small.txt?keepAliveCount=2"),
                    SftpTestServer.options());
            assertTrue(count.exists());
            assertEquals(2, sessionOf(count).getServerAliveCountMax());
        }
    }

    @Test
    public void uriTimeoutDoesNotReplaceADurationThatTheOptionsHold() throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(25000));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt?timeout=20000"), options);
            assertTrue(file.exists());
            assertEquals(25000, sessionOf(file).getTimeout());
        }
    }

    @Test
    public void uriTimeoutThatIsNotANumberIsLoggedAndStoresNothing() throws Exception {
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(StandardFileSystemManager.class)) {
            final FileSystemOptions options = SftpTestServer.options();
            final FileObject file = manager.resolveFile(server.uri("in/small.txt?timeout=soon"), options);
            assertTrue(file.exists());
            assertFalse(BUILDER.isSessionTimeoutSet(file.getFileSystem().getFileSystemOptions()));
            assertEquals(30000, sessionOf(file).getTimeout());
            assertTrue(log.messages(Level.WARN).contains("Invalid timeout soon specified in FileURI."),
                    log.messages(Level.WARN).toString());
        }
    }
}
