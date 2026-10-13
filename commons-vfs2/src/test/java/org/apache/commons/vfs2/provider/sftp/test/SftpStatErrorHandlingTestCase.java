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
package org.apache.commons.vfs2.provider.sftp.test;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.apache.commons.vfs2.provider.sftp.SftpFileProvider;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.apache.commons.vfs2.util.FreeSocketPortUtil;
import org.apache.sshd.SshServer;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.FileSystemView;
import org.apache.sshd.server.PasswordAuthenticator;
import org.apache.sshd.server.filesystem.NativeFileSystemFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.sftp.SftpSubsystem;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests how SftpFileObject interprets failed STAT requests: a status reply from the server for a missing file must be
 * treated as a non-existent file, while a broken connection (e.g. a stale pooled session) must be recovered or
 * reported as an error, never as a non-existent file.
 * <p>
 * Run this test when changing the SFTP stat handling in {@code SftpFileObject} / {@code SftpClient} or upgrading
 * JSch; run it from the IDE, as tests are skipped in the Maven build of this module. The embedded test server only
 * supports legacy algorithms, so add the JVM options
 * {@code -Djsch.kex=diffie-hellman-group14-sha1 -Djsch.server_host_key=ssh-dss}.
 */
public class SftpStatErrorHandlingTestCase {

    private static final String USER = "test";

    /** When true, the server replies SSH_FX_FAILURE for a missing file, as FileZilla server does. */
    private static volatile boolean failureStatusForMissingFile;

    /** Message sent with the SSH_FX_FAILURE reply for a missing file. */
    private static volatile String failureMessage;

    /** When set, every STAT of a path ending with {@link #forcedStatusPath} is answered with this status code. */
    private static volatile int forcedStatus;
    private static volatile String forcedStatusPath;

    /** Number of STAT requests received for {@link #forcedStatusPath}. */
    private static final AtomicInteger FORCED_STATUS_STAT_COUNT = new AtomicInteger();

    /** One entry per upcoming STAT request: true closes the connection instead of replying. */
    private static final Queue<Boolean> STAT_DROP_PLAN = new ConcurrentLinkedQueue<>();

    private static SshServer server;
    private static int port;
    private static File baseDir;

    private DefaultFileSystemManager manager;
    private FileSystemOptions options;

    @BeforeClass
    public static void startServer() throws IOException {
        baseDir = new File(System.getProperty("java.io.tmpdir"), "vfs-sftp-stat-" + System.nanoTime());
        Assert.assertTrue(new File(baseDir, "dir").mkdirs());
        port = FreeSocketPortUtil.findFreeLocalPort();

        server = SshServer.setUpDefaultServer();
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File(baseDir, "key.ser").getAbsolutePath()));
        server.setPasswordAuthenticator(new PasswordAuthenticator() {
            @Override
            public boolean authenticate(final String username, final String password, final ServerSession session) {
                return username != null && username.equals(password);
            }
        });
        server.setFileSystemFactory(new NativeFileSystemFactory());
        server.setSubsystemFactories(Collections.<NamedFactory<Command>>singletonList(new NamedFactory<Command>() {
            @Override
            public String getName() {
                return "sftp";
            }

            @Override
            public Command create() {
                return new FaultInjectingSftpSubsystem();
            }
        }));
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
        FileUtils.deleteQuietly(baseDir);
    }

    @Before
    public void setUp() throws FileSystemException {
        failureStatusForMissingFile = false;
        failureMessage = "STAT error";
        forcedStatus = 0;
        forcedStatusPath = null;
        FORCED_STATUS_STAT_COUNT.set(0);
        STAT_DROP_PLAN.clear();
        manager = new DefaultFileSystemManager();
        manager.addProvider("sftp", new SftpFileProvider());
        manager.init();
        options = new FileSystemOptions();
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        builder.setStrictHostKeyChecking(options, "no");
        builder.setUserDirIsRoot(options, false);
        builder.setTimeout(options, 10000);
    }

    @After
    public void tearDown() {
        manager.close();
    }

    @Test
    public void testMissingFileReportedAsNoSuchFile() throws FileSystemException {
        Assert.assertEquals(FileType.IMAGINARY, typeOf("missing.lock"));
    }

    @Test
    public void testMissingFileReportedAsFailure() throws FileSystemException {
        failureStatusForMissingFile = true;
        Assert.assertEquals(FileType.IMAGINARY, typeOf("missing.lock"));
    }

    @Test
    public void testMissingFileReportedAsFailureWithoutMessage() throws FileSystemException {
        failureStatusForMissingFile = true;
        failureMessage = "";
        Assert.assertEquals(FileType.IMAGINARY, typeOf("missing.lock"));
    }

    @Test
    public void testOtherStatusIsRetriedAndReportedAsError() throws FileSystemException {
        forcedStatus = SftpSubsystem.SSH_FX_PERMISSION_DENIED;
        forcedStatusPath = "/dir";
        try {
            final FileType type = typeOf("dir");
            Assert.fail("A permission denied status must not be reported as a file type, but got " + type);
        } catch (final FileSystemException expected) {
            // expected
        }
        Assert.assertEquals("STAT must be retried once on a new channel", 2, FORCED_STATUS_STAT_COUNT.get());
    }

    @Test
    public void testBrokenPooledConnectionIsRecovered() throws FileSystemException {
        Assert.assertEquals(FileType.FOLDER, typeOf("dir"));
        STAT_DROP_PLAN.add(Boolean.TRUE);
        Assert.assertEquals(FileType.FOLDER, typeOf("dir"));
    }

    @Test
    public void testBrokenPooledConnectionIsRecoveredForMissingFile() throws FileSystemException {
        failureStatusForMissingFile = true;
        Assert.assertEquals(FileType.FOLDER, typeOf("dir"));
        STAT_DROP_PLAN.add(Boolean.TRUE);
        Assert.assertEquals(FileType.IMAGINARY, typeOf("missing.lock"));
    }

    @Test
    public void testConnectionBrokenAgainOnRetryIsNotReportedAsMissing() throws FileSystemException {
        Assert.assertEquals(FileType.FOLDER, typeOf("dir"));
        // Pooled session dies; the new session's working directory check succeeds; the retried STAT dies again.
        STAT_DROP_PLAN.add(Boolean.TRUE);
        STAT_DROP_PLAN.add(Boolean.FALSE);
        STAT_DROP_PLAN.add(Boolean.TRUE);
        try {
            final FileType type = typeOf("dir");
            Assert.fail("A broken connection must not be reported as a file type, but got " + type);
        } catch (final FileSystemException expected) {
            // expected
        }
    }

    /**
     * Resolves a fresh FileObject on the pooled file system for each call, as the VFS transport sender does.
     */
    private FileType typeOf(final String child) throws FileSystemException {
        // URI-encoded absolute path that always starts with '/', also on Windows (e.g. /C:/...)
        String basePath = baseDir.toURI().getRawPath();
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        final String uri = String.format("sftp://%s:%s@localhost:%d%s%s", USER, USER, port, basePath, child);
        final FileObject file = manager.resolveFile(uri, options);
        try {
            return file.getType();
        } finally {
            file.close();
            manager.getFilesCache().clear(file.getFileSystem());
        }
    }

    /**
     * SFTP subsystem that can reply SSH_FX_FAILURE for missing files, answer STAT with a given status and close the
     * connection on STAT requests.
     */
    private static class FaultInjectingSftpSubsystem extends SftpSubsystem {
        private ServerSession serverSession;
        private FileSystemView view;

        @Override
        public void setSession(final ServerSession session) {
            super.setSession(session);
            this.serverSession = session;
        }

        @Override
        public void setFileSystemView(final FileSystemView view) {
            super.setFileSystemView(view);
            this.view = view;
        }

        @Override
        protected void process(final Buffer buffer) throws IOException {
            final int rpos = buffer.rpos();
            buffer.getInt();
            final int type = buffer.getByte();
            final int id = buffer.getInt();
            if (type == SSH_FXP_STAT || type == SSH_FXP_LSTAT) {
                final String path = buffer.getString();
                if (Boolean.TRUE.equals(STAT_DROP_PLAN.poll())) {
                    serverSession.close(true);
                    return;
                }
                if (forcedStatusPath != null && path.endsWith(forcedStatusPath)) {
                    FORCED_STATUS_STAT_COUNT.incrementAndGet();
                    sendStatus(id, forcedStatus, "Forced status");
                    return;
                }
                if (failureStatusForMissingFile && !view.getFile(path).doesExist()) {
                    sendStatus(id, SSH_FX_FAILURE, failureMessage);
                    return;
                }
            }
            buffer.rpos(rpos);
            super.process(buffer);
        }
    }
}
