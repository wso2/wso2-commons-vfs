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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;

/**
 * The unit half of the abort test with one real file system of each kind in one manager: SFTP, FTP and SMB2 on the
 * lanes 0 of the rigs, and a local file. The endpoints come from the system properties rev7.sftp, rev7.ftp, rev7.smb2
 * and rev7.local, and the test is skipped without them.
 * <p>
 * Helper threads hold the monitor of the manager, of the files cache, of each provider with its map and its list of
 * components, and of each file system while {@code abortAll()} runs on another thread. A monitor that abortAll entered
 * would stop it there, so a return within one second shows that it entered none of them. The monitors that the
 * aborting thread holds at the end are read too. A monitor that it entered and left can only be seen by the first
 * check, which is why the helpers hold them.
 * </p>
 */
public class Smb2AbortAllRigTest {

    private static final String FORK_PACKAGE = "org.wso2.org.apache.commons.vfs2";

    @Test
    public void abortAllEntersNoMonitorOfTheForkWithARealFileSystemOfEachKind() throws Exception {
        final String sftp = System.getProperty("rev7.sftp");
        final String ftp = System.getProperty("rev7.ftp");
        final String smb2 = System.getProperty("rev7.smb2");
        final String local = System.getProperty("rev7.local");
        assumeTrue(sftp != null && ftp != null && smb2 != null && local != null, "the endpoints of the rigs are not given");

        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        if (!manager.hasProvider("smb2")) {
            manager.addProvider("smb2", new Smb2FileProvider());
        }
        final FileSystemOptions sftpOptions = new FileSystemOptions();
        SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(sftpOptions, "no");
        SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(sftpOptions, false);
        final FileSystemOptions ftpOptions = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setPassiveMode(ftpOptions, true);
        FtpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(ftpOptions, false);

        final List<AbstractFileSystem> fileSystems = new ArrayList<>();
        fileSystems.add(readOnce(manager, sftp, sftpOptions));
        fileSystems.add(readOnce(manager, ftp, ftpOptions));
        fileSystems.add(readOnce(manager, smb2, new FileSystemOptions()));
        fileSystems.add(readOnce(manager, "file://" + local, new FileSystemOptions()));
        for (final AbstractFileSystem fileSystem : fileSystems.subList(0, 3)) {
            assertTrue(fileSystem.isAliveLocally(), fileSystem.getRootURI());
        }

        final Set<Object> monitors = Collections.newSetFromMap(new IdentityHashMap<>());
        monitors.add(manager);
        monitors.add(manager.getFilesCache());
        monitors.addAll(fileSystems);
        for (final Object provider : providers(manager)) {
            monitors.add(provider);
            addField(monitors, provider, "fileSystemMap");
            addField(monitors, provider, "components");
        }
        final CountDownLatch held = new CountDownLatch(monitors.size());
        final CountDownLatch release = new CountDownLatch(1);
        final List<Thread> holders = new ArrayList<>();
        for (final Object monitor : monitors) {
            final Thread holder = new Thread(() -> {
                synchronized (monitor) {
                    held.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "holder-" + monitor.getClass().getSimpleName());
            holder.setDaemon(true);
            holder.start();
            holders.add(holder);
        }
        final AtomicReference<String> heldByAborter = new AtomicReference<>("not sampled");
        final AtomicReference<Boolean> aliveAfter = new AtomicReference<>();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            final Thread aborter = new Thread(() -> {
                manager.abortAll();
                final ThreadInfo[] info = ManagementFactory.getThreadMXBean()
                        .getThreadInfo(new long[] {Thread.currentThread().getId()}, true, false);
                final List<String> found = new ArrayList<>();
                for (final MonitorInfo monitor : info[0].getLockedMonitors()) {
                    if (monitor.getClassName().startsWith(FORK_PACKAGE)) {
                        found.add(monitor.getClassName());
                    }
                }
                heldByAborter.set(found.toString());
                aliveAfter.set(manager.isAliveLocally());
            }, "aborter");
            final long start = System.nanoTime();
            aborter.start();
            aborter.join(5000);
            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertFalse(aborter.isAlive(), "abortAll did not return while the monitors were held");
            assertTrue(millis < 1000, "abortAll and the local check took " + millis + " ms");
            assertEquals("[]", heldByAborter.get());
            assertEquals(Boolean.FALSE, aliveAfter.get());
        } finally {
            release.countDown();
            for (final Thread holder : holders) {
                holder.join(10000);
            }
        }
        for (final AbstractFileSystem fileSystem : fileSystems.subList(0, 3)) {
            assertFalse(fileSystem.isAliveLocally(), fileSystem.getRootURI());
        }
        manager.close();
    }

    private static AbstractFileSystem readOnce(final StandardFileSystemManager manager, final String uri,
            final FileSystemOptions options) throws Exception {
        final FileObject file = manager.resolveFile(uri, options);
        try (InputStream in = file.getContent().getInputStream()) {
            final byte[] buffer = new byte[4096];
            while (in.read(buffer) > 0) {
                // Read to the end, so the client goes back to the idle slot.
            }
        }
        return (AbstractFileSystem) file.getFileSystem();
    }

    private static Collection<Object> providers(final StandardFileSystemManager manager) throws Exception {
        final Set<Object> found = Collections.newSetFromMap(new IdentityHashMap<>());
        found.addAll(((Map<?, ?>) field(manager, "providers")).values());
        return found;
    }

    private static void addField(final Set<Object> monitors, final Object target, final String name) {
        try {
            monitors.add(field(target, name));
        } catch (final Exception e) {
            // A provider of another kind has no such field.
        }
    }

    private static Object field(final Object target, final String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found.get(target);
            } catch (final NoSuchFieldException e) {
                // The field is in a super class.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
