/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.PacketHandlers;
import com.hierynomus.protocol.transport.PacketReceiver;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.protocol.transport.TransportLayer;
import com.hierynomus.security.jce.JceSecurityProvider;
import com.hierynomus.smb.SMBPacket;
import com.hierynomus.smb.SMBPacketData;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.paths.PathResolveException;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.Directory;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.smbj.transport.TransportLayerFactory;
import com.hierynomus.smbj.transport.tcp.direct.DirectTcpTransportFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocketFactory;

import java.io.IOException;
import java.net.Socket;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * A wrapper to the SMBClient for bundling the related client & connection instances.
 */
public class Smb2ClientWrapper extends SMBClient {

    private static final Log LOG = LogFactory.getLog(Smb2ClientWrapper.class);
    private static final String BCFIPS = "BCFIPS";
    private static final String SCHEME = "smb2";
    // The connect bound of the default socket factory of smbj. A factory of our own replaces that one, so it applies
    // the same bound itself.
    private static final String ABORT_REASON = "The SMB2 client was aborted";
    private static final String LOST_REASON = "The connection of the SMB2 client was lost";
    private static final String CLOSED_REASON = "The SMB2 client was closed";
    protected final FileSystemOptions fileSystemOptions;
    private final GenericFileName root;
    private final SocketGroup socketGroup;
    // Set when a call on this client ended with a transport error, or when the packet reader of one of its connections
    // met a fatal error. The client is not used again.
    private volatile boolean broken;
    // The socket of the connection, kept after the connect. The local check reads it, because the connection itself
    // answers only under the transport lock that a blocked writer holds.
    private volatile Socket connectionSocket;
    private SMBClient smbClient;
    private Connection connection;
    private Session session;
    private DiskShare diskShare;
    // The local ports of the connection, read once after the connect, for the events of the test build.
    private String localPorts = "";

    /**
     * Constructs a client whose sockets are in a group of its own. No manager knows that group, so an abort of a
     * manager does not reach these sockets.
     *
     * @param root the root of the file system.
     * @param fileSystemOptions the options of the file system.
     * @throws FileSystemException when the client can not connect.
     */
    protected Smb2ClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        this(root, fileSystemOptions, new SocketGroup());
    }

    /**
     * Constructs a client with its own config, built from its own options, and connects it.
     *
     * @param root the root of the file system.
     * @param fileSystemOptions the options of the file system.
     * @param socketGroup the group of this client. Every socket of the client, also one to a DFS target, is in it.
     * @throws FileSystemException when the client can not connect.
     */
    protected Smb2ClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions,
            final SocketGroup socketGroup) throws FileSystemException {

        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        this.socketGroup = socketGroup;
        smbClient = new SMBClient(buildConfig(fileSystemOptions, socketGroup, this::connectionLost));
        setupClient();
    }

    // The config of one client. No other client reads or changes it. The lost action runs on the packet reader of a
    // connection of this client when that reader meets a fatal error.
    static SmbConfig buildConfig(final FileSystemOptions options, final SocketGroup group, final Runnable lost) {
        final Smb2FileSystemConfigBuilder builder = Smb2FileSystemConfigBuilder.getInstance();
        final SmbConfig.Builder config;
        // Detect FIPS
        final String activeProvider = Security.getProviders()[0].getName();
        final boolean isFipsMode = BCFIPS.equalsIgnoreCase(activeProvider);
        if (isFipsMode) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("FIPS mode detected.");
            }
            System.setProperty("crypto.policy", "limited");
            config = SmbConfig.builder()
                    .withDialects(SMB2Dialect.SMB_2_1)
                    .withSecurityProvider(new JceSecurityProvider())
                    .withSigningRequired(true);
        } else {
            config = SmbConfig.builder()
                    .withDfsEnabled(true)
                    .withMultiProtocolNegotiate(true);
            if (builder.getEncryptionEnabled(options)) {
                config.withEncryptData(true);
            }
        }
        // The values come from the bounds in effect, so the logged value is the applied one.
        final EffectiveBounds bounds = EffectiveBounds.of(SCHEME, options);
        return config
                .withReadTimeout(bounds.getReadTimeout().getValue(), TimeUnit.MILLISECONDS)
                .withWriteTimeout(bounds.getWriteTimeout().getValue(), TimeUnit.MILLISECONDS)
                .withTransactTimeout(bounds.getTransactTimeout().getValue(), TimeUnit.MILLISECONDS)
                .withSoTimeout((int) bounds.getSocketTimeout().getValue())
                .withSocketFactory(new WatchedSocketFactory(group, (int) bounds.getConnectTimeout().getValue(),
                        bounds.getWriteStallTimeout().getValue()))
                .withTransportLayerFactory(new ReaderWatch(lost))
                .build();
    }

    /**
     * The direct transport of smbj with one change. When the packet reader of a connection meets a fatal error, the
     * client is marked lost and its raw sockets are closed first. Then smbj gets the error as before: it fails the
     * outstanding requests with that error and closes its sessions and shares. With the sockets closed, no request of
     * that cleanup waits for a reply that only this reader could read. Every packet goes on to smbj unchanged.
     */
    static final class ReaderWatch implements TransportLayerFactory<SMBPacketData<?>, SMBPacket<?, ?>> {

        private final DirectTcpTransportFactory<SMBPacketData<?>, SMBPacket<?, ?>> direct =
                new DirectTcpTransportFactory<>();

        private final Runnable lost;

        ReaderWatch(final Runnable lost) {
            this.lost = lost;
        }

        @Override
        public TransportLayer<SMBPacket<?, ?>> createTransportLayer(
                final PacketHandlers<SMBPacketData<?>, SMBPacket<?, ?>> handlers, final SmbConfig config) {
            return direct.createTransportLayer(new PacketHandlers<>(handlers.getSerializer(),
                    watch(handlers.getReceiver(), lost), handlers.getPacketFactory()), config);
        }

        static PacketReceiver<SMBPacketData<?>> watch(final PacketReceiver<SMBPacketData<?>> receiver,
                final Runnable lost) {
            return new PacketReceiver<SMBPacketData<?>>() {
                @Override
                public void handle(final SMBPacketData<?> packet) throws TransportException {
                    receiver.handle(packet);
                }

                @Override
                public void handleError(final Throwable error) {
                    lost.run();
                    receiver.handleError(error);
                }
            };
        }
    }

    private void setupClient() throws FileSystemException {

        final GenericFileName rootName = getRoot();

        //the relevant data to authenticate a connection
        final String userString = rootName.getUserName();
        String userName = "";
        if (userString != null && !userString.isEmpty()) {

            if (userString.contains(";")) {
                userName = userString.substring(userString.indexOf(";") + 1, userString.length());
            } else if (userString.contains("\\")) {
                userName = userString.substring(userString.indexOf("\\") + 1, userString.length());
            } else {
                userName = userString;
            }
        }

        String password = rootName.getPassword();

        String authDomain = null;

        if (userString.contains(";")) {
            authDomain = userString.substring(0, userString.indexOf(";"));
        } else if (userString.contains("\\")) {
            authDomain = userString.substring(0, userString.indexOf("\\"));
        } else {
            authDomain = null;
        }

        //if username == "" the client tries to authenticate "anonymously". It's also possible to submit "guest" as
        // username Password may be null depending on upstream auth handling. Avoid NullPointerException when
        // converting to char[] by falling back to an empty array. This preserves constructor contract without
        // altering authentication flow.
        char[] pwd = (password != null) ? password.toCharArray() : new char[0];
        AuthenticationContext authContext = new AuthenticationContext(userName, pwd, authDomain);

        //a connection stack is: SMBClient > Connection > Session > DiskShare
        try {
            //the port parsed from the smb2:// URI is stored on the file name; when it differs from the
            //SMB2 default it has to be passed explicitly, since connect(host) always uses the default port
            int port = rootName.getPort();
            connection = (port > 0 && port != rootName.getDefaultPort())
                    ? smbClient.connect(rootName.getHostName(), port)
                    : smbClient.connect(rootName.getHostName());
            final List<Socket> sockets = socketGroup.getSockets();
            connectionSocket = sockets.isEmpty() ? null : sockets.get(0);
            if (TestPoints.enabled()) {
                localPorts = portsOf(sockets);
            }
            session = connection.authenticate(authContext);
            String share = ((Smb2FileName) rootName).getShareName();
            diskShare = (DiskShare) session.connectShare(share);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error while creating connection to " + rootName.getHostName());
            }
            close();
            final FileSystemException error = new FileSystemException("vfs.provider.smb2/connect.error",
                    rootName.getHostName(), e);
            error.setFault(Faults.of(e));
            throw error;
        }
    }

    private static String portsOf(final List<Socket> sockets) {
        final StringJoiner ports = new StringJoiner(",");
        for (final Socket socket : sockets) {
            ports.add(String.valueOf(socket.getLocalPort()));
        }
        return ports.toString();
    }

    /**
     * Tells from local state whether this client looks alive: no call and no packet reader marked it broken, its
     * sockets were not aborted, the socket of its connection is not closed, and its disk share is not closed. It makes
     * no network call and takes no lock of smbj, so it returns also while another thread is blocked in a socket write
     * of this client. A half open connection still looks alive.
     *
     * @return false when the client is known to be dead.
     */
    public boolean isAliveLocally() {
        final Socket socket = connectionSocket;
        return !broken && !socketGroup.isAborted() && socket != null && !socket.isClosed() && diskShare != null
                && diskShare.isConnected();
    }

    /**
     * Closes every raw socket of this client. It sends no request and waits for no reply, so any thread may call it.
     * A thread that is blocked on one of the sockets fails at once.
     */
    public void abort() {
        socketGroup.abort(ABORT_REASON);
    }

    // Marks the client as not to be used again. Only a discard calls it.
    void markBroken() {
        broken = true;
    }

    // Runs on the packet reader of a connection of this client, after a fatal error and before smbj cleans up.
    void connectionLost() {
        broken = true;
        socketGroup.abort(LOST_REASON);
    }

    // The local ports of the connection of this client, as they were after the connect.
    String getLocalPorts() {
        return localPorts;
    }

    /**
     * Gets the fault of an exception of a call on this client. Two rows of the SMB2 table need the state of this
     * client, so they are decided here. A runtime exception with no cause and no status while the share is closed is
     * a transport error. A not found status that the DFS resolver made itself, which has a path resolve exception in
     * its cause chain, is a transport error too, because the share was reached when the client connected. Every other
     * exception gets the fault of {@link Faults#of(Throwable)}.
     *
     * @param error the exception of a call on this client.
     * @return the fault.
     */
    Fault faultOf(final Throwable error) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = error; link != null && seen.add(link); link = link.getCause()) {
            if (link instanceof FileSystemException && ((FileSystemException) link).getFault() != null) {
                return ((FileSystemException) link).getFault();
            }
            if (link instanceof SMBApiException) {
                return isNotFoundOfTheResolver((SMBApiException) link) ? Fault.TRANSPORT : Faults.of(error);
            }
            if (link instanceof SMBRuntimeException) {
                return link.getCause() == null && isShareClosed() ? Fault.TRANSPORT : Faults.of(error);
            }
        }
        return Faults.of(error);
    }

    private boolean isShareClosed() {
        return broken || diskShare == null || !diskShare.isConnected();
    }

    private static boolean isNotFoundOfTheResolver(final SMBApiException error) {
        final NtStatus status = error.getStatus();
        if (status != NtStatus.STATUS_OBJECT_NAME_NOT_FOUND && status != NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) {
            return false;
        }
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PathResolveException) {
                return true;
            }
        }
        return false;
    }

    private FileSystemException failure(final String code, final Throwable error) {
        final FileSystemException failure = new FileSystemException(code, error);
        failure.setFault(faultOf(error));
        return failure;
    }

    public GenericFileName getRoot() {

        return root;
    }

    /**
     * Returns file information of a given file.
     *
     * @param relativePath of file
     * @return FileAllInformation object
     */
    public FileAllInformation getFileInfo(String relativePath) throws FileSystemException {

        try {
            return query(relativePath);
        } catch (Exception e) {
            final FileSystemException failure = failure("Could not get information for file: " + relativePath, e);
            // Only a file that the server says is missing is not there. A busy file, a refusal and a dead connection
            // throw with their fault.
            if (failure.getFault() == Fault.MISSING) {
                return null;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not get information for file: " + relativePath);
            }
            throw failure;
        }
    }

    // The same open, query and close as DiskShare.getFileInformation, with the handle held here, so a dead connection
    // is aborted before the close and a failed close is seen.
    private FileAllInformation query(final String path) {
        final DiskEntry entry = diskShare.open(path,
                EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null);
        final FileAllInformation info;
        try {
            info = entry.getFileInformation(FileAllInformation.class);
        } catch (RuntimeException e) {
            closeOwned(entry, e);
            throw e;
        }
        closeOwned(entry, null);
        return info;
    }

    /**
     * Closes a handle that this client opened for one call, and keeps the outcome of that call. When the call met a
     * transport error, the sockets are closed first, so the close fails at once and sends nothing on a dead
     * connection. When the close itself meets a transport error, the sockets are closed too, so the give back
     * discards the client. The error of the close is added to the error of the call when there is one, and is not
     * thrown.
     *
     * @param handle the handle.
     * @param callError the error of the call, or null when the call worked.
     * @return the error of the close, or null.
     */
    RuntimeException closeOwned(final DiskEntry handle, final Throwable callError) {
        if (callError != null && Smb2FileSystem.isTransport(this, callError)) {
            abort();
        }
        try {
            handle.close();
            return null;
        } catch (RuntimeException e) {
            if (Smb2FileSystem.isTransport(this, e)) {
                abort();
            }
            if (callError != null && callError != e) {
                callError.addSuppressed(e);
            }
            return e;
        }
    }

    /**
     * Returns a write handle on the file.
     *
     * @param path   path to file
     * @param append append to file
     * @return DiskEntry file
     */
    public DiskEntry getDiskEntryWrite(String path, boolean append) throws FileSystemException {
        ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        Set<AccessMask> accessMask = resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.MAXIMUM_ALLOWED);
        return diskShare.open(path, accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                append ? SMB2CreateDisposition.FILE_OPEN_IF : SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    /**
     * Opens the source of a rename. The open never creates a missing source and never shares delete. A file is opened
     * with the configured access mask, {@code MAXIMUM_ALLOWED} by default, and share write. A folder is opened with
     * the mask and the share of {@link #getDiskEntryFolderWrite(String)}.
     *
     * @param path the path of the source.
     * @param isFolder true for a folder.
     * @return the handle. The caller closes it.
     */
    // The exclusive create: the open fails when the name exists.
    File openCreate(final String path) {
        final ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        return diskShare.openFile(path, resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    // The open of a file for a hard link. It never creates a missing file.
    DiskEntry openForLink(final String path) {
        return diskShare.open(path, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
                null);
    }

    // The look at a name, with the exception of smbj as it came.
    FileAllInformation look(final String path) {
        return query(path);
    }

    void remove(final String path) {
        diskShare.rm(path);
    }

    public DiskEntry openForRename(String path, boolean isFolder) {
        ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        if (isFolder) {
            return diskShare.openDirectory(path,
                    resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.GENERIC_ALL),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
        }
        return diskShare.open(path,
                resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.MAXIMUM_ALLOWED),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    /**
     * Retrieves the set of \AccessMask\ values based on the provided configuration string.
     *
     * @param config the access mask configuration string
     * @param defaultMask the default \AccessMask\ value to use if the configuration string is null or empty
     * @return a set of \AccessMask\ values corresponding to the configuration
     * @throws IllegalArgumentException if the configuration string is null or invalid
     */
    private static Set<AccessMask> resolveAccessMaskOrDefault(ArrayList<String> config, AccessMask defaultMask) {
        if (config == null || config.isEmpty()) {
            // To keep backward compatibility
            LOG.debug("Disk share access mask is not configured. Using default access mask: " + defaultMask);
            return EnumSet.of(defaultMask);
        }

        Set<AccessMask> accessMasks = EnumSet.noneOf(AccessMask.class);

        for (String maskValue : config) {
            try {
                if (maskValue != null) {
                    AccessMask mask = AccessMask.valueOf(maskValue.trim().toUpperCase());
                    accessMasks.add(mask);
                }
            } catch (IllegalArgumentException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Invalid access mask value: " + maskValue);
                }
            }
        }

        if (accessMasks.isEmpty()) {
            // To keep backward compatibility
            return EnumSet.of(defaultMask);
        }

        return accessMasks;
    }


    /**
     * Creates a folder on a given path.
     *
     * @param path of folder
     */
    public void createFolder(String path) {

        DiskEntry de = getDiskEntryFolderWrite(path);
        de.close();
    }

    /**
     * Returns a handle for a folder.
     *
     * @param path folder path
     * @return DiskEntry
     */
    public DiskEntry getDiskEntryFolderWrite(String path) {

        ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        Set<AccessMask> accessMask = resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.GENERIC_ALL);
        return diskShare.openDirectory(path,
                accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN_IF,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
    }

    /**
     * Returns a read handle on the file.
     *
     * @param path path to file
     * @return DiskEntry file
     */
    public DiskEntry getDiskEntryRead(String path) {

        return diskShare.open(path,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    /**
     * Returns directory listing of path.
     *
     * @param path
     * @return listing string array
     */
    public String[] getChildren(String path) {

        List<String> children = new ArrayList<>();

        // The same open, query and close as DiskShare.list, with the handle held here. On a dead connection the close
        // of the handle would wait one more request timeout, so the sockets are closed before it.
        Directory directory = diskShare.openDirectory(path,
                EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
                null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
        List<FileIdBothDirectoryInformation> entries;
        try {
            entries = directory.list(FileIdBothDirectoryInformation.class, null);
        } catch (RuntimeException e) {
            closeOwned(directory, e);
            throw e;
        }
        // The listing stands also when the close fails. A transport error of the close retires the client.
        closeOwned(directory, null);

        for (FileIdBothDirectoryInformation file : entries) {
            String name = file.getFileName();
            if (name.equals(".") || name.equals("..") || name.equals("./") || name.equals("../")) {
                // ignore if there are no files in the directory and only relative path exists
                continue;
            }
            children.add(file.getFileName());
        }
        return children.toArray(new String[children.size()]);
    }

    /**
     * Delete item in given path of share.
     *
     * @param path string
     * @throws FileSystemException
     */
    public void delete(String path) throws FileSystemException {

        try {
            FileAllInformation info = query(path);
            if (info.getStandardInformation().isDirectory()) {
                diskShare.rmdir(path, true);
            } else {
                diskShare.rm(path);
            }
        } catch (RuntimeException e) {
            throw failure("Could not delete file " + path, e);
        }
    }

    public void close() {
        try {
            if (diskShare != null) {
                diskShare.close();
            }
            if (session != null) {
                session.close();
            }
            if (connection != null) {
                connection.close(true);
            }
        } catch (Exception e) {
            LOG.debug("Error while closing the connection.", e);
        } finally {
            if (connection != null) {
                try {
                    LOG.debug("Connection closed forcefully for : " + smbClient);
                    connection.close(true);
                } catch (IOException e) {
                    //Ignore
                }
            }
            // The connections of this client, also one to a DFS target, are in the table of the client that opened
            // them. The client that this wrapper extends opened none.
            if (smbClient != null) {
                smbClient.close();
            }
            // A socket that is still open now, for example after a setup that failed half way, is closed here, so a
            // retired client keeps no socket.
            socketGroup.abort(CLOSED_REASON);
            super.close();
        }
    }
}
