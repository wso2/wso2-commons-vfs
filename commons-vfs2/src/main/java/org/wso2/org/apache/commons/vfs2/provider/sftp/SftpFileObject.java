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

import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import static org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystem.quote;

import java.util.Objects;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileNotFoundException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.RandomAccessContent;
import org.wso2.org.apache.commons.vfs2.VFS;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystem.LinkState;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystem.ReplaceState;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.PosixPermissions;
import org.wso2.org.apache.commons.vfs2.util.ProgressWatchdog;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

/**
 * An SFTP file.
 */
public class SftpFileObject extends AbstractFileObject<SftpFileSystem> implements AtomicOps {

    private void checkStreamClose() throws IOException {
        if (TestPoints.enabled()) {
            final String key = pointKey(getName());
            TestPoints.hold("stream.close", key);
            final TestPoints.Fault fault = TestPoints.fault("stream.close", key);
            if (fault != null && "close-fails".equals(fault.token())) {
                throw new IOException("The stream close failed");
            }
        }
    }

    private static final char[] CHILD_RESERVED = { '?', '\\' };

    private static final Log LOG = LogFactory.getLog(SftpFileObject.class);

    /**
     * An InputStream that monitors for end-of-file.
     * <p>
     * It notes the first exception of a read or a close on the stream of JSch. Its close puts the channel back only
     * when none was noted. Else it discards the channel and its session.
     * </p>
     */
    private final class SftpInputStream extends MonitorInputStream {
        private final ChannelSftp channel;

        private final NotedInput source;

        SftpInputStream(final ChannelSftp channel, final InputStream in) {
            this(channel, new NotedInput(channel, in));
        }

        SftpInputStream(final ChannelSftp channel, final InputStream in, final int bufferSize) {
            this(channel, new NotedInput(channel, in), bufferSize);
        }

        private SftpInputStream(final ChannelSftp channel, final NotedInput source) {
            super(source);
            this.channel = channel;
            this.source = source;
        }

        private SftpInputStream(final ChannelSftp channel, final NotedInput source, final int bufferSize) {
            super(source, bufferSize);
            this.channel = channel;
            this.source = source;
        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            releaseNoted(channel, source.failure);
        }
    }

    /**
     * An OutputStream that wraps an sftp OutputStream, and closes the channel when the stream is closed.
     * <p>
     * It notes the first exception of a write, a flush or a close on the stream of JSch. Its close puts the channel
     * back only when none was noted. Else it discards the channel and its session.
     * </p>
     */
    private final class SftpOutputStream extends MonitorOutputStream {
        private final ChannelSftp channel;

        private final NotedOutput target;

        SftpOutputStream(final ChannelSftp channel, final OutputStream out) {
            this(channel, new NotedOutput(channel, out));
        }

        private SftpOutputStream(final ChannelSftp channel, final NotedOutput target) {
            super(target);
            this.channel = channel;
            this.target = target;
        }

        /**
         * Called after this stream is closed.
         */
        @Override
        protected void onClose() throws IOException {
            releaseNoted(channel, target.failure);
        }
    }

    /**
     * The stream of JSch for a read, which notes its first exception.
     */
    private final class NotedInput extends FilterInputStream {

        private volatile Throwable failure;

        // One read or write of the stream gets the bound of a request, the time between two does not.
        private final ProgressWatchdog.Wait wait;

        NotedInput(final ChannelSftp channel, final InputStream in) {
            super(in);
            wait = getAbstractFileSystem().waitFor(channel);
        }

        @Override
        public int read() throws IOException {
            wait.progress();
            try {
                return super.read();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            wait.progress();
            try {
                return super.read(buffer, offset, length);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        @Override
        public long skip(final long count) throws IOException {
            wait.progress();
            try {
                return super.skip(count);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        @Override
        public void close() throws IOException {
            wait.progress();
            try {
                try {
                    checkStreamClose();
                } finally {
                    super.close();
                }
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
        }
    }

    /**
     * The stream of JSch for a write, which notes its first exception.
     */
    private final class NotedOutput extends FilterOutputStream {

        private volatile Throwable failure;

        // One read or write of the stream gets the bound of a request, the time between two does not.
        private final ProgressWatchdog.Wait wait;

        NotedOutput(final ChannelSftp channel, final OutputStream out) {
            super(out);
            wait = getAbstractFileSystem().waitFor(channel);
        }

        @Override
        public void write(final int value) throws IOException {
            wait.progress();
            try {
                out.write(value);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException {
            wait.progress();
            try {
                out.write(buffer, offset, length);
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        @Override
        public void flush() throws IOException {
            wait.progress();
            try {
                out.flush();
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        // The stream above flushes before it closes, so the close does not flush again.
        @Override
        public void close() throws IOException {
            wait.progress();
            try {
                try {
                    checkStreamClose();
                } finally {
                    out.close();
                }
            } catch (final IOException | RuntimeException e) {
                note(e);
                throw e;
            } finally {
                wait.end();
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
        }
    }
    private static final long MOD_TIME_FACTOR = 1000L;

    private SftpATTRS attrs;

    private final String relPath;

    /**
     * Constructs a new instance.
     *
     * @param fileName the file name.
     * @param fileSystem the file system.
     * @throws FileSystemException if a file system error occurs.
     */
    protected SftpFileObject(final AbstractFileName fileName, final SftpFileSystem fileSystem) throws FileSystemException {
        super(fileName, fileSystem);
        relPath = UriParser.decode(fileSystem.getRootName().getRelativeName(fileName));
    }

    /**
     * Creates this file as a folder.
     */
    @Override
    protected void doCreateFolder() throws Exception {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            send(channel, () -> channel.mkdir(relPath));
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    /**
     * Deletes the file.
     */
    @Override
    protected void doDelete() throws Exception {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // Only the failure of a call on this channel decides what happens to it.
        Throwable failure = null;
        try {
            final boolean file = isFile();
            try {
                if (file) {
                    send(channel, () -> channel.rm(quote(relPath)));
                } else {
                    send(channel, () -> channel.rmdir(quote(relPath)));
                }
            } catch (final Throwable e) {
                failure = e;
                throw e;
            }
        } finally {
            release(channel, failure);
        }
    }

    /** @since 2.0 */
    @Override
    protected synchronized void doDetach() throws Exception {
        attrs = null;
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected synchronized long doGetContentSize() throws Exception {
        if (attrs == null || (attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_SIZE) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-size.error");
        }
        return attrs.getSize();
    }

    /**
     * Creates an input stream to read the file content from.
     */
    @SuppressWarnings("resource")
    @Override
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        // VFS-113: avoid NPE.
        synchronized (getAbstractFileSystem()) {
            // VFS-210: sftp allows to gather an input stream even from a directory and will fail on first read, so
            // the type is checked. It is checked before the channel of the read is taken: a file object with no
            // attributes asks the server for them, and with the channel of the read held that opens a second channel.
            if (!getType().hasContent()) {
                throw new FileSystemException("vfs.provider/read-not-file.error", getName());
            }
            final ChannelSftp channel = getAbstractFileSystem().getChannel();
            // return channel.get(getName().getPath());
            // hmmm - using the in memory method is soooo much faster ...

            // TODO - Don't read the entire file into memory. Use the
            // stream-based methods on ChannelSftp once they work properly

            /*
             * final ByteArrayOutputStream outstr = new ByteArrayOutputStream(); channel.get(relPath, outstr); outstr.close();
             * return new ByteArrayInputStream(outstr.toByteArray());
             */

            final InputStream inputStream;
            try {
                inputStream = request(channel, wait -> channel.get(quote(relPath)));
            } catch (final SftpException e) {
                release(channel, e);
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && e.getCause() == null) {
                    throw new FileNotFoundException(getName());
                }
                throw new FileSystemException(e);
            } catch (final Throwable e) {
                release(channel, e);
                throw e;
            }
            return new SftpInputStream(channel, inputStream, bufferSize);
        }
    }

    @Override
    protected synchronized long doGetLastModifiedTime() throws Exception {
        if (attrs == null || (attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_ACMODTIME) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-modtime.error");
        }
        return attrs.getMTime() * MOD_TIME_FACTOR;
    }

    /**
     * Creates an output stream to write the file content to.
     */
    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        // TODO - Don't write the entire file into memory. Use the stream-based
        // methods on ChannelSftp once the work properly
        /*
         * final ChannelSftp channel = getAbstractFileSystem().getChannel(); return new SftpOutputStream(channel);
         */

        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        try {
            final OutputStream out = request(channel,
                    wait -> channel.put(quote(relPath), bAppend ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE));
            return new SftpOutputStream(channel, out);
        } catch (final Throwable ex) {
            release(channel, ex);
            throw ex;
        }

    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        return new SftpRandomAccessContent(this, mode);
    }

    /**
     * Determines the type of this file, returns null if the file does not exist.
     */
    @Override
    protected synchronized FileType doGetType() throws Exception {
        if (attrs == null) {
            statSelf();
        }

        if (attrs == null) {
            return FileType.IMAGINARY;
        }

        if ((attrs.getFlags() & SftpATTRS.SSH_FILEXFER_ATTR_PERMISSIONS) == 0) {
            throw new FileSystemException("vfs.provider.sftp/unknown-permissions.error");
        }
        if (attrs.isDir()) {
            return FileType.FOLDER;
        }
        return FileType.FILE;
    }

    @Override
    protected boolean doIsExecutable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isExecutable();
        }
        return true;
    }

    @Override
    protected boolean doIsReadable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isReadable();
        }
        return true;
    }

    @Override
    protected boolean doIsWriteable() throws Exception {
        if (isPermissionCheckRequired()) {
            return getPermissions(true).isWritable();
        }
        return true;
    }

    private boolean isPermissionCheckRequired() {
        final String permissionCheck = SftpFileSystemConfigBuilder.getInstance()
                .getAvoidPermissionCheck(getAbstractFileSystem().getFileSystemOptions());
        return permissionCheck == null || !permissionCheck.equalsIgnoreCase("true");
    }

    /**
     * Lists the children of this file.
     */
    @Override
    protected String[] doListChildren() throws Exception {
        // use doListChildrenResolved for performance
        return null;
    }

    /**
     * Lists the children of this file.
     */
    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {
        // should not require a round-trip because type is already set.
        if (isFile()) {
            return null;
        }
        // List the contents of the folder
        final Vector<?> vector;
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // The failure that decides what happens to the channel.
        final Throwable[] failure = new Throwable[1];
        try {
            vector = list(channel, failure);
        } catch (final Throwable e) {
            if (failure[0] == null) {
                failure[0] = e;
            }
            throw e;
        } finally {
            release(channel, failure[0]);
        }
        if (vector == null) {
            // VFS-210: seems not to be a directory
            return null;
        }
        FileSystemException.requireNonNull(vector, "vfs.provider.sftp/list-children.error");

        // Extract the child names
        final ArrayList<FileObject> children = new ArrayList<>();
        for (@SuppressWarnings("unchecked") // OK because ChannelSftp.ls() is documented to return Vector<LsEntry>
             final Iterator<LsEntry> iterator = (Iterator<LsEntry>) vector.iterator(); iterator.hasNext();) {
            final LsEntry stat = iterator.next();

            String name = stat.getFilename();
            if (VFS.isUriStyle() && stat.getAttrs().isDir() && name.charAt(name.length() - 1) != '/') {
                name += "/";
            }

            if (name.equals(".") || name.equals("..") || name.equals("./") || name.equals("../")) {
                continue;
            }

            final FileObject fo = getFileSystem().resolveFile(getFileSystem().getFileSystemManager()
                    .resolveName(getName(), UriParser.encode(name, CHILD_RESERVED), NameScope.CHILD));

            ((SftpFileObject) FileObjectUtils.getAbstractFileObject(fo)).setStat(stat.getAttrs());

            children.add(fo);
        }

        return children.toArray(EMPTY_ARRAY);
    }

    /**
     * A request with no result, for {@link #send}.
     */
    private interface Call {
        void run() throws SftpException;
    }

    // One request under the bound of a request of the file system.
    private <T> T request(final ChannelSftp channel, final SftpFileSystem.Request<T> request) throws SftpException {
        return getAbstractFileSystem().request(channel, request);
    }

    private void send(final ChannelSftp channel, final Call call) throws SftpException {
        request(channel, wait -> {
            call.run();
            return null;
        });
    }

    private SftpATTRS statOn(final ChannelSftp channel) throws SftpException {
        return request(channel, wait -> channel.stat(quote(relPath)));
    }

    // A listing renews its wait with each entry, so a folder that keeps answering is never cut.
    private Vector<LsEntry> ls(final ChannelSftp channel, final String path) throws SftpException {
        return request(channel, wait -> {
            final Vector<LsEntry> entries = new Vector<>();
            channel.ls(path, entry -> {
                wait.progress();
                entries.add(entry);
                return ChannelSftp.LsEntrySelector.CONTINUE;
            });
            return entries;
        });
    }

    /**
     * Lists this folder on the channel. A failure with a cause, which is a broken connection, stops at once and the
     * caller discards the channel with its session. After a reply of the server the old way runs: a change into the
     * folder and a list of ".", and then the change back. A channel that can not change back is in the wrong folder,
     * so it is disconnected and never pooled.
     *
     * @param channel the channel.
     * @param failure set to the failure that decides what happens to the channel.
     * @return the listing, or null when the server says that this is not a folder.
     */
    private Vector<?> list(final ChannelSftp channel, final Throwable[] failure) throws Exception {
        try {
            // try the direct way to list the directory on the server to avoid too many round trips
            return ls(channel, quote(relPath));
        } catch (final SftpException e) {
            if (!SftpFileSystem.isReplyOrSuccess(e)) {
                failure[0] = e;
                throw e;
            }
        }
        String workingDirectory = null;
        try {
            if (relPath != null) {
                workingDirectory = request(channel, wait -> channel.pwd());
                send(channel, () -> channel.cd(quote(relPath)));
            }
        } catch (final SftpException ex) {
            failure[0] = ex;
            if (!SftpFileSystem.isReplyOrSuccess(ex)) {
                throw ex;
            }
            return null;
        }
        Vector<?> vector = null;
        SftpException lsEx = null;
        try {
            vector = ls(channel, quote("."));
        } catch (final SftpException ex) {
            failure[0] = ex;
            if (!SftpFileSystem.isReplyOrSuccess(ex)) {
                throw ex;
            }
            lsEx = ex;
        }
        if (relPath != null) {
            try {
                final String back = workingDirectory;
                send(channel, () -> channel.cd(quote(back)));
            } catch (final SftpException xe) {
                failure[0] = xe;
                channel.disconnect();
                final FileSystemException restore = new FileSystemException(
                        "vfs.provider.sftp/change-work-directory-back.error", workingDirectory, xe);
                if (lsEx != null) {
                    restore.addSuppressed(lsEx);
                }
                throw restore;
            }
        }
        if (lsEx != null) {
            throw lsEx;
        }
        return vector;
    }

    /**
     * Renames the file.
     */
    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        final SftpFileObject newSftpFileObject = (SftpFileObject) FileObjectUtils.getAbstractFileObject(newFile);
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            send(channel, () -> channel.rename(quote(relPath), quote(newSftpFileObject.relPath)));
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    @Override
    protected synchronized boolean doSetExecutable(final boolean executable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeExecutable(executable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    /**
     * Sets the last modified time of this file. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     *
     * @param modtime is modification time in milliseconds. SFTP protocol can send times with nanosecond precision but
     *            at the moment jsch send them with second precision.
     */
    @Override
    protected synchronized boolean doSetLastModifiedTime(final long modtime) throws Exception {
        if (attrs == null) {
            statSelf();
            if (attrs == null) {
                throw new FileNotFoundException(getName());
            }
        }
        final int newMTime = (int) (modtime / MOD_TIME_FACTOR);
        attrs.setACMODTIME(attrs.getATime(), newMTime);
        flushStat();
        return true;
    }

    @Override
    protected boolean doSetReadable(final boolean readable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeReadable(readable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    @Override
    protected synchronized boolean doSetWritable(final boolean writable, final boolean ownerOnly) throws Exception {
        final PosixPermissions permissions = getPermissions(false);
        final int newPermissions = permissions.makeWritable(writable, ownerOnly);
        if (newPermissions == permissions.getPermissions()) {
            return true;
        }

        attrs.setPERMISSIONS(newPermissions);
        flushStat();

        return true;
    }

    /** Sets the requested POSIX mode using the file system's channel. */
    public synchronized void setPosixMode(final int mode) throws IOException {
        if (mode < 0 || mode > 07777) {
            throw new IllegalArgumentException("Invalid POSIX mode");
        }
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            send(channel, () -> channel.chmod(mode, quote(relPath)));
            attrs = null;
        } catch (final SftpException error) {
            failure = error;
            throw new FileSystemException(error);
        } catch (final RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            release(channel, failure);
        }
    }

    private synchronized void flushStat() throws IOException, SftpException {
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        Throwable failure = null;
        try {
            send(channel, () -> channel.setStat(quote(relPath), attrs));
        } catch (final Throwable e) {
            failure = e;
            throw e;
        } finally {
            release(channel, failure);
        }
    }

    /**
     * Creates an input stream to read the file content from. The input stream is starting at the given position in the
     * file.
     */
    InputStream getInputStream(final long filePointer) throws IOException {
        final String path = getName().getPathDecoded();
        final ChannelSftp channel = getAbstractFileSystem().getChannel();
        // Using InputStream directly from the channel
        // is much faster than the memory method.
        try {
            final InputStream in = request(channel, wait -> channel.get(quote(path), null, filePointer));
            return new SftpInputStream(channel, in);
        } catch (final SftpException e) {
            release(channel, e);
            throw new FileSystemException(e);
        } catch (final Throwable e) {
            release(channel, e);
            throw e;
        }
    }

    /**
     * Returns the POSIX type permissions of the file.
     *
     * @param checkIds {@code true} if user and group ID should be checked (needed for some access rights checks)
     * @return A PosixPermission object
     * @throws Exception If an error occurs
     * @since 2.1
     */
    protected synchronized PosixPermissions getPermissions(final boolean checkIds) throws Exception {
        statSelf();
        boolean isInGroup = false;
        if (checkIds) {
            if (getAbstractFileSystem().isExecDisabledForCheck()) {
                // Exec is disabled, so we won't be able to ascertain the current user's UID and GID.
                // Return "always-true" permissions as a workaround, knowing that the SFTP server won't
                // let us perform unauthorized actions anyway.
                return new UserIsOwnerPosixPermissions(attrs.getPermissions());
            }

            final int[] groupsIds = getAbstractFileSystem().getGroupsIdsForCheck();
            // The probe of the groups can pass its deadline too, and then exec counts as disabled.
            if (getAbstractFileSystem().isExecDisabledForCheck()) {
                return new UserIsOwnerPosixPermissions(attrs.getPermissions());
            }
            for (final int groupId : groupsIds) {
                if (groupId == attrs.getGId()) {
                    isInGroup = true;
                    break;
                }
            }
        }
        final boolean isOwner = checkIds && attrs.getUId() == getAbstractFileSystem().getUIdForCheck();
        return new PosixPermissions(attrs.getPermissions(), isOwner, isInGroup);
    }

    /**
     * Called when the type or content of this file changes.
     */
    @Override
    protected void onChange() throws Exception {
        statSelf();
    }

    // Every call site gives its channel back here, with the exception that ended its call or null.
    @SuppressWarnings("resource") // does not allocate
    private void release(final ChannelSftp channel, final Throwable failure) {
        getAbstractFileSystem().releaseChannel(channel, failure);
    }

    // A stream that noted an exception never puts its channel back.
    @SuppressWarnings("resource") // does not allocate
    private void releaseNoted(final ChannelSftp channel, final Throwable failure) {
        if (failure == null) {
            release(channel, null);
        } else {
            getAbstractFileSystem().discardChannel(channel);
        }
    }

    /**
     * Sets attrs from listChildrenResolved
     */
    private synchronized void setStat(final SftpATTRS attrs) {
        this.attrs = attrs;
    }

    /**
     * Fetches file attributes from server.
     *
     * @throws IOException if an error occurs.
     */
    private synchronized void statSelf() throws Exception {
        // The fast window of this call opens here.
        final long start = System.nanoTime();
        final SftpFileSystem fileSystem = getAbstractFileSystem();
        ChannelSftp channel = fileSystem.getChannel();
        try {
            setStat(statOn(channel));
        } catch (final SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE && e.getCause() == null) {
                release(channel, e);
                attrs = null;
                LOG.debug("SFTP server returned no such file for stat of '" + relPath + "', so the file is missing");
                return;
            }
            // The channel that failed is not handed back. A reply of the server leaves the session in use, and any
            // other failure discards it.
            if (SftpFileSystem.isReplyOrSuccess(e)) {
                channel.disconnect();
            }
            release(channel, e);
            if (!Faults.isFast(start)) {
                // A slow failure gets no retry.
                throw e;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Stat of '" + relPath + "' failed, retrying it on a fresh SFTP channel", e);
            }
            channel = fileSystem.getChannel();
            try {
                setStat(statOn(channel));
            } catch (final SftpException retryEx) {
                final boolean connected = channel.isConnected();
                release(channel, retryEx);
                // A failure with a cause is a broken connection, whatever its id. Some servers reply with a failure
                // for a file that is not there, so on a channel that is still up that reply counts as missing too.
                if (retryEx.getCause() == null && (retryEx.id == ChannelSftp.SSH_FX_NO_SUCH_FILE
                        || retryEx.id == ChannelSftp.SSH_FX_FAILURE && connected)) {
                    attrs = null;
                    LOG.debug("Stat retry of '" + relPath + "' found no file, so the file is missing");
                    return;
                }
                throw retryEx;
            } catch (final Throwable retryError) {
                release(channel, retryError);
                throw retryError;
            }
            release(channel, null);
            return;
        } catch (final Throwable error) {
            release(channel, error);
            throw error;
        }
        release(channel, null);
    }

    // The few bytes of a probe file of the replace test.
    private static final byte[] PROBE_CONTENT = "probe".getBytes(StandardCharsets.US_ASCII);

    // One call of AtomicOps on one channel. A channel whose call ended with no reply of the server is discarded at
    // once and never used again, so the look and the probe run only on a channel whose calls all got a reply.
    private final class AtomicCall {

        // The phase of the public call, which an unconfirmed change reports, and its temp path or null. The temp
        // path is the path of the name in the file system.
        private final MovePhase phase;

        private String tempPath;

        private ChannelSftp channel;

        private final List<String> leftovers = new ArrayList<>();

        private boolean connectionLost;

        // The message of an interrupt seen before the call sent a change, and its values.
        private final String code;

        private final Object[] info;

        AtomicCall(final MovePhase phase, final String tempPath, final String code, final Object... info)
                throws FileSystemException {
            this.phase = phase;
            this.tempPath = tempPath;
            this.code = code;
            this.info = info;
            // A pending interrupt stops the call before it takes a channel.
            if (interrupted()) {
                throw notSent();
            }
            try {
                channel = getAbstractFileSystem().getChannel();
            } catch (final FileSystemException e) {
                throw e;
            } catch (final IOException e) {
                throw new FileSystemException(e);
            }
        }

        boolean isAnnounced(final String extension) {
            return SftpFileSystem.ANNOUNCED.equals(channel.getExtension(extension));
        }

        void discard() {
            if (channel != null) {
                getAbstractFileSystem().discardChannel(channel);
                channel = null;
            }
        }

        // The channel goes back only when every call on it got a reply of the server.
        void end() {
            if (channel != null) {
                release(channel, null);
                channel = null;
            }
        }

        // True when the thread was interrupted. No further command is sent then.
        boolean interrupted() {
            return Thread.currentThread().isInterrupted();
        }

        // The check before the first change of the call. After an interrupt nothing is sent, the channel is discarded
        // and the call ends with a plain error that keeps the interrupt flag.
        void beforeFirst() throws FileSystemException {
            if (interrupted()) {
                discard();
                throw notSent();
            }
        }

        // An interrupt seen before the call sent a change. Nothing changed, so the error is not unclear. Its fault is
        // the fault of an interrupt.
        FileSystemException notSent() {
            final InterruptedIOException cause = new InterruptedIOException(
                    "The thread was interrupted before the call sent a change.");
            final FileSystemException error = new FileSystemException(code, cause, info);
            error.setFault(Faults.of(cause));
            return error;
        }

        // Removes a name. A name that the server could not remove is a leftover. No such file means that it is gone.
        // After an interrupt nothing is sent: the name is a leftover and the channel is discarded.
        void remove(final String path) {
            if (channel == null) {
                leftovers.add(path);
                return;
            }
            if (interrupted()) {
                discard();
                connectionLost = true;
                leftovers.add(path);
                return;
            }
            try {
                send(channel, () -> channel.rm(quote(path)));
            } catch (final Exception e) {
                if (!isReply(e)) {
                    discard();
                    // A cancel in the cause chain stays on the thread, also when JSch cleared the flag.
                    keepInterrupt(e);
                    connectionLost = true;
                    leftovers.add(path);
                } else if (((SftpException) e).id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    leftovers.add(path);
                }
            }
        }

        AtomicResult result(final AtomicOutcome outcome, final String detail) {
            return new AtomicResult(outcome, detail, leftovers, connectionLost);
        }

        // A change whose outcome no reply of the server confirmed. The channel is discarded before the error leaves,
        // and no further call is made.
        UnclearResultException notKnown(final String step, final Throwable cause) {
            discard();
            keepInterrupt(cause);
            return new UnclearResultException("vfs.provider/atomic-not-known.error", cause, phase, tempPath, false,
                    step, getName().getFriendlyURI());
        }

        // The cause of an interrupt that the fork saw at the handoff to the next command.
        UnclearResultException interruptedBefore(final String step) {
            return notKnown(step, new InterruptedIOException("The thread was interrupted before the " + step + "."));
        }
    }

    // A cancel that the fork saw in the cause chain stays on the thread, also when JSch cleared the flag. A timeout of
    // a socket is no cancel.
    private static void keepInterrupt(final Throwable cause) {
        for (Throwable link = cause; link != null; link = link.getCause()) {
            if (link instanceof InterruptedException || link instanceof ClosedByInterruptException
                    || link instanceof InterruptedIOException && !(link instanceof SocketTimeoutException)) {
                Thread.currentThread().interrupt();
                return;
            }
            if (link.getCause() == link) {
                return;
            }
        }
    }

    // A status reply of the server: an SftpException with no cause, on a thread that was not interrupted.
    private static boolean isReply(final Throwable error) {
        return error instanceof SftpException && error.getCause() == null
                && !Thread.currentThread().isInterrupted();
    }

    private static boolean isNoSuchFile(final Throwable reply) {
        return ((SftpException) reply).id == ChannelSftp.SSH_FX_NO_SUCH_FILE;
    }

    private static String detail(final Throwable reply) {
        return reply instanceof SftpException ? "id " + ((SftpException) reply).id + " " + reply.getMessage()
                : String.valueOf(reply);
    }

    private static SftpFileObject sftpOf(final FileObject file) throws FileSystemException {
        return (SftpFileObject) FileObjectUtils.getAbstractFileObject(file);
    }

    // The path of a base name in the folder of a path, as JSch takes it.
    private static String sibling(final String path, final String baseName) {
        final int slash = path.lastIndexOf('/');
        return slash < 0 ? baseName : path.substring(0, slash + 1) + baseName;
    }

    // JSch sends the old path of a hard link as it is given, and the server resolves a relative path in the folder
    // where the session started. Every other call of JSch makes its path absolute in the folder of the channel, so the
    // link takes its old path from that folder too.
    private static String absolute(final ChannelSftp channel, final String path) throws SftpException {
        if (path.startsWith("/")) {
            return path;
        }
        final String folder = channel.pwd();
        return folder.endsWith("/") ? folder + path : folder + "/" + path;
    }

    // The folder of a name. A root has no parent, so it is its own folder.
    private static String folderKey(final FileName name) {
        final FileName parent = name.getParent();
        return (parent == null ? name : parent).getPath();
    }

    private static String pairKey(final FileName source, final FileName target) {
        return folderKey(source) + "\u0000" + folderKey(target);
    }

    // The file URI of a test point: no user part and no query.
    private static String pointKey(final FileName name) {
        final GenericFileName root = (GenericFileName) name.getRoot();
        final StringBuilder key = new StringBuilder(root.getScheme()).append("://").append(root.getHostName());
        if (root.getPort() != root.getDefaultPort()) {
            key.append(':').append(root.getPort());
        }
        return key.append(name.getPath()).toString();
    }

    private Answer linkAnswer(final String key) {
        final Boolean announced = getAbstractFileSystem().isHardlinkAnnounced();
        if (announced == null) {
            return Answer.NOT_KNOWN;
        }
        if (!announced) {
            return Answer.NO;
        }
        final LinkState state = getAbstractFileSystem().getLinkState(key);
        return state == LinkState.LINKS ? Answer.YES : state == LinkState.NO_LINK ? Answer.NO : Answer.NOT_KNOWN;
    }

    private Answer replaceAnswer(final String folder) {
        final Boolean announced = getAbstractFileSystem().isPosixRenameAnnounced();
        if (announced == null) {
            return Answer.NOT_KNOWN;
        }
        if (announced) {
            return Answer.YES;
        }
        final ReplaceState state = getAbstractFileSystem().getReplaceState(folder);
        return state == ReplaceState.REPLACES ? Answer.YES
                : state == ReplaceState.NO_REPLACE ? Answer.NO : Answer.NOT_KNOWN;
    }

    @Override
    protected EntryRelation entryRelation(final FileObject other) throws FileSystemException {
        if (!(other.getFileSystem() instanceof SftpFileSystem)) {
            return EntryRelation.DISTINCT;
        }
        if (getFileSystem() == other.getFileSystem()) {
            return workingDirectoryEntry(other, false, false, true);
        }
        final FileSystemOptions left = getFileSystem().getFileSystemOptions();
        final FileSystemOptions right = other.getFileSystem().getFileSystemOptions();
        final SftpFileSystemConfigBuilder config = SftpFileSystemConfigBuilder.getInstance();
        final DefaultFileSystemConfigBuilder auth = DefaultFileSystemConfigBuilder.getInstance();
        final boolean sameLogin = auth.getUserAuthenticator(left) == auth.getUserAuthenticator(right);

        final boolean sameRoute = Objects.equals(config.getProxyHost(left), config.getProxyHost(right))
                && config.getProxyPort(left) == config.getProxyPort(right)
                && config.getProxyType(left) == config.getProxyType(right)
                && Objects.equals(config.getProxyUser(left), config.getProxyUser(right))
                && config.getProxyUserAuthenticator(left) == config.getProxyUserAuthenticator(right)
                && config.getProxyOptions(left) == config.getProxyOptions(right)
                && Objects.equals(config.getProxyCommand(left), config.getProxyCommand(right))
                && config.getConfigRepository(left) == config.getConfigRepository(right)
                && config.isLoadOpenSSHConfig(left) == config.isLoadOpenSSHConfig(right);
        return workingDirectoryEntry(other, Boolean.TRUE.equals(config.getUserDirIsRoot(left)),
                Boolean.TRUE.equals(config.getUserDirIsRoot(right)), sameLogin && sameRoute
                && Objects.equals(config.getFileNameEncoding(left), config.getFileNameEncoding(right)));
    }

    @Override
    public AtomicCapabilities atomicCapabilities() {
        return new AtomicCapabilities(linkAnswer(folderKey(getName())), linkAnswer(pairKey(getName(), getName())),
                replaceAnswer(folderKey(getName())));
    }

    @Override
    public AtomicCapabilities atomicCapabilities(final FileObject target) {
        // A folder has no hard link.
        final Answer publish = getName().getType() == FileType.FOLDER ? Answer.NO
                : linkAnswer(pairKey(getName(), target.getName()));
        return new AtomicCapabilities(linkAnswer(folderKey(getName())), publish,
                replaceAnswer(folderKey(target.getName())));
    }

    // The link routine of the exclusive steps, from a source path to a target path, on the channel of the call.
    // It returns the outcome with the reply that decided it, or throws when a call ended with no reply.
    private AtomicResult link(final AtomicCall call, final String source, final String target, final String probe,
            final String key, final FileName targetName) throws FileSystemException {
        final SftpFileSystem fileSystem = getAbstractFileSystem();
        if (!call.isAnnounced(SftpFileSystem.HARDLINK) || fileSystem.getLinkState(key) == LinkState.NO_LINK) {
            return call.result(AtomicOutcome.UNSUPPORTED, null);
        }
        final String point = pointKey(targetName);
        final Exception refusal;
        try {
            send(call.channel, () -> call.channel.hardlink(quote(absolute(call.channel, source)), quote(target)));
            fileSystem.markLinks(key);
            return call.result(AtomicOutcome.DONE, null);
        } catch (final Exception e) {
            if (!isReply(e)) {
                throw call.notKnown("link", e);
            }
            if (isNoSuchFile(e)) {
                return noSuchFile(call, source, probe, key, targetName, e);
            }
            refusal = e;
        }
        TestPoints.hold("link.after-refusal", point);
        if (call.interrupted()) {
            throw call.interruptedBefore("look after a refused link");
        }
        Exception lookError = null;
        try {
            request(call.channel, wait -> call.channel.lstat(quote(target)));
        } catch (final Exception e) {
            lookError = e;
        }
        // The reply is read as it came, before a later interrupt.
        final boolean lookReplied = lookError != null && isReply(lookError);
        TestPoints.hold("link.after-look", point);
        if (lookError == null) {
            return call.result(AtomicOutcome.EXISTS, detail(refusal));
        }
        if (!lookReplied) {
            throw call.notKnown("look after a refused link", lookError);
        }
        if (!isNoSuchFile(lookError)) {
            return call.result(AtomicOutcome.REFUSED, detail(lookError));
        }
        // The holder released the name between the link and the look. A folder that linked once is never demoted.
        if (fileSystem.getLinkState(key) == LinkState.LINKS) {
            return call.result(AtomicOutcome.EXISTS, detail(refusal));
        }
        if (call.interrupted()) {
            throw call.interruptedBefore("probe link");
        }
        try {
            send(call.channel, () -> call.channel.hardlink(quote(absolute(call.channel, source)), quote(probe)));
        } catch (final Exception e) {
            if (!isReply(e)) {
                throw call.notKnown("probe link", e);
            }
            if (isNoSuchFile(e)) {
                return call.result(AtomicOutcome.GONE, detail(e));
            }
            fileSystem.markNoLink(key);
            if (fileSystem.firstWarning("link " + key)) {
                LOG.warn("The SFTP server refused a probe link from \"" + source + "\" to \"" + probe
                        + "\". This folder can not hold a hard link. Its exclusive step is not used while this file"
                        + " system lives. The server replied " + detail(e) + ".");
            }
            return call.result(AtomicOutcome.UNSUPPORTED, detail(e));
        }
        // The folder can link, so the first refusal was a busy name.
        fileSystem.markLinks(key);
        call.remove(probe);
        return call.result(AtomicOutcome.EXISTS, detail(refusal));
    }

    // The link answered no such file. That decides nothing, unless the folder never linked, the source is there, and
    // the probe link answers no such file too. Then the server does not link in this folder, as when it refuses the
    // probe, and the folder is marked so.
    private AtomicResult noSuchFile(final AtomicCall call, final String source, final String probe, final String key,
            final FileName targetName, final Exception linkError) throws FileSystemException {
        final SftpFileSystem fileSystem = getAbstractFileSystem();
        if (fileSystem.getLinkState(key) == LinkState.LINKS) {
            return call.result(AtomicOutcome.GONE, detail(linkError));
        }
        if (call.interrupted()) {
            throw call.interruptedBefore("look at the source of a link");
        }
        try {
            request(call.channel, wait -> call.channel.lstat(quote(source)));
        } catch (final Exception e) {
            if (!isReply(e)) {
                throw call.notKnown("look at the source of a link", e);
            }
            return call.result(AtomicOutcome.GONE, detail(linkError));
        }
        if (call.interrupted()) {
            throw call.interruptedBefore("probe link");
        }
        try {
            send(call.channel, () -> call.channel.hardlink(quote(absolute(call.channel, source)), quote(probe)));
        } catch (final Exception e) {
            if (!isReply(e)) {
                throw call.notKnown("probe link", e);
            }
            if (!isNoSuchFile(e)) {
                return call.result(AtomicOutcome.GONE, detail(linkError));
            }
            fileSystem.markNoLink(key);
            if (fileSystem.firstWarning("link " + key)) {
                LOG.warn("The SFTP server answered no such file for a link and for a probe link from \"" + source
                        + "\", which is there. The server does not link in the folder \"" + folderKey(targetName)
                        + "\", so the lock uses the weak rule there. Its exclusive step is not used while this file"
                        + " system lives. The server replied " + detail(e) + ".");
            }
            return call.result(AtomicOutcome.UNSUPPORTED, detail(e));
        }
        // The probe linked, so the folder can link and the first answer stays an error.
        fileSystem.markLinks(key);
        call.remove(probe);
        return call.result(AtomicOutcome.GONE, detail(linkError));
    }

    @Override
    public AtomicResult createExclusive(final byte[] content, final String scratchName, final String probeName)
            throws FileSystemException {
        return createExclusive(content, scratchName, probeName, null);
    }

    @Override
    public AtomicResult createExclusive(final byte[] content, final String scratchName, final String probeName,
            final Runnable beforeLink) throws FileSystemException {
        final String key = folderKey(getName());
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, null, "vfs.provider/create-file.error",
                getName().getFriendlyURI());
        try {
            // A server or a folder with no hard link gets no scratch file.
            if (!call.isAnnounced(SftpFileSystem.HARDLINK)
                    || getAbstractFileSystem().getLinkState(key) == LinkState.NO_LINK) {
                return call.result(AtomicOutcome.UNSUPPORTED, null);
            }
            call.beforeFirst();
            final String scratch = sibling(relPath, scratchName);
            call.tempPath = sibling(getName().getPath(), scratchName);
            try {
                send(call.channel, () -> call.channel.put(new ByteArrayInputStream(content), quote(scratch)));
            } catch (final Exception e) {
                if (!isReply(e)) {
                    throw call.notKnown("write of the scratch file", e);
                }
                // The server can refuse the write after it made the private scratch name, so that name is removed.
                // A name that is not there is clean.
                call.remove(scratch);
                return call.result(AtomicOutcome.REFUSED, detail(e));
            }
            if (beforeLink != null) {
                beforeLink.run();
            }
            if (call.interrupted()) {
                throw call.interruptedBefore("link of the scratch file");
            }
            final AtomicResult linked = link(call, scratch, relPath, sibling(relPath, probeName), key, getName());
            call.remove(scratch);
            return call.result(linked.getOutcome(), linked.getDetail());
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult linkExclusive(final FileObject target, final String probeName) throws FileSystemException {
        final AtomicCall call = new AtomicCall(MovePhase.RENAME, null, "vfs.provider/write.error",
                target.getName().getFriendlyURI());
        try {
            final String targetPath = sftpOf(target).relPath;
            call.beforeFirst();
            return link(call, relPath, targetPath, sibling(targetPath, probeName), pairKey(getName(), target.getName()),
                    target.getName());
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult publishExclusive(final FileObject target, final String probeName) throws FileSystemException {
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, getName().getPath(), "vfs.provider/write.error",
                target.getName().getFriendlyURI());
        try {
            final String targetPath = sftpOf(target).relPath;
            call.beforeFirst();
            final AtomicResult linked = link(call, relPath, targetPath, sibling(targetPath, probeName),
                    pairKey(getName(), target.getName()), target.getName());
            if (linked.getOutcome() != AtomicOutcome.DONE) {
                return linked;
            }
            TestPoints.hold("publish.after-link", pointKey(target.getName()));
            call.remove(relPath);
            return call.result(AtomicOutcome.DONE, linked.getDetail());
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult renameReplacing(final FileObject target, final FileObject probeA, final FileObject probeB)
            throws FileSystemException {
        final String targetPath = sftpOf(target).relPath;
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, getName().getPath(), "vfs.provider/rename.error",
                getName().getFriendlyURI(), target.getName().getFriendlyURI());
        try {
            // With the posix rename JSch sends every rename as that request, which replaces an existing target.
            final boolean stated = call.isAnnounced(SftpFileSystem.POSIX_RENAME);
            final Exception refusal;
            call.beforeFirst();
            try {
                send(call.channel, () -> call.channel.rename(quote(relPath), quote(targetPath)));
                return call.result(AtomicOutcome.DONE, null);
            } catch (final Exception e) {
                if (!isReply(e)) {
                    throw call.notKnown("rename", e);
                }
                if (isNoSuchFile(e)) {
                    return call.result(AtomicOutcome.GONE, detail(e));
                }
                refusal = e;
            }
            if (stated) {
                return call.result(AtomicOutcome.REFUSED, detail(refusal));
            }
            // The refusal was a reply, so nothing changed. After an interrupt the look is not sent.
            if (call.interrupted()) {
                call.discard();
                call.connectionLost = true;
                return call.result(AtomicOutcome.REFUSED, detail(refusal));
            }
            try {
                request(call.channel, wait -> call.channel.lstat(quote(targetPath)));
            } catch (final Exception e) {
                if (!isReply(e)) {
                    call.discard();
                    call.connectionLost = true;
                }
                // A target that is not there can not be the reason.
                return call.result(AtomicOutcome.REFUSED, detail(refusal));
            }
            final String folder = folderKey(target.getName());
            final ReplaceState state = getAbstractFileSystem().getReplaceState(folder);
            if (state == ReplaceState.REPLACES) {
                return call.result(AtomicOutcome.REFUSED, detail(refusal));
            }
            if (state == ReplaceState.NO_REPLACE) {
                return call.result(AtomicOutcome.UNSUPPORTED, detail(refusal));
            }
            if (probeA == null || probeB == null) {
                return call.result(AtomicOutcome.REFUSED, detail(refusal));
            }
            return replaceTest(call, detail(refusal), sftpOf(probeA).relPath, sftpOf(probeB).relPath, folder);
        } finally {
            call.end();
        }
    }

    // Does this server refuse a rename only because the target exists. It touches only the two probe files.
    private AtomicResult replaceTest(final AtomicCall call, final String refusal, final String probeA,
            final String probeB, final String folder) {
        for (final String probe : new String[] {probeA, probeB}) {
            if (call.interrupted()) {
                return testLost(call, refusal, probeA, probeB);
            }
            try {
                send(call.channel, () -> call.channel.put(new ByteArrayInputStream(PROBE_CONTENT), quote(probe)));
            } catch (final Exception e) {
                if (!isReply(e)) {
                    return testLost(call, refusal, probeA, probeB);
                }
                call.remove(probeA);
                if (probe == probeB) {
                    call.remove(probeB);
                }
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
        }
        if (call.interrupted()) {
            return testLost(call, refusal, probeA, probeB);
        }
        try {
            send(call.channel, () -> call.channel.rename(quote(probeA), quote(probeB)));
            call.remove(probeB);
            getAbstractFileSystem().markReplaces(folder);
            return call.result(AtomicOutcome.REFUSED, refusal);
        } catch (final Exception e) {
            if (!isReply(e)) {
                return testLost(call, refusal, probeA, probeB);
            }
        }
        // The control: the same rename with only the existing target taken away.
        if (call.interrupted()) {
            return testLost(call, refusal, probeA, probeB);
        }
        try {
            send(call.channel, () -> call.channel.rm(quote(probeB)));
        } catch (final Exception e) {
            if (!isReply(e)) {
                return testLost(call, refusal, probeA, probeB);
            }
            call.leftovers.add(probeB);
            call.remove(probeA);
            return call.result(AtomicOutcome.REFUSED, refusal);
        }
        if (call.interrupted()) {
            return testLost(call, refusal, probeA, probeB);
        }
        try {
            send(call.channel, () -> call.channel.rename(quote(probeA), quote(probeB)));
        } catch (final Exception e) {
            if (!isReply(e)) {
                return testLost(call, refusal, probeA, probeB);
            }
            call.remove(probeA);
            return call.result(AtomicOutcome.REFUSED, refusal);
        }
        call.remove(probeB);
        getAbstractFileSystem().markNoReplace(folder);
        if (getAbstractFileSystem().firstWarning("replace " + folder)) {
            LOG.warn("The SFTP server does not replace an existing name on a rename in the folder \"" + folder
                    + "\". The replace test on two probe files showed it.");
        }
        return call.result(AtomicOutcome.UNSUPPORTED, refusal);
    }

    // A transport error or an interrupt in the replace test decides nothing and remembers nothing.
    private static AtomicResult testLost(final AtomicCall call, final String refusal, final String probeA,
            final String probeB) {
        call.discard();
        call.connectionLost = true;
        call.leftovers.add(probeA);
        call.leftovers.add(probeB);
        return call.result(AtomicOutcome.REFUSED, refusal);
    }

    @Override
    public AtomicResult takeAway(final FileObject awayTarget) throws FileSystemException {
        final String awayPath = sftpOf(awayTarget).relPath;
        final AtomicCall call = new AtomicCall(MovePhase.RENAME, null, "vfs.provider/rename.error",
                getName().getFriendlyURI(), awayTarget.getName().getFriendlyURI());
        // The check discards the channel when it throws.
        call.beforeFirst();
        try {
            send(call.channel, () -> call.channel.rename(quote(relPath), quote(awayPath)));
            return call.result(AtomicOutcome.DONE, null);
        } catch (final Exception e) {
            if (!isReply(e)) {
                throw call.notKnown("take away", e);
            }
            return call.result(isNoSuchFile(e) ? AtomicOutcome.GONE : AtomicOutcome.REFUSED, detail(e));
        } finally {
            call.end();
        }
    }

    // One lstat of this name. Null when the server said that the name is not there. Some servers, FileZilla Server
    // among them, answer a name that is not there with a failure. So a failure is looked at once more on a fresh
    // channel, and a second failure on a channel that is still up counts as not there, as for the stat of this file.
    private SftpATTRS lookNow() throws FileSystemException {
        final long start = System.nanoTime();
        for (int look = 1; ; look++) {
            final ChannelSftp channel;
            try {
                channel = getAbstractFileSystem().getChannel();
            } catch (final FileSystemException e) {
                throw e;
            } catch (final IOException e) {
                throw new FileSystemException(e);
            }
            Throwable failure = null;
            try {
                return request(channel, wait -> channel.lstat(quote(relPath)));
            } catch (final Exception e) {
                failure = e;
                final boolean refused = isReply(e) && ((SftpException) e).id == ChannelSftp.SSH_FX_FAILURE;
                if (isReply(e) && isNoSuchFile(e) || refused && look > 1 && channel.isConnected()) {
                    return null;
                }
                if (refused && look == 1 && Faults.isFast(start)) {
                    // The channel that answered is not handed back.
                    channel.disconnect();
                    continue;
                }
                final FileSystemException error = new FileSystemException("vfs.provider/look.error", e,
                        getName().getFriendlyURI());
                error.setFault(isReply(e) ? Faults.of(e) : Fault.TRANSPORT);
                throw error;
            } finally {
                release(channel, failure);
            }
        }
    }

    // A look that has no answer for a reason of the server, the folder or the name. It is an application error.
    private static FileSystemException applicationError(final String code, final Object name) {
        final FileSystemException error = new FileSystemException(code, name);
        error.setFault(Fault.APPLICATION);
        return error;
    }

    @Override
    public boolean existsNow() throws FileSystemException {
        return lookNow() != null;
    }

    @Override
    public boolean folderExistsNow() throws FileSystemException {
        final SftpATTRS found = lookNow();
        if (found == null) {
            return false;
        }
        if (!found.isDir()) {
            throw applicationError("vfs.provider/look-not-folder.error", getName().getFriendlyURI());
        }
        return true;
    }

    @Override
    public StatNow statNow() throws FileSystemException {
        final SftpATTRS found = lookNow();
        return found == null ? StatNow.notThere()
                : StatNow.there(found.getSize(), (found.getMTime() & 0xFFFFFFFFL) * MOD_TIME_FACTOR, false);
    }

}
