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

import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;


import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.parser.MLSxEntryParser;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileNotFolderException;
import org.wso2.org.apache.commons.vfs2.FileNotFoundException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.RandomAccessContent;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpClient.Change;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpClient.Listing;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpClient.RenameReplies;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpClient.Reply;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystem.ReplaceState;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.Messages;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * An FTP file.
 */
public class FtpFileObject extends AbstractFileObject<FtpFileSystem> implements AtomicOps {

    private void checkStreamClose() throws IOException {
        if (TestPoints.enabled()) {
            final String key = getName().getFriendlyURI().replaceFirst("(://)[^/?#]*@", "$1").split("[?#]", 2)[0];
            TestPoints.hold("stream.close", key);
            final TestPoints.Fault fault = TestPoints.fault("stream.close", key);
            if (fault != null && "close-fails".equals(fault.token())) {
                throw new IOException("The stream close failed");
            }
        }
    }


    /**
     * An InputStream that monitors for end-of-file.
     */
    final class FtpInputStream extends MonitorInputStream {
        private final FtpClient client;
        private final NotingInputStream data;

        FtpInputStream(final FtpClient client, final InputStream in) {
            this(client, new NotingInputStream(in));
        }

        private FtpInputStream(final FtpClient client, final NotingInputStream data) {
            super(data);
            this.client = client;
            this.data = data;
        }

        FtpInputStream(final FtpClient client, final InputStream in, final int bufferSize) {
            this(client, new NotingInputStream(in), bufferSize);
        }

        private FtpInputStream(final FtpClient client, final NotingInputStream data, final int bufferSize) {
            super(data, bufferSize);
            this.client = client;
            this.data = data;
        }

        void abort() throws IOException {
            client.abort();
            close();
        }

        private boolean isTransferAbortedOkReplyCode() throws IOException {
            final List<Integer> transferAbortedOkReplyCodes = FtpFileSystemConfigBuilder
                .getInstance()
                .getTransferAbortedOkReplyCodes(getAbstractFileSystem().getFileSystemOptions());
            return transferAbortedOkReplyCodes != null && transferAbortedOkReplyCodes.contains(client.getReplyCode());
        }

        /**
         * Called after the stream has been closed. Only a good completion gives the client back.
         */
        @Override
        protected void onClose() throws IOException {
            if (data.failure() != null) {
                // The data stream failed, so the final reply is not read. The client is not in step any more.
                getAbstractFileSystem().discardClient(client);
                return;
            }
            final FileSystemException refused = run(client, c -> c.completePendingCommand()
                    || isTransferAbortedOkReplyCode() ? null
                    : refusal(c, "vfs.provider.ftp/finish-get-reply.error", true, getName()));
            if (refused != null) {
                getAbstractFileSystem().discardClient(client);
                throw refused;
            }
            getAbstractFileSystem().putClient(client);
        }
    }
    /**
     * An OutputStream that monitors for end-of-file.
     */
    private final class FtpOutputStream extends MonitorOutputStream {
        private final FtpClient client;
        private final NotingOutputStream data;

        FtpOutputStream(final FtpClient client, final OutputStream outstr) {
            this(client, new NotingOutputStream(outstr));
        }

        private FtpOutputStream(final FtpClient client, final NotingOutputStream data) {
            super(data);
            this.client = client;
            this.data = data;
        }

        /**
         * Called after this stream is closed. Only a good completion gives the client back.
         */
        @Override
        protected void onClose() throws IOException {
            if (data.failure() != null) {
                // The data stream failed, so the final reply is not read. The client is not in step any more.
                getAbstractFileSystem().discardClient(client);
                return;
            }
            final FileSystemException refused = run(client, c -> c.completePendingCommand() ? null
                    : refusal(c, "vfs.provider.ftp/finish-put-reply.error", false, getName()));
            if (refused != null) {
                getAbstractFileSystem().discardClient(client);
                throw refused;
            }
            getAbstractFileSystem().putClient(client);
        }
    }

    /**
     * Keeps the first exception of the data stream of a read.
     */
    private final class NotingInputStream extends FilterInputStream {

        private volatile IOException failure;

        NotingInputStream(final InputStream in) {
            super(in);
        }

        IOException failure() {
            return failure;
        }

        private IOException note(final IOException e) {
            if (failure == null) {
                failure = e;
            }
            return e;
        }

        @Override
        public int read() throws IOException {
            try {
                return in.read();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            try {
                return in.read(b, off, len);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public long skip(final long n) throws IOException {
            try {
                return in.skip(n);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public int available() throws IOException {
            try {
                return in.available();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                try {
                    checkStreamClose();
                } finally {
                    in.close();
                }
            } catch (final IOException e) {
                throw note(e);
            }
        }
    }

    /**
     * Keeps the first exception of the data stream of a write.
     */
    private final class NotingOutputStream extends FilterOutputStream {

        private volatile IOException failure;

        NotingOutputStream(final OutputStream out) {
            super(out);
        }

        IOException failure() {
            return failure;
        }

        private IOException note(final IOException e) {
            if (failure == null) {
                failure = e;
            }
            return e;
        }

        @Override
        public void write(final int b) throws IOException {
            try {
                out.write(b);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            try {
                out.write(b, off, len);
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                out.flush();
            } catch (final IOException e) {
                throw note(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                try {
                    checkStreamClose();
                } finally {
                    out.close();
                }
            } catch (final IOException e) {
                throw note(e);
            }
        }
    }

    /**
     * One call on a client that this object took.
     *
     * @param <T> the result.
     */
    private interface FtpCall<T> {
        T run(FtpClient client) throws IOException;
    }

    private static final long DEFAULT_TIMESTAMP = 0L;

    private static final FTPFile UNKNOWN = new FTPFile();

    private static final Log log = LogFactory.getLog(FtpFileObject.class);
    private volatile boolean mdtmSet;
    private final String relPath;
    // Cached info
    private volatile FTPFile ftpFile;
    // Created once and never null. Empty means that the folder was not listed yet, or that it has no entry.
    private final ConcurrentSkipListMap<String, FTPFile> childMap = new ConcurrentSkipListMap<>();

    private volatile FileObject linkDestination;

    private final AtomicBoolean inRefresh = new AtomicBoolean();
    private boolean refreshParent;

    /**
     * Constructs a new instance.
     *
     * @param fileName the file name.
     * @param fileSystem the file system.
     * @param rootName the root name.
     * @throws FileSystemException if an file system error occurs.
     */
    protected FtpFileObject(final AbstractFileName fileName, final FtpFileSystem fileSystem, final FileName rootName)
            throws FileSystemException {
        super(fileName, fileSystem);
        final String relPath = UriParser.decode(rootName.getRelativeName(fileName));
        if (".".equals(relPath)) {
            // do not use the "." as path against the ftp-server
            // e.g. the uu.net ftp-server do a recursive listing then
            // this.relPath = UriParser.decode(rootName.getPath());
            // this.relPath = ".";
            this.relPath = null;
        } else {
            this.relPath = relPath;
        }
    }

    /**
     * Attaches this file object to its file resource.
     */
    @Override
    protected void doAttach() throws IOException {
        if (refreshParent) {
            setFTPFile(true);
            refreshParent = false;
        }
    }

    /**
     * Creates this file as a folder.
     */
    @Override
    protected void doCreateFolder() throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final FileSystemException refused = run(client, c -> c.makeDirectory(relPath) ? null
                : refusal(c, "vfs.provider.ftp/create-folder-reply.error", false, getName()));
        if (refused != null && refused.getFault() != Fault.TRANSPORT) {
            // A folder that can be entered counts as made, also when the server hides it from a listing.
            final Change change = run(client, c -> c.enterAndBack(relPath));
            if (change.isEntered()) {
                if (change.isBackRefused()) {
                    getAbstractFileSystem().discardClient(client);
                } else {
                    getAbstractFileSystem().putClient(client);
                }
                return;
            }
        }
        giveBack(client, refused);
        if (refused != null) {
            throw refused;
        }
    }

    /**
     * Deletes the file.
     */
    @Override
    protected void doDelete() throws Exception {
        synchronized (getFileSystem()) {
            final FtpClient ftpClient = getAbstractFileSystem().getClient();
            FTPFile entry = ftpFile;
            if (entry == null || entry == UNKNOWN) {
                // A listing on another thread can clear the info of this entry after the delete chose it. Then the
                // entry is looked up again here, under the same lock, before any command is sent.
                entry = lookUpForDelete(ftpClient);
            }
            final boolean folder = entry.isDirectory();
            final FileSystemException refused = run(ftpClient,
                    c -> (folder ? c.removeDirectory(relPath) : c.deleteFile(relPath)) ? null
                            : refusal(c, "vfs.provider.ftp/delete-file-reply.error", false, getName()));
            giveBack(ftpClient, refused);
            if (refused != null) {
                throw refused;
            }
            ftpFile = null;
            childMap.clear();
        }
    }

    // The entry of this file in a new listing of its folder. A link is the entry itself, so it gets DELE. A listing
    // that is refused, or that does not show the name, can not prove that the delete is done, so it is an error.
    // The client goes back or is discarded by the fault of that error.
    private FTPFile lookUpForDelete(final FtpClient client) throws IOException {
        final String name = UriParser.decode(getName().getBaseName());
        final String folderPath = UriParser.decode(getAbstractFileSystem().getRootName()
                .getRelativeName(getName().getParent()));
        final FTPFile[] entries = run(client, c -> c.listFiles(".".equals(folderPath) ? null : folderPath));
        final FileSystemException error;
        if (entries == null) {
            error = run(client, c -> refusal(c, "vfs.provider.ftp/delete-lookup-reply.error", true, getName()));
        } else {
            for (final FTPFile entry : entries) {
                if (entry != null && name.equals(entry.getName())) {
                    return entry;
                }
            }
            error = new FileSystemException("vfs.provider.ftp/delete-not-listed.error", getName());
            error.setFault(Fault.MISSING);
        }
        giveBack(client, error);
        throw error;
    }

    /**
     * Detaches this file object from its file resource.
     */
    @Override
    protected void doDetach() {
        synchronized (getFileSystem()) {
            ftpFile = null;
            childMap.clear();
            mdtmSet = false;
        }
    }

    /**
     * Fetches the children of this file with the checked listing, if not already cached. A listing that was refused or
     * cut throws, and never gives an empty or a short folder.
     *
     * @return false when the folder can not be entered.
     */
    private boolean doGetChildren() throws IOException {
        if (!childMap.isEmpty()) {
            return true;
        }

        final String path = ftpFile != null && ftpFile.isSymbolicLink()
                ? getFileSystem().getFileSystemManager().resolveName(getParent().getName(), encodeServerPath(ftpFile.getLink()))
                        .getPathDecoded()
                : relPath;
        final FTPFile[] tmpChildren = listEntries(path);
        if (tmpChildren == null) {
            return false;
        }
        if (!ArrayUtils.isEmpty(tmpChildren)) {
            // Remove '.' and '..' elements
            for (int i = 0; i < tmpChildren.length; i++) {
                final FTPFile child = tmpChildren[i];
                if (child == null) {
                    if (log.isDebugEnabled()) {
                        log.debug(Messages.getString("vfs.provider.ftp/invalid-directory-entry.debug",
                                Integer.valueOf(i), relPath));
                    }
                    continue;
                }
                if (!".".equals(child.getName()) && !"..".equals(child.getName())) {
                    childMap.put(child.getName(), child);
                }
            }
        }
        return true;
    }

    private FTPFile[] listEntries(final String path) throws IOException {
        FtpClient client = getAbstractFileSystem().getClient();
        Listing listing = run(client, c -> checkedListing(c, path));
        if (needsPlainListing(listing)) {
            // The client is in another folder now, so it is not used again. The plain listing runs on a fresh client.
            getAbstractFileSystem().discardClient(client);
            client = getAbstractFileSystem().getClient();
            listing = run(client, c -> c.listChecked(path, false));
        }
        final FileSystemException failed = listing.getFailure() == null ? null
                : listFailure(path, listing.getFailure());
        if (listing.isBackRefused()) {
            getAbstractFileSystem().discardClient(client);
        } else {
            giveBack(client, failed);
        }
        if (failed != null) {
            throw failed;
        }
        return listing.getEntries();
    }

    @Override
    public FileObject[] listConfirmedFolder(final FileObject folder, final Map<FileName, Long> listedSizes)
            throws FileSystemException {
        final FTPFile[] entries;
        try {
            entries = listEntries(relPath);
        } catch (final FileSystemException error) {
            throw error;
        } catch (final IOException error) {
            throw new FileSystemException("vfs.provider/list-children.error", error, getName());
        }
        if (entries == null) {
            throw new FileSystemException("vfs.provider.ftp/list-not-entered.error", getName());
        }
        final List<FileObject> children = new ArrayList<>();
        for (final FTPFile entry : entries) {
            if (entry == null || ".".equals(entry.getName()) || "..".equals(entry.getName())) {
                continue;
            }
            final FileObject child = folder.resolveFile("./" + encodeServerPath(entry.getName()), NameScope.CHILD);
            if (!entry.isSymbolicLink()) {
                listedSizes.put(child.getName(), entry.isFile() ? entry.getSize() : -1L);
            }
            children.add(child);
        }
        return children.toArray(FileObject.EMPTY_ARRAY);
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected long doGetContentSize() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                return 0;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return ftpFile.getSize();
                }
                return linkDest.getContent().getSize();
            }
            return ftpFile.getSize();
        }
    }

    /**
     * Creates an input stream to read the file content from.
     */
    @Override
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final InputStream inputStream = run(client, c -> c.retrieveFileStream(relPath, 0));
        // VFS-210
        if (inputStream == null) {
            final FileSystemException refused = run(client,
                    c -> notRead(c, getName().toString(), "vfs.provider.ftp/read-file-reply.error"));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpInputStream(client, inputStream, bufferSize);
    }

    /**
     * Gets the last modified time on an FTP file
     *
     * @see AbstractFileObject#doGetLastModifiedTime()
     */
    @Override
    protected long doGetLastModifiedTime() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                return DEFAULT_TIMESTAMP;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return getTimestampMillis();
                }
                return linkDest.getContent().getLastModifiedTime();
            }
            return getTimestampMillis();
        }
    }

    /**
     * Creates an output stream to write the file content to.
     */
    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        final FtpClient client = getAbstractFileSystem().getClient();
        final OutputStream out = run(client,
                c -> bAppend ? c.appendFileStream(relPath) : c.storeFileStream(relPath));
        if (out == null) {
            final FileSystemException refused = run(client,
                    c -> refusal(c, "vfs.provider.ftp/output-error.debug", false, getName()));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpOutputStream(client, out);
    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        return new FtpRandomAccessContent(this, mode);
    }

    /**
     * Determines the type of the file, returns null if the file does not exist.
     */
    @Override
    protected FileType doGetType() throws Exception {
        // VFS-210
        synchronized (getFileSystem()) {
            if (ftpFile == null) {
                setFTPFile(false);
            }

            if (ftpFile == UNKNOWN) {
                return FileType.IMAGINARY;
            }
            if (ftpFile.isDirectory()) {
                return FileType.FOLDER;
            }
            if (ftpFile.isFile()) {
                return FileType.FILE;
            }
            if (ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: We need to check if the symbolic link links back to the symbolic link itself
                if (isCircular(linkDest)) {
                    // If the symbolic link links back to itself, treat it as an imaginary file to prevent following
                    // this link. If the user tries to access the link as a file or directory, the user will end up with
                    // a FileSystemException warning that the file cannot be accessed. This is to prevent the infinite
                    // call back to doGetType() to prevent the StackOverFlow
                    return FileType.IMAGINARY;
                }
                return linkDest.getType();

            }
        }
        throw new FileSystemException("vfs.provider.ftp/get-type.error", getName());
    }

    /**
     * Lists the children of the file.
     */
    @Override
    protected String[] doListChildren() throws Exception {
        // List the children of this file. A folder that can not be entered can not be listed.
        if (!doGetChildren()) {
            throw new FileSystemException("vfs.provider.ftp/list-not-entered.error", getName());
        }

        // VFS-210. The map is never null, so an empty map is the folder with no entry.
        if (childMap.isEmpty()) {
            return new String[0];
        }

        return childMap.values().stream().filter(Objects::nonNull).map(FTPFile::getName)
                .map(FtpFileObject::encodeServerPath).toArray(String[]::new);
    }

    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {
        synchronized (getFileSystem()) {
            if (ftpFile != null && ftpFile.isSymbolicLink()) {
                final FileObject linkDest = getLinkDestination();
                // VFS-437: Try to avoid a recursion loop.
                if (isCircular(linkDest)) {
                    return null;
                }
                return linkDest.getChildren();
            }
        }
        return null;
    }

    /**
     * Renames the file
     */
    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        synchronized (getFileSystem()) {
            final String newName = ((FtpFileObject) FileObjectUtils.getAbstractFileObject(newFile)).getRelPath();
            final FtpClient ftpClient = getAbstractFileSystem().getClient();
            final FileSystemException refused = run(ftpClient, c -> c.rename(relPath, newName) ? null
                    : refusal(c, "vfs.provider.ftp/rename-file-reply.error", false, getName().toString(), newFile));
            giveBack(ftpClient, refused);
            if (refused != null) {
                throw refused;
            }
            ftpFile = null;
            childMap.clear();
        }
    }

    /**
     * Called by child file objects, to locate their FTP file info.
     *
     * @param name the file name in its native form i.e. without URI stuff (%nn)
     * @param flush recreate children cache
     */
    private FTPFile getChildFile(final String name, final boolean flush) throws IOException {
        /*
         * If we should flush cached children, clear our children map unless we're in the middle of a refresh in which
         * case we've just recently refreshed our children. No need to do it again when our children are refresh()ed,
         * calling getChildFile() for themselves from within getInfo(). See getChildren().
         */
        if (flush && !inRefresh.get()) {
            childMap.clear();
        }

        // List the children of this file
        doGetChildren();

        // Look for the requested child
        return childMap.get(name);
    }

    /**
     * Returns the file's list of children.
     *
     * @return The list of children
     * @throws FileSystemException If there was a problem listing children
     * @see AbstractFileObject#getChildren()
     * @since 2.0
     */
    @Override
    public FileObject[] getChildren() throws FileSystemException {
        try {
            if (doGetType() != FileType.FOLDER) {
                throw new FileNotFolderException(getName());
            }
        } catch (final Exception ex) {
            throw new FileNotFolderException(getName(), ex);
        }

        try {
            /*
             * Wrap our parent implementation, noting that we're refreshing so that we don't refresh() ourselves and
             * each of our parents for each child. Note that refresh() will list children. Meaning, if this file
             * has C children, P parents, there will be (C * P) listings made with (C * (P + 1)) refreshes, when there
             * should really only be 1 listing and C refreshes.
             */
            inRefresh.set(true);
            return super.getChildren();
        } finally {
            inRefresh.set(false);
        }
    }

    FtpInputStream getInputStream(final long filePointer) throws IOException {
        final FtpClient client = getAbstractFileSystem().getClient();
        final InputStream instr = run(client, c -> c.retrieveFileStream(relPath, filePointer));
        // VFS-210
        if (instr == null) {
            final FileSystemException refused = run(client,
                    c -> notRead(c, getName().getFriendlyURI(), "vfs.provider.ftp/input-error.debug"));
            giveBack(client, refused);
            throw refused;
        }
        return new FtpInputStream(client, instr);
    }

    private static String encodeServerPath(final String path) {
        return UriParser.encode(path, new char[] {'?'});
    }

    private FileObject getLinkDestination() throws FileSystemException {
        if (linkDestination == null) {
            final String path;
            synchronized (getFileSystem()) {
                path = ftpFile == null ? null : ftpFile.getLink();
            }
            final FileName parent = getName().getParent();
            final FileName relativeTo = parent == null ? getName() : parent;
            final FileName linkDestinationName = getFileSystem().getFileSystemManager().resolveName(relativeTo, encodeServerPath(path));
            linkDestination = getFileSystem().resolveFile(linkDestinationName);
        }
        return linkDestination;
    }

    String getRelPath() {
        return relPath;
    }

    /**
     * ftpFile is not null.
     */
    @SuppressWarnings("resource") // abstractFileSystem is managed in the superclass.
    private long getTimestampMillis() throws IOException {
        final FtpFileSystem abstractFileSystem = getAbstractFileSystem();
        final Boolean mdtmLastModifiedTime = FtpFileSystemConfigBuilder.getInstance()
            .getMdtmLastModifiedTime(abstractFileSystem.getFileSystemOptions());
        if (mdtmLastModifiedTime != null && mdtmLastModifiedTime.booleanValue() && !mdtmSet) {
            final FtpClient client = abstractFileSystem.getClient();
            final Instant mdtmInstant = run(client, this::mdtmTime);
            abstractFileSystem.putClient(client);
            if (mdtmInstant != null) {
                final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
                final long epochMilli = mdtmInstant.toEpochMilli();
                calendar.setTimeInMillis(epochMilli);
                ftpFile.setTimestamp(calendar);
                mdtmSet = true;
            }
        }
        final Calendar timestamp = ftpFile.getTimestamp();
        return timestamp == null ? DEFAULT_TIMESTAMP : timestamp.getTimeInMillis();
    }

    // The time of MDTM, or null when the server does not list MDTM or gives no reply 213. Then the time of the
    // listing is used, as when the server has no MDTM. A transport reply is an IOException of the wrapper.
    private Instant mdtmTime(final FtpClient client) throws IOException {
        if (!client.hasFeature("MDTM")) {
            return null;
        }
        final Instant time = client.mdtmInstant(relPath);
        if (time == null && log.isDebugEnabled()) {
            final int reply = client.getReplyCode();
            final String text = client.getReplyString();
            log.debug("MDTM gave no time for \"" + getName().getFriendlyURI() + "\". The server replied \""
                    + (text == null ? String.valueOf(reply) : text.trim()) + "\" (fault " + faultOf(reply, true)
                    + "). The time of the listing is used.");
        }
        return time;
    }

    /**
     * This is an over simplistic implementation for VFS-437.
     */
    private boolean isCircular(final FileObject linkDest) throws FileSystemException {
        return linkDest.getName().getPathDecoded().equals(getName().getPathDecoded());
    }

    /**
     * Called when the type or content of this file changes.
     */
    @Override
    protected void onChange() throws IOException {
        childMap.clear();

        if (getType().equals(FileType.IMAGINARY)) {
            // file is deleted, avoid server lookup
            synchronized (getFileSystem()) {
                ftpFile = UNKNOWN;
            }
            return;
        }

        setFTPFile(true);
    }

    /**
     * Called when the children of this file change.
     */
    @Override
    protected void onChildrenChanged(final FileName child, final FileType newType) {
        if (newType.equals(FileType.IMAGINARY)) {

            if (!(childMap.isEmpty())) {

                try {
                    if (childMap.containsKey(UriParser.decode(child.getBaseName()))) {
                        childMap.remove(UriParser.decode(child.getBaseName()));
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Map does not contain the " + child.getBaseName() + "in the map");
                        }
                    }
                } catch (final FileSystemException e) {
                    throw new RuntimeException(e.getMessage());
                }

            } else {
                if (log.isDebugEnabled()) {
                    log.debug("EMPTY_FTP_FILE_MAP returned empty collection.");
                }
            }
        } else {
            // if child was added we have to rescan the children
            // TODO - get rid of this
            childMap.clear();
        }
    }

    // Runs one call on a client that this object took. An IOException of the call leaves the client out of step, so
    // the client is discarded. Any other error gives it back, as before.
    private <T> T run(final FtpClient client, final FtpCall<T> call) throws IOException {
        try {
            return call.run(client);
        } catch (final IOException e) {
            getAbstractFileSystem().discardClient(client);
            throw e;
        } catch (final RuntimeException | Error e) {
            getAbstractFileSystem().putClient(client);
            throw e;
        }
    }

    // Gives the client back after a refusal, or discards it when the reply is a transport reply. The fault was read
    // while the caller still held the client. A refusal that is not a transport reply leaves the client in step.
    private void giveBack(final FtpClient client, final FileSystemException refused) {
        if (refused != null && refused.getFault() == Fault.TRANSPORT) {
            getAbstractFileSystem().discardClient(client);
        } else {
            getAbstractFileSystem().putClient(client);
        }
    }

    // The error of a command that the server refused. The reply is read while the caller still holds the client, and
    // it is the last value of the message.
    private static FileSystemException refusal(final FtpClient client, final String code, final boolean read,
            final Object... info) throws IOException {
        final int reply = client.getReplyCode();
        final String text = client.getReplyString();
        final Object[] values = Arrays.copyOf(info, info.length + 1);
        values[info.length] = text == null ? String.valueOf(reply) : text.trim();
        final FileSystemException error = new FileSystemException(code, values);
        error.setFault(faultOf(reply, read));
        return error;
    }

    // A read that got no stream. Only a reply 550 is the file not found, and that is a hint, not a proof.
    private static FileSystemException notRead(final FtpClient client, final String name, final String code)
            throws IOException {
        if (client.getReplyCode() == FTPReply.FILE_UNAVAILABLE) {
            final FileSystemException error = new FileNotFoundException(name);
            error.setFault(Fault.MISSING);
            return error;
        }
        return refusal(client, code, true, name);
    }

    /**
     * Gives the fault of a reply by the FTP table of the fault classes. A reply 550 to a read, a size or a time
     * request is the hint that the name is not there. A reply 550 to a call that changes the server is a refusal.
     *
     * @param reply the reply code.
     * @param read true for a call that only reads.
     * @return the fault.
     */
    static Fault faultOf(final int reply, final boolean read) {
        switch (reply) {
        case FTPReply.SERVICE_NOT_AVAILABLE:
        case FTPReply.CANNOT_OPEN_DATA_CONNECTION:
        case FTPReply.TRANSFER_ABORTED:
            return Fault.TRANSPORT;
        case FTPReply.FILE_ACTION_NOT_TAKEN:
            return Fault.BUSY;
        case FTPReply.FILE_UNAVAILABLE:
            return read ? Fault.MISSING : Fault.APPLICATION;
        default:
            return Fault.APPLICATION;
        }
    }

    /**
     * @throws FileSystemException if an error occurs.
     */
    // The few bytes of a probe file of the replace test.
    private static final byte[] PROBE_CONTENT = "probe".getBytes(StandardCharsets.US_ASCII);

    // The listing with the guard of the option -a. The option is trusted only after one listing with it gave an entry
    // on this file system. Until then an empty listing with the option is followed by one plain listing. When the
    // change back of that empty listing was refused, the caller runs the plain listing on a fresh client.
    private Listing checkedListing(final FtpClient client, final String path) throws IOException {
        final Listing hidden = client.listChecked(path, true);
        final FTPFile[] entries = hidden.getEntries();
        if (entries == null) {
            return hidden;
        }
        if (countOf(entries) > 0) {
            if (!hidden.isBackRefused()) {
                getAbstractFileSystem().markHiddenOptionSeen();
            }
            return hidden;
        }
        if (hidden.isBackRefused() || getAbstractFileSystem().isHiddenOptionSeen()) {
            return hidden;
        }
        return client.listChecked(path, false);
    }

    // An empty listing with the option -a whose change back was refused, while the option is not trusted. It does not
    // show that a visible name is missing. The plain listing is still needed.
    private boolean needsPlainListing(final Listing listing) {
        final FTPFile[] entries = listing.getEntries();
        return entries != null && countOf(entries) == 0 && listing.isBackRefused()
                && !getAbstractFileSystem().isHiddenOptionSeen();
    }

    // The names of a listing. The entries "." and ".." are not names in the folder.
    private static int countOf(final FTPFile[] entries) {
        int count = 0;
        for (final FTPFile entry : entries) {
            if (entry != null && !".".equals(entry.getName()) && !"..".equals(entry.getName())) {
                count++;
            }
        }
        return count;
    }

    private FileSystemException listFailure(final String path, final Reply reply) {
        final FileSystemException error = new FileSystemException("vfs.provider.ftp/list-reply.error",
                path == null ? "." : path, reply.getText());
        error.setFault(faultOf(reply.getCode(), true));
        return error;
    }

    // One call of AtomicOps. A client whose call ended with an I/O error is discarded at once, and the call makes no
    // further command on any client. A client whose change back was refused is not used again, and the next command
    // of the call gets a fresh client.
    private final class AtomicCall {

        private final FtpFileSystem fileSystem = getAbstractFileSystem();

        private FtpClient client;

        private boolean backRefused;

        private boolean lost;

        private final List<String> leftovers = new ArrayList<>();

        private boolean connectionLost;
        private Fault renameFault;

        FtpClient client() throws FileSystemException {
            if (client != null && backRefused) {
                fileSystem.discardClient(client);
                client = null;
                backRefused = false;
            }
            if (client == null) {
                client = fileSystem.getClient();
            }
            return client;
        }

        <T> T run(final FtpCall<T> call) throws IOException {
            final FtpClient current = client();
            try {
                return call.run(current);
            } catch (final IOException e) {
                fileSystem.discardClient(current);
                client = null;
                lost = true;
                throw e;
            }
        }

        void noteBack(final boolean refused) {
            backRefused |= refused;
        }

        void discard() {
            if (client != null) {
                fileSystem.discardClient(client);
                client = null;
            }
            lost = true;
        }

        void end() {
            if (client != null) {
                if (backRefused) {
                    fileSystem.discardClient(client);
                } else {
                    fileSystem.putClient(client);
                }
                client = null;
            }
        }

        // Sends DELE. False when the server refused it with a reply. A transport reply retires the client and is an
        // I/O error.
        boolean delete(final String path) throws IOException {
            if (run(c -> c.deleteFile(path))) {
                return true;
            }
            final Reply reply = run(c -> new Reply(c.getReplyCode(), String.valueOf(c.getReplyString()).trim()));
            if (isTransport(reply)) {
                discard();
                final FileSystemException error = new FileSystemException("vfs.provider.ftp/delete-file-reply.error",
                        path, textOf(reply));
                error.setFault(Fault.TRANSPORT);
                throw error;
            }
            return false;
        }

        // A transport reply to RNFR or RNTO in a rename of two steps. The client is retired. The reply means that the
        // command did not run, so the replies decide the outcome. The test of the replace answer decides nothing.
        void checkRename(final RenameReplies replies, final String from, final String to) throws IOException {
            final Reply reply = isTransport(replies.getFrom()) ? replies.getFrom()
                    : isTransport(replies.getTo()) ? replies.getTo() : null;
            if (reply != null) {
                discard();
                final FileSystemException error = new FileSystemException("vfs.provider.ftp/rename-file-reply.error",
                        from, to, textOf(reply));
                error.setFault(Fault.TRANSPORT);
                throw error;
            }
        }

        // Removes a name. A name that the server did not remove is a leftover.
        void remove(final String path) {
            if (lost) {
                leftovers.add(path);
                return;
            }
            try {
                if (!delete(path)) {
                    leftovers.add(path);
                }
            } catch (final IOException e) {
                connectionLost = true;
                leftovers.add(path);
            }
        }

        void recordRenameReply(final RenameReplies replies) {
            final Reply reply = replies.getTo() == null ? replies.getFrom() : replies.getTo();
            if (!FTPReply.isPositiveCompletion(reply.getCode())) {
                renameFault = faultOf(reply.getCode(), false);
            }
        }

        AtomicResult result(final AtomicOutcome outcome, final String detail) {
            return new AtomicResult(outcome, detail, leftovers, connectionLost,
                    outcome == AtomicOutcome.REFUSED ? renameFault : null);
        }

        // A transport reply to the rename of the call. The command did not run, so nothing changed. The client is
        // retired and the call sends no further command.
        AtomicResult refusedByTransport(final Reply reply) {
            discard();
            connectionLost = true;
            return result(AtomicOutcome.REFUSED, textOf(reply));
        }

        // The rename ended with an I/O error. The client is discarded, and the rename may have worked.
        UnclearResultException notKnown(final String step, final IOException cause, final MovePhase phase,
                final String tempPath) {
            discard();
            return new UnclearResultException("vfs.provider/atomic-not-known.error", cause, phase, tempPath, false,
                    step, getName().getFriendlyURI());
        }
    }

    // 421, 425 and 426 are transport replies.
    private static boolean isTransport(final Reply reply) {
        return reply != null && faultOf(reply.getCode(), false) == Fault.TRANSPORT;
    }

    private static FtpFileObject ftpOf(final FileObject file) throws FileSystemException {
        return (FtpFileObject) FileObjectUtils.getAbstractFileObject(file);
    }

    private static String parentOf(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash <= 0 ? null : path.substring(0, slash);
    }

    private static String baseOf(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    // The folder of a name. A root has no parent, so it is its own folder.
    private static String folderKey(final FileName name) {
        final FileName parent = name.getParent();
        return (parent == null ? name : parent).getPath();
    }

    private static String textOf(final Reply reply) {
        return reply == null ? null : reply.getText();
    }

    private Answer replaceAnswer(final String folder) {
        final ReplaceState state = getAbstractFileSystem().getReplaceState(folder);
        return state == ReplaceState.REPLACES ? Answer.YES
                : state == ReplaceState.NO_REPLACE ? Answer.NO : Answer.NOT_KNOWN;
    }

    @Override
    protected EntryRelation entryRelation(final FileObject other) throws FileSystemException {
        if (!(other.getFileSystem() instanceof FtpFileSystem)) {
            return EntryRelation.DISTINCT;
        }
        if (getFileSystem() == other.getFileSystem()) {
            return workingDirectoryEntry(other, false, false, true);
        }
        final FileSystemOptions left = getAbstractFileSystem().getClientOptions();
        final FileSystemOptions right = ((FtpFileSystem) other.getFileSystem()).getClientOptions();
        final FtpFileSystemConfigBuilder config = FtpFileSystemConfigBuilder.getInstance();
        final DefaultFileSystemConfigBuilder auth = DefaultFileSystemConfigBuilder.getInstance();
        final boolean sameLogin = auth.getUserAuthenticator(left) == auth.getUserAuthenticator(right);

        final boolean sameRoute = Objects.equals(config.getProxy(left), config.getProxy(right));
        return workingDirectoryEntry(other, Boolean.TRUE.equals(config.getUserDirIsRoot(left)),
                Boolean.TRUE.equals(config.getUserDirIsRoot(right)), sameLogin && sameRoute
                && Objects.equals(config.getControlEncoding(left), config.getControlEncoding(right))
                && Objects.equals(config.getAutodetectUtf8(left), config.getAutodetectUtf8(right)));
    }

    @Override
    public AtomicCapabilities atomicCapabilities() {
        return new AtomicCapabilities(Answer.NO, Answer.NO, replaceAnswer(folderKey(getName())));
    }

    @Override
    public AtomicCapabilities atomicCapabilities(final FileObject target) {
        return new AtomicCapabilities(Answer.NO, Answer.NO, replaceAnswer(folderKey(target.getName())));
    }

    // FTP has no exclusive call.
    @Override
    public AtomicResult createExclusive(final byte[] content, final String scratchName, final String probeName) {
        return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
    }

    @Override
    public AtomicResult linkExclusive(final FileObject target, final String probeName) {
        return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
    }

    @Override
    public AtomicResult publishExclusive(final FileObject target, final String probeName) {
        return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
    }

    // The look at a name: MDTM by name where the server lists MDTM, then a fresh checked listing of the parent. It
    // never opens the file and never changes into the name. The root is taken as there.
    private boolean lookNow(final AtomicCall call, final String path) throws IOException {
        if (path == null) {
            return true;
        }
        if (call.run(c -> c.hasFeature("MDTM"))
                && call.run(c -> c.mdtmReply(path)).getCode() == FTPReply.FILE_STATUS) {
            return true;
        }
        return listingLook(call, path) != null;
    }

    // The entry of the name in a fresh checked listing of its parent. Null when that listing is complete without the
    // name, or when the parent is not there. An error never counts as "not there".
    private FTPFile listingLook(final AtomicCall call, final String path) throws IOException {
        final String parent = parentOf(path);
        final String name = baseOf(path);
        Listing listing = call.run(c -> checkedListing(c, parent));
        call.noteBack(listing.isBackRefused());
        if (needsPlainListing(listing)) {
            // The next command of the call takes a fresh client, because the change back was refused.
            listing = call.run(c -> c.listChecked(parent, false));
            call.noteBack(listing.isBackRefused());
        }
        if (listing.getFailure() != null) {
            throw listFailure(parent, listing.getFailure());
        }
        final FTPFile[] entries = listing.getEntries();
        if (entries != null) {
            for (final FTPFile entry : entries) {
                if (entry != null && name.equals(entry.getName())) {
                    return entry;
                }
            }
            // A good reply with no entry does not prove that the server opened the folder.
            if (countOf(entries) > 0 || listing.isEntered()) {
                return null;
            }
            final Change control = call.run(c -> c.enterAndBack(parent));
            call.noteBack(control.isBackRefused());
            if (control.isEntered()) {
                return null;
            }
        }
        // The parent can not be entered. Nothing can be below a folder that is not there.
        if (!lookNow(call, parent)) {
            return null;
        }
        throw applicationError("vfs.provider/look-not-entered.error", parent);
    }

    private FileSystemException lookError(final IOException error) {
        if (error instanceof FileSystemException) {
            return (FileSystemException) error;
        }
        final FileSystemException wrapped = new FileSystemException("vfs.provider/look.error", error,
                getName().getFriendlyURI());
        wrapped.setFault(Faults.of(error));
        return wrapped;
    }

    // A look that has no answer for a reason of the server, the folder or the name. It is an application error.
    private static FileSystemException applicationError(final String code, final Object name) {
        final FileSystemException error = new FileSystemException(code, name);
        error.setFault(Fault.APPLICATION);
        return error;
    }

    @Override
    public boolean existsNow() throws FileSystemException {
        final AtomicCall call = new AtomicCall();
        try {
            return lookNow(call, relPath);
        } catch (final IOException e) {
            throw lookError(e);
        } finally {
            call.end();
        }
    }

    @Override
    public boolean folderExistsNow() throws FileSystemException {
        final AtomicCall call = new AtomicCall();
        try {
            final Change change = call.run(c -> c.enterAndBack(relPath));
            call.noteBack(change.isBackRefused());
            if (change.isEntered()) {
                return true;
            }
            // The refusal does not say whether the folder is missing or closed to the account.
            if (!lookNow(call, relPath)) {
                return false;
            }
            throw applicationError("vfs.provider/look-not-entered.error", getName().getFriendlyURI());
        } catch (final IOException e) {
            throw lookError(e);
        } finally {
            call.end();
        }
    }

    // SIZE in the type ASCII is the size of the transfer, so the values by name are read only in the type BINARY.
    private boolean isBinary() {
        final FtpFileType type = FtpFileSystemConfigBuilder.getInstance()
                .getFileType(getAbstractFileSystem().getClientOptions());
        return type == null || type == FtpFileType.BINARY;
    }

    private static Long sizeOf(final Reply reply) {
        if (reply.getCode() != FTPReply.FILE_STATUS) {
            return null;
        }
        try {
            return Long.valueOf(reply.getText().trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    // The time of MDTM, read as UTC with the parser of commons net.
    private static Long timeOf(final Reply reply) {
        if (reply.getCode() != FTPReply.FILE_STATUS) {
            return null;
        }
        final Calendar time = MLSxEntryParser.parseGMTdateTime(reply.getText().trim());
        return time == null ? null : Long.valueOf(time.getTimeInMillis());
    }

    @Override
    public StatNow statNow() throws FileSystemException {
        if (relPath == null) {
            throw applicationError("vfs.provider/look-no-size.error", getName().getFriendlyURI());
        }
        final AtomicCall call = new AtomicCall();
        try {
            final boolean mdtmListed = call.run(c -> c.hasFeature("MDTM"));
            boolean mdtmSent = false;
            boolean mdtmShown = false;
            if (isBinary() && mdtmListed && call.run(c -> c.hasFeature("SIZE"))) {
                final Reply size = call.run(c -> c.sizeReply(relPath));
                final Reply mdtm = call.run(c -> c.mdtmReply(relPath));
                mdtmSent = true;
                mdtmShown = mdtm.getCode() == FTPReply.FILE_STATUS;
                final Long bytes = sizeOf(size);
                final Long time = timeOf(mdtm);
                if (bytes != null && time != null) {
                    return StatNow.there(bytes, time, false);
                }
            }
            final FTPFile entry = listingLook(call, relPath);
            if (entry != null) {
                final Calendar time = entry.getTimestamp();
                return StatNow.there(entry.getSize(), time == null ? 0 : time.getTimeInMillis(), true);
            }
            if (mdtmListed && !mdtmSent) {
                mdtmShown = call.run(c -> c.mdtmReply(relPath)).getCode() == FTPReply.FILE_STATUS;
            }
            if (!mdtmShown) {
                return StatNow.notThere();
            }
            final String folder = parentOf(relPath) == null ? "." : parentOf(relPath);
            if (getAbstractFileSystem().firstWarning("hides " + folder)) {
                log.warn("The FTP server hides names from the listing of the folder \"" + folder
                        + "\". MDTM shows \"" + baseOf(relPath) + "\", and LIST -a does not.");
            }
            throw applicationError("vfs.provider/look-no-size.error", getName().getFriendlyURI());
        } catch (final IOException e) {
            throw lookError(e);
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult renameReplacing(final FileObject target, final FileObject probeA, final FileObject probeB)
            throws FileSystemException {
        final String targetPath = ftpOf(target).relPath;
        final AtomicCall call = new AtomicCall();
        try {
            final RenameReplies replies;
            try {
                replies = call.run(c -> c.renameSteps(relPath, targetPath));
            } catch (final IOException e) {
                throw call.notKnown("replacing rename", e, MovePhase.COMMIT, getName().getPath());
            }
            call.recordRenameReply(replies);
            if (replies.getTo() == null) {
                if (isTransport(replies.getFrom())) {
                    return call.refusedByTransport(replies.getFrom());
                }
                // RNTO was not sent, so the target is not the reason.
                return lookAfterRefusal(call, relPath, textOf(replies.getFrom()));
            }
            if (FTPReply.isPositiveCompletion(replies.getTo().getCode())) {
                return call.result(AtomicOutcome.DONE, null);
            }
            if (isTransport(replies.getTo())) {
                return call.refusedByTransport(replies.getTo());
            }
            final String refusal = textOf(replies.getTo());
            final boolean targetThere;
            try {
                targetThere = lookNow(call, targetPath);
            } catch (final IOException e) {
                // The refusal was a reply, so nothing changed.
                call.connectionLost |= Faults.isTransport(e);
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            if (!targetThere) {
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            final String folder = folderKey(target.getName());
            final ReplaceState state = getAbstractFileSystem().getReplaceState(folder);
            if (state == ReplaceState.REPLACES) {
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            if (state == ReplaceState.NO_REPLACE) {
                return call.result(AtomicOutcome.UNSUPPORTED, refusal);
            }
            if (probeA == null || probeB == null) {
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            return replaceTest(call, refusal, ftpOf(probeA).relPath, ftpOf(probeB).relPath, folder);
        } finally {
            call.end();
        }
    }

    // After a refused RNFR: GONE when a look shows the name gone, else REFUSED. A look that throws does not change
    // the outcome.
    private AtomicResult lookAfterRefusal(final AtomicCall call, final String path, final String refusal) {
        try {
            return call.result(lookNow(call, path) ? AtomicOutcome.REFUSED : AtomicOutcome.GONE, refusal);
        } catch (final IOException e) {
            call.connectionLost |= Faults.isTransport(e);
            return call.result(AtomicOutcome.REFUSED, refusal);
        }
    }

    // Does this server refuse a rename only because the target exists. It touches only the two probe files.
    private AtomicResult replaceTest(final AtomicCall call, final String refusal, final String probeA,
            final String probeB, final String folder) {
        try {
            for (final String probe : new String[] {probeA, probeB}) {
                if (!store(call, probe)) {
                    call.remove(probeA);
                    if (probe == probeB) {
                        call.remove(probeB);
                    }
                    return call.result(AtomicOutcome.REFUSED, refusal);
                }
            }
            final RenameReplies test = call.run(c -> c.renameSteps(probeA, probeB));
            call.checkRename(test, probeA, probeB);
            if (test.getTo() == null) {
                // A refused RNFR inside the test decides nothing.
                call.remove(probeA);
                call.remove(probeB);
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            if (FTPReply.isPositiveCompletion(test.getTo().getCode())) {
                call.remove(probeB);
                getAbstractFileSystem().markReplaces(folder);
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            // The control: the same rename with only the existing target taken away.
            if (!call.delete(probeB)) {
                call.leftovers.add(probeB);
                call.remove(probeA);
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            final RenameReplies control = call.run(c -> c.renameSteps(probeA, probeB));
            call.checkRename(control, probeA, probeB);
            if (control.getTo() == null || !FTPReply.isPositiveCompletion(control.getTo().getCode())) {
                call.remove(probeA);
                return call.result(AtomicOutcome.REFUSED, refusal);
            }
            call.remove(probeB);
            getAbstractFileSystem().markNoReplace(folder);
            if (getAbstractFileSystem().firstWarning("replace " + folder)) {
                log.warn("The FTP server does not replace an existing name on a rename in the folder \"" + folder
                        + "\". The replace test on two probe files showed it.");
            }
            return call.result(AtomicOutcome.UNSUPPORTED, refusal);
        } catch (final IOException e) {
            // A transport error in the test decides nothing and remembers nothing.
            call.discard();
            call.connectionLost = true;
            call.leftovers.add(probeA);
            call.leftovers.add(probeB);
            return call.result(AtomicOutcome.REFUSED, refusal);
        }
    }

    // Writes a probe file. False when the server refused it by a reply. A transport reply is an I/O error.
    private boolean store(final AtomicCall call, final String path) throws IOException {
        final OutputStream out = call.run(c -> c.storeFileStream(path));
        if (out == null) {
            transportReply(call);
            return false;
        }
        try {
            out.write(PROBE_CONTENT);
            out.close();
        } catch (final IOException e) {
            call.discard();
            throw e;
        }
        if (!call.run(c -> c.completePendingCommand())) {
            transportReply(call);
            return false;
        }
        return true;
    }

    private static void transportReply(final AtomicCall call) throws IOException {
        final int reply = call.run(c -> c.getReplyCode());
        if (faultOf(reply, false) == Fault.TRANSPORT) {
            call.discard();
            final FileSystemException error = new FileSystemException("vfs.provider.ftp/output-error.debug",
                    "probe", String.valueOf(reply));
            error.setFault(Fault.TRANSPORT);
            throw error;
        }
    }

    @Override
    public AtomicResult takeAway(final FileObject awayTarget) throws FileSystemException {
        final String awayPath = ftpOf(awayTarget).relPath;
        final AtomicCall call = new AtomicCall();
        try {
            final RenameReplies replies;
            try {
                replies = call.run(c -> c.renameSteps(relPath, awayPath));
            } catch (final IOException e) {
                throw call.notKnown("take away", e, MovePhase.RENAME, null);
            }
            call.recordRenameReply(replies);
            if (replies.getTo() == null) {
                if (isTransport(replies.getFrom())) {
                    return call.refusedByTransport(replies.getFrom());
                }
                return lookAfterRefusal(call, relPath, textOf(replies.getFrom()));
            }
            if (isTransport(replies.getTo())) {
                return call.refusedByTransport(replies.getTo());
            }
            return call.result(FTPReply.isPositiveCompletion(replies.getTo().getCode()) ? AtomicOutcome.DONE
                    : AtomicOutcome.REFUSED, textOf(replies.getTo()));
        } finally {
            call.end();
        }
    }

    @Override
    public void refresh() throws FileSystemException {
        if (inRefresh.compareAndSet(false, true)) {
            try {
                synchronized (getFileSystem()) {
                    super.refresh();
                    ftpFile = null;
                    refreshParent = true;
                }
            } finally {
                inRefresh.set(false);
            }
        }
    }

    /**
     * Sets the internal FTPFile for this instance.
     */
    private void setFTPFile(final boolean flush) throws IOException {
        synchronized (getFileSystem()) {
            final FtpFileObject parent = (FtpFileObject) FileObjectUtils.getAbstractFileObject(getParent());
            final FTPFile newFileInfo;
            if (parent != null) {
                newFileInfo = parent.getChildFile(UriParser.decode(getName().getBaseName()), flush);
            } else {
                // Assume the root is a directory and exists
                newFileInfo = new FTPFile();
                newFileInfo.setType(FTPFile.DIRECTORY_TYPE);
            }
            ftpFile = newFileInfo == null ? UNKNOWN : newFileInfo;
        }
    }
}
