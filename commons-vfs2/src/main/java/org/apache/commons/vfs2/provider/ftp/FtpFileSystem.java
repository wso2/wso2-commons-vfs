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
package org.apache.commons.vfs2.provider.ftp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.VfsLog;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.apache.commons.vfs2.provider.GenericFileName;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An FTP file system.
 */
public class FtpFileSystem extends AbstractFileSystem {
    private static final Log LOG = LogFactory.getLog(FtpFileSystem.class);

    // An idle client
    private final AtomicReference<FtpClient> idleClient = new AtomicReference<>();

    private final ClientWrapperFactory clientWrapperFactory;

    private volatile boolean isFileSystemClosed = false;

    /**
     * @param rootName The root of the file system.
     * @param clientWrapperFactory The {@link FtpClientWrapperFactory}.
     * @param fileSystemOptions The FileSystemOptions.
     * @since 2.0 (was protected)
     */
    public FtpFileSystem(final GenericFileName rootName, final ClientWrapperFactory clientWrapperFactory,
                         final FileSystemOptions fileSystemOptions) throws FileSystemException {
        super(rootName, null, fileSystemOptions);

        this.clientWrapperFactory = clientWrapperFactory;
        idleClient.set(clientWrapperFactory.create());
    }

    @Override
    protected void doCloseCommunicationLink() {
        isFileSystemClosed = true;
        final FtpClient idle = idleClient.getAndSet(null);
        // Clean up the connection
        if (idle != null) {
            closeConnection(idle);
        }
    }

    /**
     * Adds the capabilities of this file system.
     */
    @Override
    protected void addCapabilities(final Collection<Capability> caps) {
        caps.addAll(FtpFileProvider.capabilities);
    }

    /**
     * Cleans up the connection to the server.
     *
     * @param client The FtpClient.
     */
    private void closeConnection(final FtpClient client) {
        try {
            // Clean up
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (final IOException e) {
            VfsLog.warn(getLogger(), LOG, "vfs.provider.ftp/close-connection.error", e);
        }
    }

    /**
     * Creates an FTP client to use.
     *
     * @return An FTPCleint.
     * @throws FileSystemException if an error occurs.
     */
    public FtpClient getClient() throws FileSystemException {
        FtpClient client = idleClient.getAndSet(null);

        if (client == null || !client.isConnected()) {
            client = clientWrapperFactory.create();
        }

        return client;
    }

    /**
     * Returns an FTP client after use.
     *
     * @param client The FTPClient.
     */
    void putClient(final FtpClient client) {
        // Save client for reuse if none is idle.
        if (isFileSystemClosed) {
            closeConnection(client);
        } else if (!idleClient.compareAndSet(null, client)) {
            // An idle client is already present so close the connection.
            closeConnection(client);
        }
    }

    /**
     * Creates a file object.
     */
    @Override
    protected FileObject createFile(final AbstractFileName name) throws FileSystemException {
        return new FtpFileObject(name, this, getRootName());
    }
}
