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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.UserAuthenticationData;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.URLFileName;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpConstants;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.UserAuthenticatorUtils;

/**
 * A wrapper to the FTPClient to allow automatic reconnect on connection loss.
 * <p>
 * I decided to not to use eg. noop() to determine the state of the connection to avoid unnecessary server round-trips.
 * </p>
 * <p>
 * Each client of the wrapper has a socket group of its own. After an IOException of a command the group is aborted,
 * which closes the sockets with no command, and the client is not used again. A command that changes the server is
 * never sent twice. A command that only reads is sent once more on a new client, but only when the first try ended
 * with a transport error inside the fast window of the call.
 * </p>
 */
public class FTPClientWrapper implements FtpClient {

    private static final String ABORT_REASON = "The FTP client was aborted";

    /**
     * Authentication options.
     */
    protected final FileSystemOptions fileSystemOptions;
    private final GenericFileName rootFileName;
    private final Supplier<SocketGroup> socketGroups;
    // The owner thread changes the client. Another thread may abort its group.
    private volatile FTPClient ftpClient;
    private volatile SocketGroup group;
    // The client whose idle read found the control connection closed. It is never used again.
    private volatile FTPClient closedOnProbe;

    /**
     * A command of commons net on one client.
     *
     * @param <T> the result.
     */
    private interface Command<T> {
        T run(FTPClient client) throws IOException;
    }

    /**
     * Constructs a new instance. Its clients have groups of their own that no manager knows, so an abort of a manager
     * does not reach their sockets.
     *
     * @param rootFileName the root file name.
     * @param fileSystemOptions the file system options.
     * @throws FileSystemException if a file system error occurs.
     */
    protected FTPClientWrapper(final GenericFileName rootFileName, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        this(rootFileName, fileSystemOptions, SocketGroup::new);
    }

    /**
     * Constructs a new instance.
     *
     * @param rootFileName the root file name.
     * @param fileSystemOptions the file system options.
     * @param socketGroups gives the group of each new client, before it connects.
     * @throws FileSystemException if a file system error occurs.
     */
    protected FTPClientWrapper(final GenericFileName rootFileName, final FileSystemOptions fileSystemOptions,
            final Supplier<SocketGroup> socketGroups) throws FileSystemException {
        this(rootFileName, fileSystemOptions, socketGroups, System.nanoTime());
    }

    /**
     * Constructs a new instance whose first connect runs in the fast window of the call that creates it.
     *
     * @param rootFileName the root file name.
     * @param fileSystemOptions the file system options.
     * @param socketGroups gives the group of each new client, before it connects.
     * @param windowStart the start of the fast window of the call, on the clock of {@link System#nanoTime()}.
     * @throws FileSystemException if a file system error occurs.
     */
    protected FTPClientWrapper(final GenericFileName rootFileName, final FileSystemOptions fileSystemOptions,
            final Supplier<SocketGroup> socketGroups, final long windowStart) throws FileSystemException {
        this.rootFileName = rootFileName;
        this.fileSystemOptions = fileSystemOptions;
        this.socketGroups = socketGroups;
        getFtpClient(windowStart); // fail-fast
    }

    @Override
    public boolean abort() throws IOException {
        disconnect();
        return true;
    }

    @Override
    public void abortNow() {
        final SocketGroup current = group;
        if (current != null) {
            current.abort(ABORT_REASON);
        }
    }

    @Override
    public OutputStream appendFileStream(final String relPath) throws IOException {
        return change(client -> client.appendFileStream(relPath));
    }

    @Override
    public boolean completePendingCommand() throws IOException {
        final FTPClient client = ftpClient;
        if (client != null) {
            return client.completePendingCommand();
        }

        return true;
    }

    private FTPClient createClient(final long windowStart) throws FileSystemException {
        ftpClient = null;
        // The group is known before the connect, so an abort reaches a connect that is still running.
        final SocketGroup fresh = socketGroups.get();
        group = fresh;
        final GenericFileName rootName = getRoot();

        UserAuthenticationData authData = null;
        try {
            authData = UserAuthenticatorUtils.authenticate(fileSystemOptions, FtpFileProvider.AUTHENTICATOR_TYPES);

            final FTPClient client = createClient(rootName, authData, fresh, windowStart);
            ftpClient = client;
            return client;
        } finally {
            UserAuthenticatorUtils.cleanup(authData);
        }
    }

    /**
     * Creates an FTPClient. Its sockets are in a group of their own that no manager knows.
     * @param rootFileName the root file name.
     * @param authData authentication data.
     * @return an FTPClient.
     * @throws FileSystemException if a file system error occurs.
     */
    protected FTPClient createClient(final GenericFileName rootFileName, final UserAuthenticationData authData)
            throws FileSystemException {
        return createClient(rootFileName, authData, new SocketGroup(), System.nanoTime());
    }

    /**
     * Creates an FTPClient whose sockets are in the given group. The proxy values come from the query of the URI,
     * and the timeout and the retry count from the query or else from the options.
     *
     * @param rootFileName the root file name.
     * @param authData authentication data.
     * @param socketGroup the group of the new client.
     * @param windowStart the start of the fast window of the call, on the clock of {@link System#nanoTime()}.
     * @return an FTPClient.
     * @throws FileSystemException if a file system error occurs.
     */
    protected FTPClient createClient(final GenericFileName rootFileName, final UserAuthenticationData authData,
            final SocketGroup socketGroup, final long windowStart) throws FileSystemException {
        final Map<String, String> query = queryParams(rootFileName);
        return FtpClientFactory.createConnection(rootFileName.getHostName(), rootFileName.getPort(),
                UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
                        UserAuthenticatorUtils.toChar(rootFileName.getUserName())),
                UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                        UserAuthenticatorUtils.toChar(rootFileName.getPassword())),
                rootFileName.getPath(), getFileSystemOptions(), query.get(SftpConstants.PROXY_SERVER),
                query.get(SftpConstants.PROXY_PORT), query.get(SftpConstants.PROXY_USERNAME),
                query.get(SftpConstants.PROXY_PASSWORD), query.get(SftpConstants.TIMEOUT),
                query.get(SftpConstants.RETRY_COUNT), socketGroup, windowStart);
    }

    // The query of the URI as the 2.2 line reads it: pairs split at "&" and at "=".
    private static Map<String, String> queryParams(final GenericFileName rootFileName) {
        final Map<String, String> params = new HashMap<>();
        if (rootFileName instanceof URLFileName) {
            final String query = ((URLFileName) rootFileName).getQueryString();
            if (query != null && !query.isEmpty()) {
                for (final String pair : query.split("&")) {
                    final String[] parts = pair.split("=");
                    if (parts.length >= 2) {
                        params.put(parts[0], parts[1]);
                    }
                }
            }
        }
        return params;
    }

    @Override
    public boolean deleteFile(final String relPath) throws IOException {
        return change(client -> client.deleteFile(relPath));
    }

    @Override
    public void disconnect() throws IOException {
        final FTPClient client = ftpClient;
        ftpClient = null;
        // No new client is made only to close it.
        abortNow();
        if (client != null) {
            client.disconnect();
        }
    }

    /**
     * Gets the FileSystemOptions.
     *
     * @return the FileSystemOptions.
     */
    public FileSystemOptions getFileSystemOptions() {
        return fileSystemOptions;
    }

    // The client of the call. A client whose group was aborted is not used again.
    private FTPClient getFtpClient(final long windowStart) throws FileSystemException {
        final FTPClient client = ftpClient;
        final SocketGroup current = group;
        if (client == null || current == null || current.isAborted()) {
            return createClient(windowStart);
        }
        return client;
    }

    @Override
    public int getReplyCode() throws IOException {
        final FTPClient client = ftpClient;
        return client == null ? 0 : client.getReplyCode();
    }

    @Override
    public String getReplyString() throws IOException {
        final FTPClient client = ftpClient;
        return client == null ? "" : client.getReplyString();
    }

    /**
     * Gets the root file name.
     *
     * @return  the root file name.
     */
    public GenericFileName getRoot() {
        return rootFileName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean hasFeature(final String feature) throws IOException {
        return read(client -> client.hasFeature(feature));
    }

    @Override
    public boolean isAliveLocally() {
        return isConnectedLocally() && ftpClient != closedOnProbe;
    }

    // The local socket state only. A client that an idle read found closed still counts here, so that a close of the
    // file system closes its sockets.
    private boolean isConnectedLocally() {
        final FTPClient client = ftpClient;
        final SocketGroup current = group;
        return client != null && current != null && !current.isAborted() && client.isConnected();
    }

    /**
     * Reads the control connection of the idle client for at most 1 ms. An idle client has no reply due, so an end of
     * stream, a byte or an error means that the server closed the connection or is closing it, as with a 421 reply.
     * The read sends nothing. A wait that ends with no data leaves the connection as it was, also with TLS.
     *
     * @return true when nothing arrived within the wait.
     */
    @Override
    public boolean probeIdle() {
        if (!isAliveLocally()) {
            return false;
        }
        final FTPClient client = ftpClient;
        if (!(client instanceof ControlSocketView)) {
            return true;
        }
        final Socket socket = ((ControlSocketView) client).controlSocket();
        boolean quiet = false;
        if (socket != null) {
            try {
                final int timeout = socket.getSoTimeout();
                try {
                    socket.setSoTimeout(1);
                    socket.getInputStream().read();
                } catch (final SocketTimeoutException e) {
                    quiet = true;
                } finally {
                    socket.setSoTimeout(timeout);
                }
            } catch (final IOException | RuntimeException e) {
                quiet = false;
            }
        }
        if (!quiet) {
            closedOnProbe = client;
        }
        return quiet;
    }

    @Override
    public boolean isConnected() throws FileSystemException {
        return isConnectedLocally();
    }

    @Override
    public FTPFile[] listFiles(final String relPath) throws IOException {
        // VFS-210: return getFtpClient().listFiles(relPath);
        return read(client -> listFilesInDirectory(client, relPath));
    }

    private FTPFile[] listFilesInDirectory(final FTPClient client, final String relPath) throws IOException {
        // VFS-307: no check if we can simply list the files, this might fail if there are spaces in the path
        FTPFile[] ftpFiles = client.listFiles(relPath);
        if (FTPReply.isPositiveCompletion(client.getReplyCode())) {
            return ftpFiles;
        }
        // A transport reply ends the listing on this client. The path fallback below is for a refusal only.
        checkTransportReply(client, relPath);

        // VFS-307: now try the hard way by cd'ing into the directory, list and cd back
        // if VFS is required to fallback here the user might experience a real bad FTP performance
        // as then every list requires 4 FTP commands.
        String workingDirectory = null;
        if (relPath != null) {
            workingDirectory = client.printWorkingDirectory();
            checkTransportReply(client, relPath);
            if (!client.changeWorkingDirectory(relPath)) {
                checkTransportReply(client, relPath);
                return null;
            }
        }

        ftpFiles = client.listFiles();
        checkTransportReply(client, relPath);

        if (relPath != null && !client.changeWorkingDirectory(workingDirectory)) {
            checkTransportReply(client, relPath);
            throw new FileSystemException("vfs.provider.ftp.wrapper/change-work-directory-back.error",
                    workingDirectory);
        }
        return ftpFiles;
    }

    // 421, 425 and 426 are transport replies. The error is an IOException, so the read aborts the client.
    private static void checkTransportReply(final FTPClient client, final String relPath) throws FileSystemException {
        checkTransportReply(client, "vfs.provider.ftp/list-reply.error", relPath);
    }

    private static void checkTransportReply(final FTPClient client, final String code, final String relPath)
            throws FileSystemException {
        final int reply = client.getReplyCode();
        if (reply == FTPReply.SERVICE_NOT_AVAILABLE || reply == FTPReply.CANNOT_OPEN_DATA_CONNECTION
                || reply == FTPReply.TRANSFER_ABORTED) {
            final FileSystemException error = new FileSystemException(code, relPath,
                    client.getReplyString().trim());
            error.setFault(Fault.TRANSPORT);
            throw error;
        }
    }

    @Override
    public Change enterAndBack(final String relPath) throws IOException {
        return read(client -> {
            final String first = client.printWorkingDirectory();
            checkTransportReply(client, "vfs.provider.ftp/change-reply.error", relPath);
            final boolean entered = client.changeWorkingDirectory(relPath == null ? "." : relPath);
            final Reply reply = replyOf(client);
            if (!entered) {
                checkTransportReply(client, "vfs.provider.ftp/change-reply.error", relPath);
                return new Change(reply, false, false);
            }
            final boolean back = client.changeWorkingDirectory(first);
            if (!back) {
                checkTransportReply(client, "vfs.provider.ftp/change-reply.error", relPath);
            }
            return new Change(reply, true, !back);
        });
    }

    @Override
    public Listing listChecked(final String relPath, final boolean hidden) throws IOException {
        return read(client -> {
            final boolean before = client.getListHiddenFiles();
            client.setListHiddenFiles(hidden);
            try {
                return listCheckedOn(client, relPath);
            } finally {
                client.setListHiddenFiles(before);
            }
        });
    }

    // commons net gives an empty list for a refused listing and drops the final reply, so the reply is read here.
    private static Listing listCheckedOn(final FTPClient client, final String relPath) throws IOException {
        final FTPFile[] entries = client.listFiles(relPath);
        if (isListed(client.getReplyCode())) {
            return new Listing(entries, null, false, false);
        }
        // A transport reply ends the listing on this client. The second form is for a refusal only.
        checkTransportReply(client, relPath);
        // VFS-307: a server that can not list a path with a blank in it lists the folder after a change into it.
        String first = null;
        if (relPath != null) {
            first = client.printWorkingDirectory();
            checkTransportReply(client, relPath);
            if (!client.changeWorkingDirectory(relPath)) {
                checkTransportReply(client, relPath);
                return new Listing(null, null, false, false);
            }
        }
        final FTPFile[] second = client.listFiles();
        final Reply reply = replyOf(client);
        checkTransportReply(client, relPath);
        boolean backRefused = false;
        if (relPath != null && !client.changeWorkingDirectory(first)) {
            checkTransportReply(client, relPath);
            backRefused = true;
        }
        if (isListed(reply.getCode())) {
            return new Listing(second, null, relPath != null, backRefused);
        }
        return new Listing(null, reply, false, backRefused);
    }

    // 226 and 250 are the two codes that end a transfer in RFC 959.
    private static boolean isListed(final int reply) {
        return reply == FTPReply.CLOSING_DATA_CONNECTION || reply == FTPReply.FILE_ACTION_OK;
    }

    private static Reply replyOf(final FTPClient client) {
        final String text = client.getReplyString();
        return new Reply(client.getReplyCode(), text == null ? "" : text.trim());
    }

    @Override
    public boolean makeDirectory(final String relPath) throws IOException {
        return change(client -> client.makeDirectory(relPath));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Instant mdtmInstant(final String relPath) throws IOException {
        return read(client -> {
            final Calendar time = client.mdtmCalendar(relPath);
            if (time != null) {
                return time.toInstant();
            }
            // No reply 213. A transport reply is an error. Any other reply gives no time, and the caller reads it.
            checkTransportReply(client, "vfs.provider.ftp/time-reply.error", relPath);
            return null;
        });
    }

    @Override
    public Reply mdtmReply(final String relPath) throws IOException {
        return read(client -> {
            final String time = client.getModificationTime(relPath);
            checkTransportReply(client, "vfs.provider.ftp/time-reply.error", relPath);
            final int code = client.getReplyCode();
            return code == FTPReply.FILE_STATUS && time != null ? new Reply(code, time) : replyOf(client);
        });
    }

    @Override
    public boolean removeDirectory(final String relPath) throws IOException {
        return change(client -> client.removeDirectory(relPath));
    }

    @Override
    public boolean rename(final String oldName, final String newName) throws IOException {
        return change(client -> client.rename(oldName, newName));
    }

    @Override
    public RenameReplies renameSteps(final String from, final String to) throws IOException {
        return change(client -> {
            final ReplyWatch watch = new ReplyWatch();
            client.addProtocolCommandListener(watch);
            try {
                final Reply fromReply = step(client, watch, () -> client.rnfr(from));
                if (!FTPReply.isPositiveIntermediate(fromReply.getCode())) {
                    return new RenameReplies(fromReply, null);
                }
                return new RenameReplies(fromReply, step(client, watch, () -> client.rnto(to)));
            } finally {
                client.removeProtocolCommandListener(watch);
            }
        });
    }

    private interface Send {
        void run() throws IOException;
    }

    // One step of a rename. commons net throws when it reads a reply 421, after it has reported that reply. A 421 that
    // came for this command is the reply of the step, and the caller retires the client. An error with no reply for
    // this command leaves the step with that error, and the rename may have worked. An older reply decides nothing.
    private static Reply step(final FTPClient client, final ReplyWatch watch, final Send send) throws IOException {
        watch.reply = null;
        try {
            send.run();
        } catch (final IOException e) {
            final Reply came = watch.reply;
            if (came != null && came.getCode() == FTPReply.SERVICE_NOT_AVAILABLE) {
                return came;
            }
            throw e;
        }
        return replyOf(client);
    }

    // The reply that the client reported after the watch was cleared.
    private static final class ReplyWatch implements ProtocolCommandListener {

        private Reply reply;

        @Override
        public void protocolCommandSent(final ProtocolCommandEvent event) {
            // Only the reply counts.
        }

        @Override
        public void protocolReplyReceived(final ProtocolCommandEvent event) {
            final String text = event.getMessage();
            reply = new Reply(event.getReplyCode(), text == null ? "" : text.trim());
        }
    }

    @Override
    public InputStream retrieveFileStream(final String relPath) throws IOException {
        return read(client -> client.retrieveFileStream(relPath));
    }

    @Override
    public InputStream retrieveFileStream(final String relPath, final int bufferSize) throws IOException {
        return read(client -> {
            client.setBufferSize(bufferSize);
            return client.retrieveFileStream(relPath);
        });
    }

    @Override
    public InputStream retrieveFileStream(final String relPath, final long restartOffset) throws IOException {
        return read(client -> {
            client.setRestartOffset(restartOffset);
            return client.retrieveFileStream(relPath);
        });
    }

    @Override
    public void setBufferSize(final int bufferSize) throws FileSystemException {
        getFtpClient(System.nanoTime()).setBufferSize(bufferSize);
    }

    @Override
    public Reply sizeReply(final String relPath) throws IOException {
        return read(client -> {
            final String size = client.getSize(relPath);
            checkTransportReply(client, "vfs.provider.ftp/size-reply.error", relPath);
            final int code = client.getReplyCode();
            return code == FTPReply.FILE_STATUS && size != null ? new Reply(code, size) : replyOf(client);
        });
    }

    @Override
    public OutputStream storeFileStream(final String relPath) throws IOException {
        return change(client -> client.storeFileStream(relPath));
    }

    // A command that changes the server. After an IOException the client is aborted and the command is not sent
    // again, because the server may have done it.
    private <T> T change(final Command<T> command) throws IOException {
        try {
            return command.run(getFtpClient(System.nanoTime()));
        } catch (final IOException e) {
            abortNow();
            throw e;
        }
    }

    // A command that only reads. The fast window opens when the call enters the wrapper. The command is sent once
    // more on a new client only when the first try ended with a transport error and the window is still open. The
    // connect of that client runs in the same window.
    private <T> T read(final Command<T> command) throws IOException {
        final long windowStart = System.nanoTime();
        try {
            return command.run(getFtpClient(windowStart));
        } catch (final IOException e) {
            abortNow();
            if (!Faults.isTransport(e) || !Faults.isFast(windowStart)) {
                throw e;
            }
        }
        try {
            return command.run(getFtpClient(windowStart));
        } catch (final IOException e) {
            abortNow();
            throw e;
        }
    }
}
