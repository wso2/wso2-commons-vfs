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
package org.wso2.org.apache.commons.vfs2.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.ram.RamFileProvider;

/**
 * A class loader that holds the sandbox jar of two applications sees the plugin file of the sandbox more than once. The
 * manager then gets the same provider class for the same scheme again, and that second copy is not an error.
 */
public class PluginReadTwiceTest {

    private static final String PLUGIN = "META-INF/vfs-providers.xml";

    @TempDir
    Path first;

    @TempDir
    Path second;

    // A loader that sees the plugin file of this module and two more copies of it, as the shared library loader of two
    // applications does.
    private ClassLoader twoApplications() throws Exception {
        for (final Path copy : new Path[] {first, second}) {
            final Path plugin = copy.resolve(PLUGIN);
            Files.createDirectories(plugin.getParent());
            try (InputStream in = getClass().getClassLoader().getResource(PLUGIN).openStream()) {
                Files.copy(in, plugin);
            }
        }
        return new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL()},
                getClass().getClassLoader());
    }

    @Test
    public void managerOnALoaderWithTheSamePluginTwiceStartsAndKeepsEveryScheme() throws Exception {
        final ClassLoader loader = twoApplications();
        final List<URL> plugins = Collections.list(loader.getResources(PLUGIN));
        assertTrue(plugins.size() >= 3, plugins.toString());
        try (StandardFileSystemManager manager = new StandardFileSystemManager()) {
            manager.setClassLoader(loader);
            manager.setConfiguration(StandardFileSystemManager.class.getResource("providers.xml"));
            manager.init();
            assertTrue(manager.hasProvider("smb2"));
            assertTrue(manager.hasProvider("sftp"));
            assertEquals("smb2", manager.resolveURI("smb2://host/share/in/a.txt").getScheme());
            assertEquals("sftp", manager.resolveURI("sftp://host/in/a.txt").getScheme());
        }
    }

    @Test
    public void providerOfAnotherClassForATakenSchemeStillFails() throws Exception {
        try (StandardFileSystemManager manager = new StandardFileSystemManager()) {
            manager.setClassLoader(twoApplications());
            manager.setConfiguration(StandardFileSystemManager.class.getResource("providers.xml"));
            manager.init();
            final FileSystemException refused = assertThrows(FileSystemException.class,
                    () -> manager.addProvider("smb2", new RamFileProvider()));
            assertEquals("vfs.impl/multiple-providers-for-scheme.error", refused.getCode());
        }
    }
}
