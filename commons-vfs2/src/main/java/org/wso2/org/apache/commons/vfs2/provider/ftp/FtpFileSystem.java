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

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.VfsLog;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * An FTP file system.
 */
public class FtpFileSystem extends AbstractFileSystem {

    private static final Log LOG = LogFactory.getLog(FtpFileSystem.class);

    // private final String hostname;
    // private final int port;
    // private final String username;
    // private final String password;

    // An idle client. It is one of the clients below.
    private final AtomicReference<FtpClient> idleClient = new AtomicReference<>();

    // Every client of this file system, the idle one and the ones that a call or an open stream holds. A client is in
    // this set from the end of its connect until it is closed or discarded, also while it moves into or out of the
    // idle slot, so an abort always finds it.
    private final Set<FtpClient> clients = ConcurrentHashMap.newKeySet();

    // The options that the clients are made with. The provider writes the values of the URI into a copy.
    private final FileSystemOptions clientOptions;

    private volatile boolean isFileSystemClosed = false;

    // A target folder that is not in the map is not known.
    enum ReplaceState {
        REPLACES, NO_REPLACE
    }

    // The folders that a WARN was logged for, in this copy of the fork.
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    // The replace state per target folder. "Replaces" never changes again.
    private final ConcurrentMap<String, ReplaceState> replaceStates = new ConcurrentHashMap<>();

    // Set when one listing with the option -a gave at least one entry on this file system. Until then an empty
    // listing with the option is followed by one plain listing.
    private volatile boolean hiddenOptionSeen;

    /**
     * Constructs a new instance.
     *
     * @param rootName The root of the file system.
     * @param ftpClient The FtpClient.
     * @param fileSystemOptions The FileSystemOptions.
     * @since 2.0 (was protected)
     */
    public FtpFileSystem(final GenericFileName rootName, final FtpClient ftpClient,
            final FileSystemOptions fileSystemOptions) {
        this(rootName, ftpClient, fileSystemOptions, fileSystemOptions);
    }

    /**
     * Constructs a new instance whose clients are made with other options than the options of the caller.
     *
     * @param rootName The root of the file system.
     * @param ftpClient The FtpClient.
     * @param fileSystemOptions The FileSystemOptions of the caller, which are the key of the file system.
     * @param clientOptions The options of the clients.
     */
    public FtpFileSystem(final GenericFileName rootName, final FtpClient ftpClient,
            final FileSystemOptions fileSystemOptions, final FileSystemOptions clientOptions) {
        super(rootName, null, fileSystemOptions);
        // hostname = rootName.getHostName();
        // port = rootName.getPort();

        this.clientOptions = clientOptions;
        if (ftpClient != null) {
            clients.add(ftpClient);
            idleClient.set(ftpClient);
        }
    }

    /**
     * Adds the capabilities of this file system.
     */
    @Override
    protected void addCapabilities(final Collection<Capability> caps) {
        caps.addAll(FtpFileProvider.CAPABILITIES);
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
            // getLogger().warn("vfs.provider.ftp/close-connection.error", e);
            VfsLog.warn(getLogger(), LOG, "vfs.provider.ftp/close-connection.error", e);
        }
    }

    /**
     * Creates a file object.
     */
    @Override
    protected FileObject createFile(final AbstractFileName name) throws FileSystemException {
        return new FtpFileObject(name, this, getRootName());
    }

    /**
     * Gets the wrapper to access this file system.
     *
     * @return new instance.
     * @throws FileSystemException if any error occurs.
     * @since 2.1
     */
    protected FTPClientWrapper createWrapper() throws FileSystemException {
        return new FTPClientWrapper((GenericFileName) getRoot().getName(), getClientOptions(), getSocketGroups());
    }

    /**
     * Gets the options that the clients of this file system are made with.
     *
     * @return the options.
     */
    protected FileSystemOptions getClientOptions() {
        return clientOptions;
    }

    /**
     * Gets the source of the socket groups of new clients. The manager keeps each group, so an abort of the manager
     * reaches every socket of the clients.
     *
     * @return the source.
     */
    protected Supplier<SocketGroup> getSocketGroups() {
        return ((DefaultFileSystemManager) getFileSystemManager())::newSocketGroup;
    }

    @Override
    protected void doCloseCommunicationLink() {
        isFileSystemClosed = true;
        final FtpClient idle = idleClient.getAndSet(null);
        // Clean up the connection
        if (idle != null) {
            closeConnection(idle);
            clients.remove(idle);
        }
    }

    /**
     * Creates an FTP client to use.
     *
     * @return An FTPClient.
     * @throws FileSystemException if an error occurs.
     */
    public FtpClient getClient() throws FileSystemException {
        final FtpClient idle = idleClient.getAndSet(null);
        if (idle != null) {
            // The state of its sockets, and then what an idle read found.
            if (idle.isConnected() && idle.isAliveLocally()) {
                return idle;
            }
            // A closed or aborted client is not handed out. Its sockets are closed with no command.
            discardClient(idle);
        }
        final FtpClient client = createWrapper();
        clients.add(client);
        return client;
    }

    /**
     * Returns an FTP client after use.
     *
     * @param client The FTPClient.
     */
    public void putClient(final FtpClient client) {
        // Save client for reuse if none is idle.
        if (isFileSystemClosed || !idleClient.compareAndSet(null, client)) {
            // The file system is closed, or an idle client is already present, so close the connection.
            closeConnection(client);
            clients.remove(client);
        } else if (isFileSystemClosed && idleClient.compareAndSet(client, null)) {
            // The close came after the check above and may have found the slot empty. Then the client is closed here,
            // so it is not left in the slot of a closed file system.
            closeConnection(client);
            clients.remove(client);
        }
    }

    /**
     * Takes back a client whose call did not end with a good reply, and never hands it out again. Its sockets are
     * closed with no command, so nothing waits on the server.
     *
     * @param client The FTPClient.
     */
    public void discardClient(final FtpClient client) {
        client.abortNow();
        clients.remove(client);
    }

    /**
     * Closes the sockets of the idle client and of every client that is out. It sends no command and takes no
     * monitor, so any thread may call it.
     */
    ReplaceState getReplaceState(final String folder) {
        return replaceStates.get(folder);
    }

    void markReplaces(final String folder) {
        replaceStates.put(folder, ReplaceState.REPLACES);
    }

    // A folder that replaces keeps that state.
    void markNoReplace(final String folder) {
        replaceStates.putIfAbsent(folder, ReplaceState.NO_REPLACE);
    }

    boolean isHiddenOptionSeen() {
        return hiddenOptionSeen;
    }

    void markHiddenOptionSeen() {
        hiddenOptionSeen = true;
    }

    // True only for the first WARN about this subject on this server in this copy of the fork.
    boolean firstWarning(final String subject) {
        final GenericFileName root = (GenericFileName) getRootName();
        return WARNED.add(root.getScheme() + "://" + root.getHostName() + ":" + root.getPort() + " " + subject);
    }

    @Override
    public void abort() {
        for (final FtpClient client : clients) {
            client.abortNow();
        }
    }

    /**
     * Tells from local state whether the idle client and every client that is out are connected and not aborted. The
     * idle client also gets a read of 1 ms on its control connection, because a socket stays connected after the server
     * closes it. The idle client leaves its slot for that read, so no caller takes it meanwhile. Nothing is sent.
     *
     * @return false when one of them is closed or aborted.
     */
    @Override
    public boolean isAliveLocally() {
        final FtpClient idle = idleClient.getAndSet(null);
        boolean alive = true;
        if (idle != null) {
            alive = idle.probeIdle();
            // A closed client goes back too. The next getClient discards it, so this check sends nothing.
            putClient(idle);
        }
        for (final FtpClient client : clients) {
            if (client != idle && !client.isAliveLocally()) {
                return false;
            }
        }
        return alive;
    }

    /**
     * Gets the timeouts that the clients of this file system enforce. They come from the options that the clients are
     * made with, which hold the values of the URI too.
     *
     * @return the bounds in effect.
     */
    @Override
    public EffectiveBounds getEffectiveBounds() {
        return EffectiveBounds.of(getRootName().getScheme(), clientOptions);
    }

    @Override
    public int getHandedOutCount() {
        final int all = clients.size();
        return idleClient.get() != null ? Math.max(0, all - 1) : all;
    }

    @Override
    public int getOpenClientCount() {
        return clients.size();
    }
}
