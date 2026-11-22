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

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.apache.sshd.SshServer;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.FileSystemView;
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
 */
public class SftpStatErrorHandlingTestCase {

    private static final String USER = "test";

    private static final String PASSWORD = UUID.randomUUID().toString();

    /** When true, the server replies SSH_FX_FAILURE for a missing file, as FileZilla server does. */
    private static volatile boolean failureStatusForMissingFile;

    /** Message sent with the SSH_FX_FAILURE reply for a missing file. */
    private static volatile String failureMessage;

    /** When set, every STAT of a path ending with {@link #forcedStatusPath} is answered with this status code. */
    private static volatile int forcedStatus;
    private static volatile String forcedStatusPath;

    /** How many STAT requests for {@link #forcedStatusPath} are answered with {@link #forcedStatus}. */
    private static volatile int forcedStatusTimes;

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

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(new File(baseDir, "key.ser").getAbsolutePath()));
        server.setPasswordAuthenticator((user, pass, session) -> USER.equals(user) && PASSWORD.equals(pass));
        server.setFileSystemFactory(session -> new AbstractSftpProviderTestCase.TestFileSystemView(
                baseDir.getAbsolutePath(), session.getUsername()));
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
        port = server.getPort();
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
        forcedStatusTimes = Integer.MAX_VALUE;
        FORCED_STATUS_STAT_COUNT.set(0);
        STAT_DROP_PLAN.clear();
        manager = new DefaultFileSystemManager();
        manager.addProvider("sftp", new SftpFileProvider());
        manager.init();
        options = new FileSystemOptions();
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        builder.setStrictHostKeyChecking(options, "no");
        builder.setUserDirIsRoot(options, true);
        TestServerOptions.apply(options);
        builder.setDisableDetectExecChannel(options, true);
        builder.setSessionTimeoutMillis(options, 10000);
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
    public void testFailureStatusOnFirstStatIsRetried() throws FileSystemException {
        // A failure status without a cause on a connected channel, e.g. a stale reply on a channel that is out of
        // sync, must not be trusted until it is confirmed on a fresh channel.
        forcedStatus = SftpSubsystem.SSH_FX_FAILURE;
        forcedStatusPath = "/dir";
        forcedStatusTimes = 1;
        Assert.assertEquals(FileType.FOLDER, typeOf("dir"));
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
        try (FileObject file = manager.resolveFile(uri("dir"), options)) {
            Assert.assertEquals(FileType.FOLDER, file.getType());
            file.refresh();
            // Arm both cuts after resolve so the manager probe cannot recover the stat under test.
            STAT_DROP_PLAN.add(Boolean.TRUE);
            STAT_DROP_PLAN.add(Boolean.TRUE);
            final FileSystemException error = Assert.assertThrows(FileSystemException.class, file::getType);
            Assert.assertEquals(Fault.TRANSPORT, Faults.of(error));
            Assert.assertTrue(STAT_DROP_PLAN.isEmpty());
        }
    }

    /**
     * Resolves a fresh FileObject on the pooled file system for each call, as the VFS transport sender does.
     */
    private FileType typeOf(final String child) throws FileSystemException {
        final FileObject file = manager.resolveFile(uri(child), options);
        try {
            return file.getType();
        } finally {
            file.close();
            manager.getFilesCache().clear(file.getFileSystem());
        }
    }

    private String uri(final String child) {
        return String.format("sftp://%s:%s@127.0.0.1:%d/%s", USER, PASSWORD, port, child);
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
                    if (FORCED_STATUS_STAT_COUNT.incrementAndGet() <= forcedStatusTimes) {
                        sendStatus(id, forcedStatus, "Forced status");
                        return;
                    }
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
