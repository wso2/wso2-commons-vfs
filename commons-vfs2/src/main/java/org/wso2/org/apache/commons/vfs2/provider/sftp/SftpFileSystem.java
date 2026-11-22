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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

/**
 * Represents the files on an SFTP server.
 */
public class SftpFileSystem extends AbstractFileSystem {

    private static final Log LOG = LogFactory.getLog(SftpFileSystem.class);

    static String quote(final String path) {
        final StringBuilder quoted = new StringBuilder(path.length());
        for (int offset = 0; offset < path.length(); offset++) {
            final char ch = path.charAt(offset);
            if (ch == '\\' || ch == '*' || ch == '?') {
                quoted.append('\\');
            }
            quoted.append(ch);
        }
        return quoted.toString();
    }

    private static final int UNIDENTIFIED = -1;

    private static final int EXEC_BUFFER_SIZE = 128;

    // One deadline for the first permission check: both commands, and the open, the request, the read and the end of
    // each.
    private static final long PROBE_TIMEOUT_MILLIS = 10000;

    private static final long PROBE_POLL_MILLIS = 50;

    private static final String CLOSED_REASON = "The SFTP file system was closed";

    private static final String DISCARD_REASON = "A call on the SFTP session ended with no reply of the server";

    private static final String ABORT_REASON = "The SFTP file system was aborted";

    private static final long LAST_MOD_TIME_ACCURACY = 1000L;

    static final String HARDLINK = "hardlink@openssh.com";

    static final String POSIX_RENAME = "posix-rename@openssh.com";

    // The value of an extension that the server announces.
    static final String ANNOUNCED = "1";

    // A folder key that is not in its map is not known.
    enum LinkState {
        LINKS, NO_LINK
    }

    enum ReplaceState {
        REPLACES, NO_REPLACE
    }

    // The folders and folder pairs that a WARN was logged for, in this copy of the fork, so a new file system for the
    // same folder does not log it again.
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    // The link state per folder key: the folder of a name for an exclusive create, the pair of the source folder and
    // the target folder for a link. "Links" never changes again.
    private final ConcurrentMap<String, LinkState> linkStates = new ConcurrentHashMap<>();

    // The replace state per target folder, used only on a server that does not announce the posix rename. "Replaces"
    // never changes again.
    private final ConcurrentMap<String, ReplaceState> replaceStates = new ConcurrentHashMap<>();

    // What the server announced when the last channel started. Null before the first channel.
    private volatile Boolean hardlinkAnnounced;

    private volatile Boolean posixRenameAnnounced;

    /**
     * A session and the group of its sockets. They are published together, so the discard of one session never aborts
     * the group of the session that replaced it.
     */
    private static final class Link {

        private final Session session;

        private final SocketGroup group;

        Link(final Session session, final SocketGroup group) {
            this.session = Objects.requireNonNull(session, "session");
            this.group = Objects.requireNonNull(group, "group");
        }
    }

    /**
     * The session of now and its group. Never null.
     * <p>
     * DCL pattern requires that the ivar be volatile.
     * </p>
     */
    private volatile Link link;

    // The group of a session that connects now, so an abort of this file system also ends that connect.
    private volatile SocketGroup connecting;

    private volatile ChannelSftp idleChannel;

    private volatile boolean isFileSystemClosed;

    // The channels that were handed out and did not come back yet.
    private final AtomicInteger handedOut = new AtomicInteger();

    // The time that the open of an SFTP channel may take, from the bounds in effect. Zero means no bound.
    private final int channelOpenMillis;

    /**
     * Cache for the user ID (-1 when not set)
     * <p>
     * DCL pattern requires that the ivar be volatile.
     * </p>
     */
    private volatile int uid = UNIDENTIFIED;

    /**
     * Cache for the user groups ids (null when not set)
     * <p>
     * DCL pattern requires that the ivar be volatile.
     * </p>
     */
    private volatile int[] groupsIds;

    /**
     * Whether the server runs the commands of the permission check over an exec channel. Some SFTP only servers
     * disable the exec channel, and then getUId() can not work. The first permission check that needs the user id
     * settles it.
     */
    private enum ExecState {
        NOT_KNOWN, ENABLED, DISABLED
    }

    private volatile ExecState execState;

    // The warning of a disabled exec channel is given once for this file system.
    private boolean execWarned;

    /**
     * Constructs a new instance.
     *
     * @param rootName The root file name of this file system.
     * @param session The session.
     * @param group The group of the sockets of the session.
     * @param fileSystemOptions Options to build this file system.
     */
    protected SftpFileSystem(final GenericFileName rootName, final Session session, final SocketGroup group,
            final FileSystemOptions fileSystemOptions) {
        super(rootName, null, fileSystemOptions);
        this.link = new Link(session, group);
        channelOpenMillis = (int) EffectiveBounds.of("sftp", fileSystemOptions).getConnectTimeout().getValue();
        final SftpFileSystemConfigBuilder configBuilder = SftpFileSystemConfigBuilder.getInstance();
        if (configBuilder.isDisableDetectExecChannel(fileSystemOptions)
                || "true".equalsIgnoreCase(configBuilder.getAvoidPermissionCheck(fileSystemOptions))) {
            execState = ExecState.DISABLED;
        } else {
            execState = ExecState.NOT_KNOWN;
        }
    }

    /**
     * Adds the capabilities of this file system.
     */
    @Override
    protected void addCapabilities(final Collection<Capability> caps) {
        caps.addAll(SftpFileProvider.capabilities);
    }

    /**
     * Creates a file object. This method is called only if the requested file is not cached.
     */
    @Override
    protected FileObject createFile(final AbstractFileName name) throws FileSystemException {
        return new SftpFileObject(name, this);
    }

    @Override
    protected void doCloseCommunicationLink() {
        isFileSystemClosed = true;
        if (idleChannel != null) {
            synchronized (this) {
                if (idleChannel != null) {
                    idleChannel.disconnect();
                    idleChannel = null;
                }
            }
        }

        link.session.disconnect();
    }

    /**
     * Executes a command and returns the (standard) output through a StringBuilder.
     * <p>
     * The open of the channel, the request, the read of the reply and the wait for the end all end at the deadline,
     * also while bytes keep coming. The reply is read with no blocking read. An answer that comes after the deadline
     * is not taken. The command runs on the session that the permission check chose, and it never makes a new one.
     * </p>
     *
     * @param current The session of this permission check.
     * @param command The command
     * @param output  The output
     * @param deadline The deadline on the clock of {@link System#nanoTime()}.
     * @return The exit code of the command, or null when the server refused the channel or the request, or did not
     *         answer before the deadline.
     * @throws JSchException       if the session failed. The session is not used again.
     * @throws IOException         if an I/O error is detected, or the thread was interrupted. An interrupt also
     *                             ends the session, and the thread keeps its interrupt.
     */
    private Integer executeCommand(final Session current, final String command, final StringBuilder output,
            final long deadline) throws JSchException, IOException {
        if (deadline - System.nanoTime() <= 0) {
            return null;
        }
        if (!current.isConnected()) {
            // The session of this check is gone. A new one is the job of the next operation, with its own bounds.
            discardSession(current);
            throw new JSchException("The SFTP session of the permission check ended before the command \""
                    + command + "\".");
        }
        final ChannelExec channel;
        boolean interrupted = false;
        try {
            channel = (ChannelExec) current.openChannel("exec");
        } catch (final JSchException e) {
            discardSession(current);
            throw e;
        }
        try {
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(System.err, true);
            final InputStream stream = channel.getInputStream();
            failIfInterrupted(command);
            try {
                // JSch waits once with this value for the open of the channel and once more for the reply to the
                // request, so each wait gets half of the time that is left.
                channel.connect((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) / 2));
            } catch (final JSchException e) {
                if (isTransport(e, current)) {
                    throw e;
                }
                // A refusal of the open or of the request, or no answer in time. The session is still in use.
                failIfInterrupted(command);
                LOG.debug("The exec channel for \"" + command + "\" got no answer or was refused.", e);
                return null;
            }
            final ByteArrayOutputStream reply = new ByteArrayOutputStream();
            final byte[] buffer = new byte[EXEC_BUFFER_SIZE];
            while (true) {
                failIfInterrupted(command);
                if (deadline - System.nanoTime() <= 0) {
                    return null;
                }
                // The close is read before the count. JSch puts the reply into the pipe before it marks the channel
                // closed, so a closed channel with an empty pipe has given every byte.
                final boolean closed = channel.isClosed();
                final int available = stream.available();
                if (available > 0) {
                    final int read = stream.read(buffer, 0, Math.min(available, buffer.length));
                    if (read > 0) {
                        reply.write(buffer, 0, read);
                    }
                } else if (closed) {
                    break;
                } else {
                    pause(command);
                }
            }
            failIfInterrupted(command);
            if (deadline - System.nanoTime() <= 0) {
                return null;
            }
            final int status = channel.getExitStatus();
            // A channel that ended with no exit status after the session went away is no answer of the server.
            if (status == -1 && isLost(current, command)) {
                throw new IOException("The SFTP session ended before the command \"" + command + "\" gave its exit status.");
            }
            output.append(new String(reply.toByteArray(), StandardCharsets.UTF_8));
            failIfInterrupted(command);
            return status;
        } catch (final JSchException | IOException | RuntimeException e) {
            // A failure of the session or an interrupt is no answer about exec. The session is not used again.
            interrupted = Thread.interrupted();
            discardSession(current);
            throw e;
        } finally {
            // The interrupt stays with the thread, also when a cleanup step of JSch cleared it.
            interrupted |= Thread.interrupted();
            channel.disconnect();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // The outcomes of JSch 2.27.7 that are an answer about exec: the open was refused or not confirmed in time, or the
    // request was refused or not answered in time (Channel.java and Request.java of that version).
    private static final List<String> NO_EXEC = Arrays.asList("channel is not opened.",
            "failed to send channel request", "channel request: timeout");

    // Transport evidence comes first: a wrapped failure, or a session that is gone. Then only an outcome of NO_EXEC is
    // an answer. Anything else, for example the end of the wait for a key exchange, is a failure of the session.
    private static boolean isTransport(final JSchException error, final Session current) {
        return error.getCause() != null || !current.isConnected() || !NO_EXEC.contains(error.getMessage());
    }

    // JSch closes the channels of a lost session before it marks the session as not connected. So the mark is read
    // again after one poll period.
    private static boolean isLost(final Session current, final String command) throws IOException {
        if (!current.isConnected()) {
            return true;
        }
        pause(command);
        return !current.isConnected();
    }

    private static void pause(final String command) throws InterruptedIOException {
        try {
            Thread.sleep(PROBE_POLL_MILLIS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            final InterruptedIOException interrupted = new InterruptedIOException("The command \"" + command
                    + "\" was interrupted.");
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    // An interrupt that the fork can see before the check starts, or after a command before its answer is taken, ends
    // the check as a failure of the session. The session is discarded and the thread keeps its interrupt.
    private void endIfInterrupted(final Session selected, final String message) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            Thread.interrupted();
            try {
                discardSession(selected);
            } finally {
                Thread.currentThread().interrupt();
            }
            throw new InterruptedIOException(message);
        }
    }

    // An interrupt that the fork can see ends the command as a failure of its session, before JSch takes the thread
    // and before an answer or a fallback is taken. The thread keeps its interrupt.
    private static void failIfInterrupted(final String command) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("The command \"" + command + "\" was interrupted.");
        }
    }

    // Called under the monitor of this file system. The warning is given once.
    private void disableExec() {
        execState = ExecState.DISABLED;
        if (!execWarned) {
            execWarned = true;
            final GenericFileName root = (GenericFileName) getRootName();
            LOG.warn("The SFTP server " + root.getHostName() + ":" + root.getPort()
                    + " did not answer the permission check over an exec channel within " + PROBE_TIMEOUT_MILLIS
                    + " ms, or refused the channel. The permission checks of this file system use the owner"
                    + " permissions from now on.");
        }
    }

    /**
     * Returns an SFTP channel to the server.
     *
     * @return new or reused channel, never null.
     * @throws FileSystemException if a session cannot be created.
     * @throws IOException         if an I/O error is detected.
     */
    protected ChannelSftp getChannel() throws IOException {
        // Use the pooled channel, or create a new one
        ChannelSftp channel = null;
        if (idleChannel != null) {
            synchronized (this) {
                if (idleChannel != null) {
                    channel = idleChannel;
                    idleChannel = null;
                }
            }
        }

        // An idle channel is handed out only when local state says that it and its session are still up, that its
        // session is the one of now, and that the file system is open. A half open session still looks up here, and
        // the bounds of the session find it.
        if (channel != null && (isFileSystemClosed || !isUp(channel) || !isCurrent(channel))) {
            channel.disconnect();
            channel = null;
        }

        final boolean opened = channel == null;
        if (opened) {
            channel = openChannel();
        }
        // From here the channel goes back through putChannel or discardChannel.
        handedOut.incrementAndGet();

        if (opened) {
            final Boolean userDirIsRoot = SftpFileSystemConfigBuilder.getInstance()
                .getUserDirIsRoot(getFileSystemOptions());
            final String workingDirectory = getRootName().getPath();
            if (workingDirectory != null && (userDirIsRoot == null || !userDirIsRoot.booleanValue())) {
                try {
                    channel.cd(quote(workingDirectory));
                } catch (final SftpException e) {
                    // A channel that is not in the work directory is never pooled.
                    channel.disconnect();
                    releaseChannel(channel, e);
                    throw new FileSystemException("vfs.provider.sftp/change-work-directory.error", workingDirectory,
                        e);
                }
            }
        }

        final String fileNameEncoding = SftpFileSystemConfigBuilder.getInstance()
            .getFileNameEncoding(getFileSystemOptions());

        if (fileNameEncoding != null) {
            try {
                channel.setFilenameEncoding(fileNameEncoding);
            } catch (final SftpException e) {
                releaseChannel(channel, e);
                throw new FileSystemException("vfs.provider.sftp/filename-encoding.error", fileNameEncoding);
            }
        }
        return channel;
    }

    // Opens a new SFTP channel. A failed open discards the session that opened it, and the channel is not counted.
    private ChannelSftp openChannel() throws FileSystemException {
        final Session current = getSession();
        try {
            final ChannelSftp channel = (ChannelSftp) current.openChannel("sftp");
            channel.connect(channelOpenMillis);
            hardlinkAnnounced = ANNOUNCED.equals(channel.getExtension(HARDLINK));
            posixRenameAnnounced = ANNOUNCED.equals(channel.getExtension(POSIX_RENAME));
            return channel;
        } catch (final JSchException e) {
            discardSession(current);
            throw new FileSystemException("vfs.provider.sftp/connect.error", getRootName().getFriendlyURI(), e);
        }
    }

    // Whether the channel belongs to the session of now.
    private boolean isCurrent(final ChannelSftp channel) {
        try {
            return channel.getSession() == link.session;
        } catch (final JSchException e) {
            return false;
        }
    }

    // Local state only. No packet goes to the server.
    private static boolean isUp(final ChannelSftp channel) {
        if (!channel.isConnected() || channel.isClosed()) {
            return false;
        }
        try {
            return channel.getSession().isConnected();
        } catch (final JSchException e) {
            return false;
        }
    }

    /**
     * Gets the (numeric) group IDs.
     *
     * @return the (numeric) group IDs.
     * @throws JSchException If a problem occurs while retrieving the group IDs.
     * @throws IOException   if an I/O error is detected.
     * @since 2.1
     */
    public int[] getGroupsIds() throws JSchException, IOException {
        return groupsIds(true);
    }

    // The groups for the permission check of a file object, which never connects. See probeIds.
    int[] getGroupsIdsForCheck() throws JSchException, IOException {
        return groupsIds(false);
    }

    private int[] groupsIds(final boolean connect) throws JSchException, IOException {
        if (groupsIds == null) {
            synchronized (this) {
                // DCL pattern requires that the ivar be volatile.
                if (groupsIds == null) {
                    if (execState != ExecState.DISABLED) {
                        probeIds(connect);
                    }
                    if (groupsIds == null) {
                        // Exec is disabled, so there are no groups to tell.
                        return new int[0];
                    }
                }
            }
        }
        return groupsIds;
    }

    /**
     * Settles the user id and the group ids that are not known yet, with one deadline for both commands. Called under
     * the monitor of this file system. An answer that is no user id, a refusal and the deadline disable exec. A
     * failure of the session leaves the state as it was, so the next check probes again on a new session.
     * <p>
     * The deadline starts with the call. Both commands run on one session. With connect, a session that is gone is
     * replaced first, and its whole setup, the connect, the banner, the key exchange and the login, ends at the same
     * deadline. Without it, as for the check of a file object that has just read its attributes, the session of now
     * is taken as it is, and a session that is gone is a failure of the session. A caller that is interrupted
     * already gets no new session.
     * </p>
     */
    private void probeIds(final boolean connect) throws JSchException, IOException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_TIMEOUT_MILLIS);
        endIfInterrupted(link.session, "The permission check was interrupted before it started.");
        final Session selected = connect ? getSession(deadline) : link.session;
        if (uid == UNIDENTIFIED) {
            final StringBuilder output = new StringBuilder();
            final Integer code = executeCommand(selected, "id -u", output, deadline);
            endIfInterrupted(selected, "The command \"id -u\" was interrupted.");
            final String uidString = output.toString().trim();
            if (code != null && code == 0) {
                try {
                    uid = Integer.parseInt(uidString);
                } catch (final NumberFormatException e) {
                    LOG.debug("Cannot convert UID to integer: '" + uidString + "'", e);
                }
            }
            if (uid == UNIDENTIFIED) {
                disableExec();
                return;
            }
        }
        if (groupsIds == null) {
            final StringBuilder output = new StringBuilder();
            final Integer code = executeCommand(selected, "id -G", output, deadline);
            endIfInterrupted(selected, "The command \"id -G\" was interrupted.");
            if (code == null) {
                disableExec();
                return;
            }
            if (code != 0) {
                throw new JSchException("Could not get the groups id of the current user (error code: " + code + ")");
            }
            groupsIds = parseGroupIdOutput(output);
        }
        // Exec counts as enabled only when both answers came.
        execState = ExecState.ENABLED;
    }

    /**
     * Last modification time is only an int and in seconds, thus can be off by 999.
     *
     * @return 1000
     */
    @Override
    public double getLastModTimeAccuracy() {
        return LAST_MOD_TIME_ACCURACY;
    }

    /**
     * Ensures that the session link is established.
     *
     * @throws FileSystemException if a session cannot be created.
     */
    private Session getSession() throws FileSystemException {
        return getSession(0);
    }

    /**
     * Ensures that the session link is established. A new session that a deadline bounds ends its whole setup at it.
     *
     * @param setupDeadline the deadline on the clock of {@link System#nanoTime()}, or 0 for the bounds of the
     *            endpoint alone.
     * @throws FileSystemException if a session cannot be created.
     */
    private Session getSession(final long setupDeadline) throws FileSystemException {
        // A closed file system never opens a session. The caller resolves a new file system.
        if (isFileSystemClosed) {
            throw closedError();
        }
        Link current = link;
        if (!current.session.isConnected()) {
            synchronized (this) {
                if (isFileSystemClosed) {
                    throw closedError();
                }
                current = link;
                if (!current.session.isConnected()) {
                    // Clean up the stale session/channel directly rather than via doCloseCommunicationLink(),
                    // which marks the file system as permanently closed for putChannel()'s pooling decision.
                    // This is a reconnect on the same instance, not a teardown.
                    if (idleChannel != null) {
                        idleChannel.disconnect();
                        idleChannel = null;
                    }
                    current.session.disconnect();
                    final SocketGroup fresh =
                            ((DefaultFileSystemManager) getFileSystemManager()).newSocketGroup();
                    connecting = fresh;
                    try {
                        final Session created = SftpFileProvider.createSession((GenericFileName) getRootName(),
                            getFileSystemOptions(), fresh, setupDeadline);
                        // The file system can close while the session connects. Such a session is not kept.
                        if (isFileSystemClosed) {
                            fresh.abort(CLOSED_REASON);
                            created.disconnect();
                            throw closedError();
                        }
                        link = new Link(created, fresh);
                    } finally {
                        // The link is published before this field is cleared, so an abort always finds the group.
                        connecting = null;
                    }
                    current = link;
                }
            }
        }
        return current.session;
    }

    private FileSystemException closedError() {
        return new FileSystemException("vfs.provider/closed.error", getRootName().getFriendlyURI());
    }

    /**
     * Gets the (numeric) group IDs.
     *
     * @return The numeric user ID
     * @throws JSchException If a problem occurs while retrieving the group ID.
     * @throws IOException   if an I/O error is detected.
     * @since 2.1
     */
    public int getUId() throws JSchException, IOException {
        return uid(true);
    }

    // The user id for the permission check of a file object, which never connects. See probeIds.
    int getUIdForCheck() throws JSchException, IOException {
        return uid(false);
    }

    private int uid(final boolean connect) throws JSchException, IOException {
        if (uid == UNIDENTIFIED) {
            synchronized (this) {
                if (uid == UNIDENTIFIED && execState != ExecState.DISABLED) {
                    probeIds(connect);
                }
            }
        }
        return uid;
    }

    /**
     * Tests whether the exec channel is disabled. The first call settles it by the probe of the user id and the group
     * ids, with one deadline for both. Later calls answer from the cached state.
     *
     * @return Whether the exec channel is disabled.
     * @throws JSchException If the session failed during the probe. The state then stays as it was.
     * @throws IOException   if an I/O error is detected, or the thread was interrupted.
     */
    public boolean isExecDisabled() throws JSchException, IOException {
        return execDisabled(true);
    }

    // The state for the permission check of a file object, which never connects. See probeIds.
    boolean isExecDisabledForCheck() throws JSchException, IOException {
        return execDisabled(false);
    }

    private boolean execDisabled(final boolean connect) throws JSchException, IOException {
        if (execState == ExecState.NOT_KNOWN) {
            synchronized (this) {
                if (execState == ExecState.NOT_KNOWN) {
                    probeIds(connect);
                }
            }
        }
        return execState == ExecState.DISABLED;
    }

    /**
     * Parses the output of the 'id -G' command
     *
     * @param output The output from the command
     * @return the (numeric) group IDs.
     */
    int[] parseGroupIdOutput(final StringBuilder output) {
        // Retrieve the different groups
        final String[] groups = output.toString().trim().split("\\s+");
        // Deal with potential empty groups
        return Arrays.stream(groups).map(String::trim).filter(s -> !s.isEmpty()).mapToInt(Integer::parseInt).toArray();
    }

    /**
     * Puts a channel back or discards it, by how the call on it ended. This is the one place of that decision.
     * <p>
     * The channel goes back after a call that returned, and after an {@link SftpException} with no cause, which is a
     * status reply of the server. It is discarded after an {@link SftpException} with a cause, after any other
     * exception, and when the thread is interrupted. So a channel or a session is never used again after a call on
     * it ended with no reply of the server.
     * </p>
     *
     * @param channel the channel that was handed out.
     * @param error the exception that ended the call, or null when the call returned.
     */
    protected void releaseChannel(final ChannelSftp channel, final Throwable error) {
        if (isReplyOrSuccess(error) && !Thread.currentThread().isInterrupted()) {
            putChannel(channel);
        } else {
            discardChannel(channel);
        }
    }

    /**
     * Tells whether a call ended with a reply of the server: it returned, or it ended with an {@link SftpException}
     * that has no cause.
     *
     * @param error the exception that ended the call, or null when the call returned.
     * @return true when the server replied.
     */
    static boolean isReplyOrSuccess(final Throwable error) {
        return error == null || error instanceof SftpException && error.getCause() == null;
    }

    /**
     * Discards a channel and its session. The sockets of the session are closed first, so the disconnects that
     * follow wait for nothing. The next channel comes from a new session with a new group. It takes no monitor of
     * this file system.
     *
     * @param channel the channel that was handed out.
     */
    protected void discardChannel(final ChannelSftp channel) {
        handedOut.decrementAndGet();
        Session channelSession = null;
        try {
            channelSession = channel.getSession();
        } catch (final JSchException e) {
            // The channel has no session any more.
        }
        if (channelSession == null) {
            channel.disconnect();
            return;
        }
        abortIfCurrent(channelSession);
        channel.disconnect();
        channelSession.disconnect();
    }

    private void discardSession(final Session discarded) {
        abortIfCurrent(discarded);
        discarded.disconnect();
    }

    // Only the group of the same session is aborted. A session that was replaced before had its sockets closed by its
    // disconnect, and the group of the session that replaced it is not touched.
    private void abortIfCurrent(final Session candidate) {
        final Link current = link;
        if (current.session == candidate) {
            current.group.abort(DISCARD_REASON);
        }
    }

    /**
     * Closes the sockets of the session of this file system, and of a session that connects now. It sends nothing,
     * waits for nothing and takes no monitor, so any thread may call it.
     */
    @Override
    public void abort() {
        // Read before the link: a reconnect publishes the link before it clears this field.
        final SocketGroup pending = connecting;
        if (pending != null) {
            pending.abort(ABORT_REASON);
        }
        link.group.abort(ABORT_REASON);
    }

    /**
     * Tells from local state whether the session looks alive: it is connected and its group was not aborted.
     *
     * @return false when the session is known to be down.
     */
    @Override
    public boolean isAliveLocally() {
        final Link current = link;
        return current.session.isConnected() && !current.group.isAborted();
    }

    /**
     * Gets the number of channels that were handed out and did not come back.
     *
     * @return the count.
     */
    @Override
    public int getHandedOutCount() {
        return handedOut.get();
    }

    /**
     * Gets the number of connections that this file system holds open: one for a connected session.
     *
     * @return the count.
     */
    @Override
    public int getOpenClientCount() {
        return link.session.isConnected() ? 1 : 0;
    }

    /**
     * Returns a channel to the pool. Only a channel that is up, of the session of now, on an open file system and
     * with no other idle channel is kept. Any other channel is disconnected.
     *
     * @param channelSftp the SFTP channel.
     */
    Boolean isHardlinkAnnounced() {
        return hardlinkAnnounced;
    }

    Boolean isPosixRenameAnnounced() {
        return posixRenameAnnounced;
    }

    LinkState getLinkState(final String key) {
        return linkStates.get(key);
    }

    void markLinks(final String key) {
        linkStates.put(key, LinkState.LINKS);
    }

    // A key that links keeps that state.
    void markNoLink(final String key) {
        linkStates.putIfAbsent(key, LinkState.NO_LINK);
    }

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

    // True only for the first WARN about this subject on this server in this copy of the fork.
    boolean firstWarning(final String subject) {
        final GenericFileName root = (GenericFileName) getRootName();
        return WARNED.add(root.getHostName() + ":" + root.getPort() + " " + subject);
    }

    protected void putChannel(final ChannelSftp channelSftp) {
        handedOut.decrementAndGet();
        if (!isFileSystemClosed && idleChannel == null && isUp(channelSftp) && isCurrent(channelSftp)) {
            synchronized (this) {
                // The close sets its flag before it clears the idle channel under this monitor, so the flag is read
                // again here.
                if (!isFileSystemClosed && idleChannel == null && isCurrent(channelSftp)) {
                    idleChannel = channelSftp;
                    return;
                }
            }
        }
        channelSftp.disconnect();
    }

}
