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
package org.wso2.org.apache.commons.vfs2.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.auth.StaticUserAuthenticator;
import org.wso2.org.apache.commons.vfs2.impl.DecoratedFileObject;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject.EntryRelation;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileObject;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileObject;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/** Compares namespace views using local provider facts. These file systems have no connected client. */
public class RemoteMoveIdentityTest {
    private static final class View {
        private final GenericFileName root;
        private final AbstractFileSystem fs;

        View(String scheme, int port, String rootPath, boolean loginRoot, boolean opaqueLogin) {
            this(scheme, "localhost", port, rootPath, loginRoot, opaqueLogin);
        }

        View(String scheme, String host, int port, String rootPath, boolean loginRoot, boolean opaqueLogin) {
            this(scheme, host, port, rootPath, loginRoot, opaqueLogin, new FileSystemOptions());
        }

        View(String scheme, String host, int port, String rootPath, boolean loginRoot, boolean opaqueLogin,
                FileSystemOptions options) {
            root = new GenericFileName(scheme, host, port, port, "reader", null, rootPath, FileType.FOLDER) { };
            if (opaqueLogin) {
                DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(options,
                        new StaticUserAuthenticator(null, "reader", "fixture"));
            }
            if ("ftp".equals(scheme)) {
                FtpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, loginRoot);
                fs = new FtpFileSystem(root, null, options);
            } else {
                SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(options, loginRoot);
                fs = new SftpFileSystem(root, mock(Session.class), new SocketGroup(), options) { };
            }
        }

        AbstractFileObject<?> entry(String path) throws FileSystemException {
            AbstractFileName name = (AbstractFileName) root.createName(path, FileType.FILE);
            if (fs instanceof FtpFileSystem) {
                return new FtpFileObject(name, (FtpFileSystem) fs, root) { };
            }
            return new SftpFileObject(name, (SftpFileSystem) fs) { };
        }
    }

    private void check(String protocol) throws Exception {
        View server = new View(protocol, 1022, "/", false, false);
        View login = new View(protocol, 1022, "/", true, false);
        AbstractFileObject<?> absolute = server.entry("/users/reader/work/report");
        AbstractFileObject<?> relative = login.entry("/work/report");
        for (boolean reverse : new boolean[] {false, true}) {
            AbstractFileObject<?> from = spy(reverse ? relative : absolute);
            AbstractFileObject<?> overlap = reverse ? absolute : relative;
            AbstractFileObject<?> other = (reverse ? server : login).entry("/archive/destination");
            assertEquals(AbstractFileObject.EntryRelation.UNKNOWN, from.entryRelation(overlap));
            assertTrue(overlap.isUnsafeTempFor(from, other));
            assertTrue(overlap.isUnsafeTempFor(other, from));
            doReturn(true).when(from).isWriteable();
            doReturn(FileType.FILE).when(from).getType();
            doThrow(new AssertionError("staging opened before validation")).when(from).getContent();
            assertFalse(assertThrows(MoveFailedException.class, () -> from.moveTo(overlap, new MoveOptions(true, null, null, null, null))).isTargetExists());
            assertFalse(assertThrows(MoveFailedException.class, () -> from.moveTo(other, overlap)).isTargetExists());
            verify(from, never()).getContent();
        }
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, relative.entryRelation(server.entry("/prefixwork/report")));
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, relative.entryRelation(server.entry("/work/other")));
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, absolute.entryRelation(server.entry("/work/report")));
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, relative.entryRelation(
                new View(protocol, 1023, "/", false, false).entry("/users/reader/work/report")));
        assertEquals(AbstractFileObject.EntryRelation.SAME, relative.entryRelation(
                new View(protocol, 1022, "/", true, false).entry("/work/report")));
        assertEquals(AbstractFileObject.EntryRelation.UNKNOWN, absolute.entryRelation(
                new View(protocol, 1022, "/", false, true).entry("/work/report")));
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, relative.entryRelation(
                new View(protocol, 1022, "/", true, true).entry("/archive/other")));
        assertEquals(AbstractFileObject.EntryRelation.SAME, relative.entryRelation(
                new View(protocol, 1022, "/virtual", true, false).entry("/virtual/work/report")));
        assertEquals(AbstractFileObject.EntryRelation.DISTINCT, absolute.entryRelation(
                new View(protocol, 1022, "/archive", false, false).entry("/archive/work/report")));
    }

    @Test
    void publicIdentityComparesTimeoutViewsAndPreservesUncertainty() throws Exception {
        for (String protocol : new String[] {"ftp", "sftp"}) {
            View first = new View(protocol, 1022, "/", false, false);
            FileSystemOptions options = new FileSystemOptions();
            Duration timeout = Duration.ofSeconds(3);
            if ("ftp".equals(protocol)) {
                FtpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, timeout);
            } else {
                SftpFileSystemConfigBuilder.getInstance().setConnectTimeout(options, timeout);
            }
            View second = new View(protocol, "localhost", 1022, "/", false, false, options);
            FileObject source = first.entry("/report");
            FileObject alias = new DecoratedFileObject(new DecoratedFileObject(second.entry("/report")));
            assertEquals(EntryRelation.SAME, AbstractFileObject.compareEntries(source, alias));
            assertEquals(EntryRelation.SAME, AbstractFileObject.compareEntries(alias, source));
            assertEquals(EntryRelation.DISTINCT, AbstractFileObject.compareEntries(source, second.entry("/other")));
            assertEquals(EntryRelation.DISTINCT, AbstractFileObject.compareEntries(source,
                    new View(protocol, 1023, "/", false, false).entry("/report")));
            assertEquals(EntryRelation.UNKNOWN, AbstractFileObject.compareEntries(source,
                    new View(protocol, 1022, "/", false, true).entry("/report")));
        }
    }

    @Test
    void publicIdentityLeavesOpaqueObjectsUnknownAndPropagatesProviderErrors() throws Exception {
        AbstractFileObject<?> source = spy(new View("sftp", 1022, "/", false, false).entry("/report"));
        FileObject target = new View("ftp", 1022, "/", false, false).entry("/report");
        assertEquals(EntryRelation.DISTINCT, AbstractFileObject.compareEntries(source, target));
        assertEquals(EntryRelation.DISTINCT, AbstractFileObject.compareEntries(target, source));
        FileObject opaque = mock(FileObject.class);
        assertEquals(EntryRelation.UNKNOWN, AbstractFileObject.compareEntries(source, new DecoratedFileObject(opaque)));
        assertEquals(EntryRelation.UNKNOWN, AbstractFileObject.compareEntries(opaque, target));
        verifyNoInteractions(opaque);

        FileSystemException failure = new FileSystemException("identity check failed");
        doThrow(failure).when(source).entryRelation(target);
        assertSame(failure, assertThrows(FileSystemException.class, () -> AbstractFileObject.compareEntries(
                new DecoratedFileObject(source), new DecoratedFileObject(target))));
    }

    @Test
    void numericEndpointEquivalenceRetainsPortScopeAndNamespace() throws Exception {
        for (String protocol : new String[] {"ftp", "sftp"}) {
            View compact = new View(protocol, "127.1", 1022, "/", false, false);
            AbstractFileObject<?> entry = compact.entry("/doc");
            for (String spelling : new String[] {"127.0.0.1", "127.0.1", "2130706433", "[::ffff:127.0.0.1]"}) {
                assertEquals(AbstractFileObject.EntryRelation.SAME, entry.entryRelation(
                        new View(protocol, spelling, 1022, "/", false, false).entry("/doc")), spelling);
            }
            assertEquals(AbstractFileObject.EntryRelation.DISTINCT, entry.entryRelation(
                    new View(protocol, "127.0.0.1", 1023, "/", false, false).entry("/doc")));
            assertEquals(AbstractFileObject.EntryRelation.DISTINCT, entry.entryRelation(
                    new View(protocol, "127.0.0.2", 1022, "/", false, false).entry("/doc")));
            assertEquals(AbstractFileObject.EntryRelation.UNKNOWN, entry.entryRelation(
                    new View(protocol, "127.0.0.1", 1022, "/", true, false).entry("/doc")));
            assertEquals(AbstractFileObject.EntryRelation.DISTINCT, entry.entryRelation(
                    new View(protocol, "localhost", 1022, "/", false, false).entry("/doc")));
            AbstractFileObject<?> scoped = new View(protocol, "[fe80::1%3]", 1022, "/", false, false).entry("/doc");
            assertEquals(AbstractFileObject.EntryRelation.SAME, scoped.entryRelation(
                    new View(protocol, "[fe80:0:0:0:0:0:0:1%3]", 1022, "/", false, false).entry("/doc")));
            assertEquals(AbstractFileObject.EntryRelation.DISTINCT, scoped.entryRelation(
                    new View(protocol, "[fe80::1%4]", 1022, "/", false, false).entry("/doc")));
        }
    }

    @Test
    void ftpWorkingDirectoryViews() throws Exception {
        check("ftp");
    }

    @Test
    void sftpWorkingDirectoryViews() throws Exception {
        check("sftp");
    }
}
