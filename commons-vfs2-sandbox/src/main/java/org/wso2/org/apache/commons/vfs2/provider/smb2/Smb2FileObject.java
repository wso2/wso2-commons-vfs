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

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.File;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
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
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.MonitorInputStream;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class containing all its handles from the current Instance but NOT all
 * possible handles to the same file (read/write separated)
 */
public class Smb2FileObject extends AbstractFileObject<Smb2FileSystem> implements AtomicOps {

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


    private static final Log LOG = LogFactory.getLog(Smb2FileObject.class);
    private static final char[] RESERVED_FILE_CHARS = {' ', '#'};
    private final String relPathToShare;
    private FileAllInformation fileInfo;
    private FileName rootName;

    /**
     * @param name       the file name - muse be an instance of {@link AbstractFileName}
     * @param fileSystem the file system
     * @throws ClassCastException if {@code name} is not an instance of {@link AbstractFileName}
     */
    protected Smb2FileObject(AbstractFileName name, Smb2FileSystem fileSystem, final FileName rootName) {

        super(name, fileSystem);
        final String relPath = name.getPath();
        // smb shares do not accept "/" --> it needs a "\" which is represented by "\\"
        relPathToShare = relPath.startsWith("/") ? relPath.substring(1).replace("/", "\\") : relPath.replace("/", "\\");
        this.rootName = rootName;
    }

    @Override
    protected long doGetContentSize() throws Exception {

        getFileInfo();
        return fileInfo.getStandardInformation().getEndOfFile();
    }

    @Override
    protected InputStream doGetInputStream() throws Exception {

        if (!getType().hasContent()) {
            throw new FileSystemException("vfs.provider/read-not-file.error", getName());
        }
        return getDiskEntryReadInputStream();

    }

    @Override
    public FileType getType() throws FileSystemException {
        try {
            return super.getType();
        } catch (final FileSystemException failure) {
            final Fault fault = Faults.match(failure);
            final SMBApiException reply = ExceptionUtils.throwableOfType(failure, SMBApiException.class);
            if (reply == null) {
                failure.setFault(fault);
                throw failure;
            }
            final FileSystemException reported = new FileSystemException("vfs.provider.smb2/get-type-status.error",
                    failure, getRelPathToShare(), reply.getStatus());
            reported.setFault(fault);
            throw reported;
        }
    }

    @Override
    protected FileType doGetType() throws Exception {

        synchronized (getFileSystem()) {
            if (this.fileInfo == null) {
                // returns null if the diskShare cannot fin the file info's. Therefore : imaginary
                getFileInfo();
            }
            if (fileInfo == null) {
                return FileType.IMAGINARY;
            } else {
                return (fileInfo.getStandardInformation().isDirectory()) ? FileType.FOLDER : FileType.FILE;
            }
        }
    }

    @Override
    protected String[] doListChildren() throws Exception {

        // do nothing here since doListChildrenResolved has been implemented
        return null;
    }

    private void getFileInfo() throws FileSystemException {

        if (fileInfo == null) {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
                Throwable failure = null;
                try {
                    fileInfo = client.getFileInfo(getRelPathToShare());
                } catch (FileSystemException | RuntimeException e) {
                    failure = e;
                    throw e;
                } finally {
                    fileSystem.giveBack(client, failure);
                }
            }
        }
    }

    @Override
    public FileObject getParent() throws FileSystemException {

        synchronized (getFileSystem()) {
            AbstractFileName name = (AbstractFileName) getName().getParent();

            // root folder has no parent
            if (name == null) {
                return null;
            }
            FileObject cachedFile = getFileSystem().getFileSystemManager().getFilesCache().getFile(getFileSystem(),
                    name);
            if (cachedFile != null) {
                return cachedFile;
            } else {
                return new Smb2FileObject(name, (Smb2FileSystem) getFileSystem(), rootName);
            }
        }
    }

    @Override
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        Smb2ClientWrapper smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
        // The handle is owned here until the stream takes it. For an append smbj asks the handle for the end of the
        // file, and a refusal there must not leave the handle open on the server.
        DiskEntry diskEntryWrite = null;
        try {
            synchronized (getFileSystem()) {
                diskEntryWrite = smb2ClientWrapper.getDiskEntryWrite(getRelPathToShare(), bAppend);
                OutputStream os = ((File) diskEntryWrite).getOutputStream(bAppend);
                return new Smb2OutputStream(smb2ClientWrapper, os, diskEntryWrite);
            }
        } catch (Exception e) {
            FileSystemException failure = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
            if (diskEntryWrite != null) {
                smb2ClientWrapper.closeOwned(diskEntryWrite, failure);
            }
            fileSystem.giveBack(smb2ClientWrapper, failure);
            throw failure;
        }
    }

    @Override
    protected void doCreateFolder() throws Exception {

        synchronized (getFileSystem()) {
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client;
            try {
                client = (Smb2ClientWrapper) fileSystem.getClient();
            } catch (FileSystemException e) {
                throw new FileSystemException("vfs.provider.smb2/folder-create.error", getName(), e);
            }
            FileSystemException failure = null;
            try {
                client.createFolder(getRelPathToShare());
            } catch (Exception e) {
                failure = failure(client, "vfs.provider.smb2/folder-create.error", e);
                throw failure;
            } finally {
                fileSystem.giveBack(client, failure);
            }
        }
    }

    /**
     * Get file read handle.
     *
     * @throws FileSystemException handle create error
     */
    private Smb2InputStream getDiskEntryReadInputStream() throws FileSystemException {

        Smb2ClientWrapper smb2ClientWrapper = null;
        // The handle is owned here until the stream takes it, so a failure after the open closes it.
        DiskEntry diskEntry = null;
        Smb2FileSystem fileSystem = null;
        try {
            synchronized (getFileSystem()) {
                fileSystem = (Smb2FileSystem) getFileSystem();

                smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
                diskEntry = smb2ClientWrapper.getDiskEntryRead(getRelPathToShare());
                InputStream is = ((File) diskEntry).getInputStream();
                return new Smb2InputStream(smb2ClientWrapper, is, this, diskEntry);
            }
        } catch (Exception e) {
            if (smb2ClientWrapper == null) {
                throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e);
            }
            FileSystemException failure = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
            if (diskEntry != null) {
                smb2ClientWrapper.closeOwned(diskEntry, failure);
            }
            fileSystem.giveBack(smb2ClientWrapper, failure);
            throw failure;
        }
    }

    /**
     * Returns relative path to share.
     *
     * @return string
     */
    public String getRelPathToShare() {

        return decodeOrGet(relPathToShare);
    }

    /**
     * Decode URI value.
     *
     * @param value uri
     * @return decoded path ot path
     */
    public String decodeOrGet(String value) {

        try {
            return UriParser.decode(value);
        } catch (FileSystemException e) {
            return value;
        }
    }

    // A read that is blocked on a socket returns within one second of an abort (part 3, section 5.1, tests).
    private static final long READ_END_NANOS = TimeUnit.SECONDS.toNanos(1);

    // A slice that does not fit in its buffer.
    static boolean isBadSlice(final byte[] buffer, final int offset, final int length) {
        return offset < 0 || length < 0 || length > buffer.length - offset;
    }

    // An error that Java throws for a null buffer or a slice that does not fit, which says nothing about the client.
    static boolean isArgumentError(final RuntimeException error, final byte[] buffer, final int offset,
            final int length) {
        return buffer == null ? error instanceof NullPointerException
                : error instanceof IndexOutOfBoundsException && isBadSlice(buffer, offset, length);
    }

    // An error of a call on the client, with the cause kept and the fault that the client gives it.
    private FileSystemException failure(final Smb2ClientWrapper client, final String code, final Exception error) {
        FileSystemException failure = new FileSystemException(code, getName(), error);
        failure.setFault(client.faultOf(error));
        return failure;
    }

    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        synchronized (getFileSystem()) {

            Smb2FileObject fileObject = (Smb2FileObject) newFile;
            boolean isFolder = doGetType() == FileType.FOLDER;
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper smb2ClientWrapper;
            try {
                smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
            } catch (FileSystemException e) {
                throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e);
            }
            // The failure that decides the give back. A failed close after a rename that returned discards the client,
            // whatever its fault.
            Throwable failure = null;
            boolean discard = false;
            try {
                // The open never creates a missing source and never shares delete.
                DiskEntry diskEntry = smb2ClientWrapper.openForRename(getRelPathToShare(), isFolder);
                RuntimeException renameError = null;
                try {
                    diskEntry.rename(fileObject.getRelPathToShare());
                } catch (RuntimeException e) {
                    renameError = e;
                }
                // The rename is classified before the close. On a dead connection the sockets are closed first, so
                // the close sends nothing and the rename, whose result is not known, is not sent again.
                RuntimeException closeError = smb2ClientWrapper.closeOwned(diskEntry, renameError);
                if (renameError != null) {
                    throw renameError;
                }
                if (closeError != null) {
                    LOG.warn("The rename of " + getName().getFriendlyURI() + " is done, but the close of its "
                            + "handle failed. The client is discarded.", closeError);
                    failure = closeError;
                    discard = true;
                }
            } catch (Exception e) {
                FileSystemException error = failure(smb2ClientWrapper, "vfs.provider.smb2/diskentry-create.error", e);
                failure = error;
                throw error;
            } finally {
                if (discard) {
                    fileSystem.discardClient(smb2ClientWrapper, failure);
                } else {
                    fileSystem.giveBack(smb2ClientWrapper, failure);
                }
            }
        }
    }

    @Override
    protected FileObject[] doListChildrenResolved() throws Exception {

        synchronized (getFileSystem()) {
            if (getType() != FileType.FOLDER) {
                throw new FileSystemException("vfs.provider/list-children-not-folder.error", this);
            }

            List<FileObject> children = new ArrayList<>();

            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
            String[] childrenNames;
            FileSystemException failure = null;
            try {
                childrenNames = client.getChildren(getRelPathToShare());
            } catch (RuntimeException e) {
                failure = failure(client, "vfs.provider/list-children.error", e);
                throw failure;
            } finally {
                fileSystem.giveBack(client, failure);
            }

            for (String child : childrenNames) {
                children.add(fileSystem.getFileSystemManager()
                        .resolveFile(this, UriParser.encode(child, RESERVED_FILE_CHARS)));
            }
            return children.toArray(new FileObject[children.size()]);
        }
    }

    @Override
    protected void doDelete() throws Exception {

        synchronized (getFileSystem()) {
            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
            Throwable failure = null;
            try {
                client.delete(getRelPathToShare());
            } catch (FileSystemException | RuntimeException e) {
                failure = e;
                throw e;
            } finally {
                fileSystem.giveBack(client, failure);
            }
        }
    }

    @Override
    protected long doGetLastModifiedTime() throws Exception {

        getFileInfo();
        return fileInfo.getBasicInformation().getLastWriteTime().toEpochMillis();
    }

    @Override
    public void close() throws FileSystemException {

        super.close();
    }

    @Override
    protected void doDetach() {

        this.fileInfo = null;
    }

    @Override
    public String toString() {

        return getName().toString();
    }

    @Override
    public boolean doSetLastModifiedTime(final long modTime) {

        // Cannot create a FileAllInformation object to override information.
        // Hence do nothing and return true
        return true;
    }

    /**
     * An InputStream that watches for the end of the file. It notes the first error of a read or a close, and apart
     * from it the first transport error of any step. After a transport error the sockets of the client are closed
     * first, so no close step waits for a reply on a dead connection, and the client is discarded.
     */
    // One call of AtomicOps on one client. A client whose call ended with a transport error is discarded at once and
    // not used again.
    private final class AtomicCall {

        private final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();

        // The phase of the public call, which an unconfirmed change reports, and its temp path or null.
        private final MovePhase phase;

        private final String tempPath;

        private Smb2ClientWrapper client;

        private final List<String> leftovers = new ArrayList<>();

        private boolean connectionLost;

        AtomicCall(final MovePhase phase, final String tempPath) throws FileSystemException {
            this.phase = phase;
            this.tempPath = tempPath;
            client = (Smb2ClientWrapper) fileSystem.getClient();
        }

        // The NT status of a reply of the server, or null for a transport error, an interrupt or any other error.
        NtStatus statusOf(final Throwable error) {
            return error instanceof SMBApiException && !Smb2FileSystem.isTransport(client, error)
                    ? ((SMBApiException) error).getStatus() : null;
        }

        void discard(final Throwable cause) {
            if (client != null) {
                fileSystem.discardClient(client, cause);
                client = null;
            }
        }

        void end() {
            if (client != null) {
                fileSystem.putClient(client);
                client = null;
            }
        }

        // A failed close of a handle after the step that decided. The client is discarded, whatever the fault.
        void closeFailed(final RuntimeException closeError) {
            if (closeError != null) {
                connectionLost |= Smb2FileSystem.isTransport(client, closeError);
                discard(closeError);
            }
        }

        // Removes a name. A name that the server could not remove is a leftover.
        void remove(final String path) {
            if (client == null) {
                leftovers.add(path);
                return;
            }
            try {
                client.remove(path);
            } catch (final RuntimeException e) {
                final NtStatus status = statusOf(e);
                if (status == null) {
                    connectionLost = true;
                    discard(e);
                    leftovers.add(path);
                } else if (status != NtStatus.STATUS_OBJECT_NAME_NOT_FOUND
                        && status != NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) {
                    leftovers.add(path);
                }
            }
        }

        AtomicResult result(final AtomicOutcome outcome, final String detail) {
            return new AtomicResult(outcome, detail, leftovers, connectionLost);
        }

        // A change whose outcome no reply of the server confirmed. The client is discarded before the error leaves,
        // and no further call is made.
        UnclearResultException notKnown(final String step, final Throwable cause) {
            discard(cause);
            return new UnclearResultException("vfs.provider/atomic-not-known.error", cause, phase, tempPath, false,
                    step, getName().getFriendlyURI());
        }
    }

    private static String relPathOf(final FileObject file) throws FileSystemException {
        return ((Smb2FileObject) FileObjectUtils.getAbstractFileObject(file)).getRelPathToShare();
    }

    // The folder of a name. A root has no parent, so it is its own folder.
    private static String folderOf(final FileName name) {
        final FileName parent = name.getParent();
        return (parent == null ? name : parent).getPath();
    }

    private static String pairKey(final FileName source, final FileName target) {
        return folderOf(source) + "\u0000" + folderOf(target);
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

    private Answer publishAnswer(final String pair) {
        final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        if (fileSystem.isLinked(pair)) {
            return Answer.YES;
        }
        return fileSystem.isMarkedNoLink(pair, TestPoints.nanoTime("linkmark")) ? Answer.NO : Answer.NOT_KNOWN;
    }

    @Override
    protected EntryRelation entryRelation(final FileObject other) throws FileSystemException {
        if (!(other.getFileSystem() instanceof Smb2FileSystem)) {
            return EntryRelation.DISTINCT;
        }
        final Smb2FileName a = (Smb2FileName) getFileSystem().getRootName();
        final Smb2FileName b = (Smb2FileName) other.getFileSystem().getRootName();
        if (!a.getShareName().equalsIgnoreCase(b.getShareName())) {
            return EntryRelation.DISTINCT;
        }
        return remoteEntry(other, true, true);
    }

    @Override
    public AtomicCapabilities atomicCapabilities() {
        return new AtomicCapabilities(Answer.YES, publishAnswer(pairKey(getName(), getName())), Answer.YES);
    }

    @Override
    public AtomicCapabilities atomicCapabilities(final FileObject target) {
        // A folder has no hard link.
        final Answer publish = getName().getType() == FileType.FOLDER ? Answer.NO
                : publishAnswer(pairKey(getName(), target.getName()));
        return new AtomicCapabilities(Answer.YES, publish, Answer.YES);
    }

    @Override
    public AtomicResult createExclusive(final byte[] content, final String scratchName, final String probeName)
            throws FileSystemException {
        final String path = getRelPathToShare();
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, null);
        try {
            final File file;
            try {
                file = call.client.openCreate(path);
            } catch (final RuntimeException e) {
                final NtStatus status = call.statusOf(e);
                if (status == null) {
                    throw call.notKnown("exclusive create", e);
                }
                return call.result(status == NtStatus.STATUS_OBJECT_NAME_COLLISION ? AtomicOutcome.EXISTS
                        : status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ? AtomicOutcome.GONE
                        : AtomicOutcome.REFUSED, status.name());
            }
            RuntimeException writeError = null;
            try {
                file.write(content, 0);
            } catch (final RuntimeException e) {
                writeError = e;
            }
            final RuntimeException closeError = call.client.closeOwned(file, writeError);
            final RuntimeException failure = writeError != null ? writeError : closeError;
            if (failure == null) {
                return call.result(AtomicOutcome.DONE, null);
            }
            final NtStatus status = call.statusOf(failure);
            if (status == null || writeError != null && closeError != null && call.statusOf(closeError) == null) {
                // The file is left. An empty or a cut lock is the job of the lock module.
                throw call.notKnown("write of the created file", failure);
            }
            call.remove(path);
            return call.result(AtomicOutcome.REFUSED, status.name());
        } finally {
            call.end();
        }
    }

    // The link from this file to the target path, on the client of the call.
    private AtomicResult link(final AtomicCall call, final String targetPath, final String pair)
            throws FileSystemException {
        final DiskEntry entry;
        try {
            entry = call.client.openForLink(getRelPathToShare());
        } catch (final RuntimeException e) {
            return linkRefused(call, e, pair, targetPath);
        }
        RuntimeException linkError = null;
        try {
            entry.createHardlink(targetPath, false);
        } catch (final RuntimeException e) {
            linkError = e;
        }
        final RuntimeException closeError = call.client.closeOwned(entry, linkError);
        if (linkError != null) {
            final AtomicResult refused = linkRefused(call, linkError, pair, targetPath);
            call.closeFailed(closeError);
            return call.result(refused.getOutcome(), refused.getDetail());
        }
        ((Smb2FileSystem) getFileSystem()).markLinks(pair);
        // A failed close after the link returned is not a failure of the link.
        call.closeFailed(closeError);
        return call.result(AtomicOutcome.DONE, null);
    }

    private AtomicResult linkRefused(final AtomicCall call, final RuntimeException error, final String pair,
            final String targetPath) throws FileSystemException {
        final NtStatus status = call.statusOf(error);
        if (status == null) {
            throw call.notKnown("link", error);
        }
        switch (status) {
        case STATUS_OBJECT_NAME_COLLISION:
            return call.result(AtomicOutcome.EXISTS, status.name());
        case STATUS_OBJECT_NAME_NOT_FOUND:
        case STATUS_OBJECT_PATH_NOT_FOUND:
            return call.result(AtomicOutcome.GONE, status.name());
        case STATUS_SHARING_VIOLATION:
        case STATUS_DELETE_PENDING:
            return call.result(AtomicOutcome.BUSY, status.name());
        default:
            break;
        }
        final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        if (fileSystem.isLinked(pair)) {
            return call.result(AtomicOutcome.REFUSED, status.name());
        }
        if (fileSystem.markNoLink(pair, TestPoints.nanoTime("linkmark"))) {
            LOG.warn("The SMB2 server refused a hard link from \"" + getRelPathToShare() + "\" to \"" + targetPath
                    + "\" with " + status.name() + ". This folder pair is not used for a link for 10 minutes.");
        }
        return call.result(AtomicOutcome.UNSUPPORTED, status.name());
    }

    @Override
    public AtomicResult linkExclusive(final FileObject target, final String probeName) throws FileSystemException {
        final String pair = pairKey(getName(), target.getName());
        final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        if (!fileSystem.isLinked(pair) && fileSystem.isMarkedNoLink(pair, TestPoints.nanoTime("linkmark"))) {
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
        }
        final String targetPath = relPathOf(target);
        final AtomicCall call = new AtomicCall(MovePhase.RENAME, null);
        try {
            return link(call, targetPath, pair);
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult publishExclusive(final FileObject target, final String probeName) throws FileSystemException {
        final String pair = pairKey(getName(), target.getName());
        final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        if (!fileSystem.isLinked(pair) && fileSystem.isMarkedNoLink(pair, TestPoints.nanoTime("linkmark"))) {
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
        }
        final String targetPath = relPathOf(target);
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, getName().getPath());
        try {
            final AtomicResult linked = link(call, targetPath, pair);
            if (linked.getOutcome() != AtomicOutcome.DONE) {
                return linked;
            }
            TestPoints.hold("publish.after-link", pointKey(target.getName()));
            call.remove(getRelPathToShare());
            return call.result(AtomicOutcome.DONE, null);
        } finally {
            call.end();
        }
    }

    // The NT status of the first server reply in the cause chain of an error, or null when the chain holds none or
    // shows a transport failure.
    private static NtStatus statusIn(final AtomicCall call, final Throwable error) {
        for (Throwable link = error; link != null && link.getCause() != link; link = link.getCause()) {
            if (link instanceof SMBApiException) {
                return call.statusOf(link);
            }
        }
        return null;
    }

    // A rename of this file on the client of the call, with the outcome of each reply that refused it. The type of the
    // source is read on that client just before the open, because another client can put a file in place of a folder,
    // or a folder in place of a file, after this object read its type. A folder is opened as a directory, with the
    // open and share flags of a directory rename.
    private AtomicResult rename(final AtomicCall call, final String targetPath, final boolean replace,
            final String step) throws FileSystemException {
        final FileAllInformation now;
        try {
            now = call.client.getFileInfo(getRelPathToShare());
        } catch (final FileSystemException e) {
            // Nothing was sent that changes the server. A transport failure retires the client. A status reply of the
            // server about the source, for example a pending delete, has the outcome of the same status at the open.
            if (e.getFault() == Fault.TRANSPORT) {
                call.discard(e);
                throw e;
            }
            final NtStatus status = statusIn(call, e);
            if (status == null) {
                throw e;
            }
            return call.result(renameOutcome(status, true, replace), status.name());
        }
        final boolean folder = now != null && now.getStandardInformation().isDirectory();
        final DiskEntry entry;
        try {
            // The open never creates a missing source and never shares delete.
            entry = call.client.openForRename(getRelPathToShare(), folder);
        } catch (final RuntimeException e) {
            final NtStatus status = call.statusOf(e);
            if (status == null) {
                throw call.notKnown(step, e);
            }
            return call.result(renameOutcome(status, true, replace), status.name());
        }
        RuntimeException renameError = null;
        try {
            entry.rename(targetPath, replace);
        } catch (final RuntimeException e) {
            renameError = e;
        }
        final RuntimeException closeError = call.client.closeOwned(entry, renameError);
        if (renameError != null) {
            final NtStatus status = call.statusOf(renameError);
            if (status == null) {
                throw call.notKnown(step, renameError);
            }
            call.closeFailed(closeError);
            return call.result(renameOutcome(status, false, replace), status.name());
        }
        if (closeError != null) {
            LOG.warn("The " + step + " of " + getName().getFriendlyURI() + " is done, but the close of its handle"
                    + " failed. The client is discarded.", closeError);
            call.closeFailed(closeError);
        }
        return call.result(AtomicOutcome.DONE, null);
    }

    // The replacing rename and the take away read the same statuses in two ways.
    private static AtomicOutcome renameOutcome(final NtStatus status, final boolean open, final boolean replace) {
        if (replace) {
            switch (status) {
            case STATUS_OBJECT_NAME_NOT_FOUND:
            case STATUS_OBJECT_PATH_NOT_FOUND:
                return AtomicOutcome.GONE;
            case STATUS_SHARING_VIOLATION:
                return AtomicOutcome.BUSY;
            default:
                return AtomicOutcome.REFUSED;
            }
        }
        if (status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND) {
            return AtomicOutcome.GONE;
        }
        if (open && status == NtStatus.STATUS_SHARING_VIOLATION) {
            return AtomicOutcome.BUSY;
        }
        if (!open && status == NtStatus.STATUS_OBJECT_NAME_COLLISION) {
            return AtomicOutcome.EXISTS;
        }
        return AtomicOutcome.REFUSED;
    }

    @Override
    public AtomicResult renameReplacing(final FileObject target, final FileObject probeA, final FileObject probeB)
            throws FileSystemException {
        // The replace flag is part of the protocol, so no replace test exists here and the probe files are not used.
        final String targetPath = relPathOf(target);
        final AtomicCall call = new AtomicCall(MovePhase.COMMIT, getName().getPath());
        try {
            return rename(call, targetPath, true, "replacing rename");
        } finally {
            call.end();
        }
    }

    @Override
    public AtomicResult takeAway(final FileObject awayTarget) throws FileSystemException {
        final String awayPath = relPathOf(awayTarget);
        final AtomicCall call = new AtomicCall(MovePhase.RENAME, null);
        try {
            return rename(call, awayPath, false, "take away");
        } finally {
            call.end();
        }
    }

    // One look at this name. Null when the server said that the name is not there. A delete pending counts as there
    // only when the caller asks so, and then the answer has no information.
    private FileAllInformation lookNow(final boolean pendingIsThere, final boolean[] pending)
            throws FileSystemException {
        final Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        final Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
        Throwable failure = null;
        try {
            return client.look(getRelPathToShare());
        } catch (final RuntimeException e) {
            failure = e;
            final NtStatus status = e instanceof SMBApiException && !Smb2FileSystem.isTransport(client, e)
                    ? ((SMBApiException) e).getStatus() : null;
            if (status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
                    || status == NtStatus.STATUS_NO_SUCH_FILE) {
                return null;
            }
            if (pendingIsThere && status == NtStatus.STATUS_DELETE_PENDING) {
                pending[0] = true;
                return null;
            }
            final FileSystemException error = new FileSystemException("vfs.provider/look.error", e,
                    getName().getFriendlyURI());
            error.setFault(status == null ? Fault.TRANSPORT : client.faultOf(e));
            throw error;
        } finally {
            fileSystem.giveBack(client, failure);
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
        final boolean[] pending = new boolean[1];
        return lookNow(true, pending) != null || pending[0];
    }

    @Override
    public boolean folderExistsNow() throws FileSystemException {
        final FileAllInformation found = lookNow(false, null);
        if (found == null) {
            return false;
        }
        if (!found.getStandardInformation().isDirectory()) {
            throw applicationError("vfs.provider/look-not-folder.error", getName().getFriendlyURI());
        }
        return true;
    }

    @Override
    public StatNow statNow() throws FileSystemException {
        final FileAllInformation found = lookNow(false, null);
        return found == null ? StatNow.notThere()
                : StatNow.there(found.getStandardInformation().getEndOfFile(),
                        found.getBasicInformation().getLastWriteTime().toEpochMillis(), false);
    }

    class Smb2InputStream extends MonitorInputStream {
        private final Smb2ClientWrapper client;
        private final FileObject fileObject;
        private final DiskEntry diskEntry;
        private volatile Throwable failure;
        private volatile Throwable transportFailure;
        // The reads that are inside smbj now. A close that finds one does not give the client back for reuse.
        private final AtomicInteger activeReads = new AtomicInteger();
        private final Object readsLock = new Object();
        private volatile boolean leaseReturned;
        // Set when the close starts. The base class guards its reads with a flag of its own, and a skip has no guard
        // there, so the skip reads this one.
        private volatile boolean closing;

        public Smb2InputStream(final Smb2ClientWrapper client, final InputStream in, FileObject fileObject, DiskEntry diskEntry) {
            super(in);
            this.client = client;
            this.fileObject = fileObject;
            this.diskEntry = diskEntry;

        }

        // smbj throws unchecked. Each unchecked error that leaves this stream becomes a FileSystemException with the
        // fault that the client gives it and the cause kept, so the caller sees the same fault as the client.
        @Override
        public synchronized int read() throws IOException {
            activeReads.incrementAndGet();
            try {
                return super.read();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        @Override
        public synchronized int read(final byte[] buffer, final int offset, final int length) throws IOException {
            activeReads.incrementAndGet();
            try {
                return super.read(buffer, offset, length);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                if (isArgumentError(e, buffer, offset, length)) {
                    // The base class checks the arguments before it reads a byte, so smbj never saw this call.
                    throw e;
                }
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        // A skip with a mark set and an empty buffer fills the buffer from smbj, so it counts and fails as a read does.
        // A skip that the buffer serves, and a request of zero or less, reach no server.
        @Override
        public synchronized long skip(final long n) throws IOException {
            activeReads.incrementAndGet();
            try {
                if (closing) {
                    // As Java answers a skip on a closed buffered stream. The client may belong to someone else now.
                    throw new IOException("Stream closed");
                }
                return super.skip(n);
            } catch (IOException e) {
                if (!closing) {
                    note(e);
                }
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/read.error", e);
            } finally {
                readLeft();
            }
        }

        private void readLeft() {
            if (activeReads.decrementAndGet() == 0) {
                synchronized (readsLock) {
                    readsLock.notifyAll();
                }
            }
        }

        // The base class marks the stream closed before this runs, so a read that counts itself later finds the
        // stream closed and never reaches smbj. A read that counted itself before is seen here.
        private void endActiveReads() {
            if (activeReads.get() == 0 || Thread.holdsLock(this)) {
                return;
            }
            // The client is retired: its sockets are closed, which ends the read in smbj, and the give back finds it
            // not alive and discards it. This runs without the monitor that the read holds.
            client.abort();
            final long deadline = System.nanoTime() + READ_END_NANOS;
            synchronized (readsLock) {
                long left;
                while (activeReads.get() > 0 && (left = deadline - System.nanoTime()) > 0) {
                    try {
                        TimeUnit.NANOSECONDS.timedWait(readsLock, left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        // The first step of the close. After a transport error the sockets are closed before anything else. An
        // unchecked error of the smbj stream becomes a FileSystemException, so the base class still runs onClose.
        @Override
        protected void closeSuper() throws IOException {
            closing = true;
            endActiveReads();
            abortAfterTransportError();
            try {
                super.closeSuper();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/close-instr.error", e);
            }
        }

        /**
         * Called after the stream has been closed. Each close has its own try, so a failed close never skips the close
         * of the handle.
         */
        @Override
        protected void onClose() throws IOException {
            IOException firstException = null;
            try {
                try {
                    checkStreamClose();
                } catch (IOException e) {
                    note(e);
                    firstException = e;
                }
                abortAfterTransportError();
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (IOException | RuntimeException e) {
                    firstException = noted(firstException, e);
                }
                try {
                    fileObject.close();
                } catch (IOException | RuntimeException e) {
                    firstException = noted(firstException, e);
                }
                abortAfterTransportError();
                try {
                    diskEntry.close();
                } catch (RuntimeException e) {
                    firstException = noted(firstException, e);
                }
            } finally {
                leaseReturned = true;
                ((Smb2FileSystem) getAbstractFileSystem()).giveBack(client, decidingFailure());
            }
            if (firstException != null) {
                throw firstException;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
            if (transportFailure == null && Smb2FileSystem.isTransport(client, error)) {
                transportFailure = error;
                // The sockets are closed at once, so a later flush or close fails at once and does not wait a second
                // request timeout. A client that was already given back belongs to someone else and is not touched.
                if (!leaseReturned) {
                    client.abort();
                }
            }
        }

        // A transport error of any step decides the give back, also after an earlier refusal. Else the first error.
        private Throwable decidingFailure() {
            return transportFailure != null ? transportFailure : failure;
        }

        private IOException noted(final IOException first, final Exception error) {
            note(error);
            if (first != null) {
                return first;
            }
            return error instanceof IOException ? (IOException) error
                    : failure(client, "vfs.provider/close-instr.error", error);
        }

        private void abortAfterTransportError() {
            final Throwable noted = decidingFailure();
            if (noted != null && Smb2FileSystem.isTransport(client, noted)) {
                client.abort();
            }
        }
    }

    /**
     * An OutputStream that notes the first error of a write, a flush or a close, and apart from it the first transport
     * error of any step. After a transport error the sockets of the client are closed first, so no close step waits
     * for a reply on a dead connection, and the client is discarded.
     */
    class Smb2OutputStream extends MonitorOutputStream {
        private final Smb2ClientWrapper client;
        private final DiskEntry diskEntry;
        private volatile Throwable failure;
        private volatile Throwable transportFailure;
        private volatile boolean leaseReturned;
        private volatile boolean closeHandled;

        public Smb2OutputStream(final Smb2ClientWrapper client, final OutputStream out, DiskEntry diskEntry) {
            super(out);
            this.client = client;
            this.diskEntry = diskEntry;
        }

        @Override
        public synchronized void write(final int b) throws IOException {
            try {
                super.write(b);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        @Override
        public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
            assertOpen();
            // An error of the caller is thrown as Java throws it, before a byte reaches smbj, and does not mark the
            // client. The buffer of the base class would pass a long slice to smbj without a check.
            if (b == null) {
                throw new NullPointerException();
            }
            if (isBadSlice(b, off, len)) {
                throw new IndexOutOfBoundsException("offset " + off + ", length " + len + ", buffer " + b.length);
            }
            try {
                super.write(b, off, len);
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            try {
                super.flush();
            } catch (IOException e) {
                note(e);
                throw e;
            } catch (RuntimeException e) {
                note(e);
                throw failure(client, "vfs.provider/write.error", e);
            }
        }

        // smbj throws unchecked. Each unchecked error that leaves this stream becomes a FileSystemException with the
        // fault that the client gives it and the cause kept. The stream of the file content wraps this one and
        // catches only an IOException, so an unchecked error would stop its close before it reaches this close.
        @Override
        public void close() throws IOException {
            abortAfterTransportError();
            try {
                super.close();
            } catch (RuntimeException e) {
                // Then the base class stops before onClose, so the close of the handle and the give back of the
                // client run here.
                note(e);
                final FileSystemException error = failure(client, "vfs.provider/close-outstr.error", e);
                if (!closeHandled) {
                    try {
                        onClose();
                    } catch (IOException closeError) {
                        if (closeError.getCause() != e) {
                            error.addSuppressed(closeError);
                        }
                    }
                }
                throw error;
            }
        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            closeHandled = true;
            IOException firstException = null;
            try {
                try {
                    checkStreamClose();
                } catch (IOException e) {
                    note(e);
                    firstException = e;
                }
                abortAfterTransportError();
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (IOException e) {
                    note(e);
                    firstException = e;
                } catch (RuntimeException e) {
                    // smbj surfaces write/flush timeouts (and other transport failures) as an
                    // unchecked SMBRuntimeException. It must not skip diskEntry.close() below,
                    // otherwise the remote SMB2 file handle is leaked until the JVM/session ends.
                    note(e);
                    firstException = failure(client, "vfs.provider/close-outstr.error", e);
                }
                abortAfterTransportError();
                try {
                    diskEntry.close();
                } catch (RuntimeException e) {
                    note(e);
                    if (firstException == null) {
                        firstException = failure(client, "vfs.provider/close-outstr.error", e);
                    }
                }
            } finally {
                leaseReturned = true;
                ((Smb2FileSystem) getAbstractFileSystem()).giveBack(client, decidingFailure());
            }
            if (firstException != null) {
                throw firstException;
            }
        }

        private void note(final Throwable error) {
            if (failure == null) {
                failure = error;
            }
            if (transportFailure == null && Smb2FileSystem.isTransport(client, error)) {
                transportFailure = error;
                // The sockets are closed at once, so a later flush or close fails at once and does not wait a second
                // request timeout. A client that was already given back belongs to someone else and is not touched.
                if (!leaseReturned) {
                    client.abort();
                }
            }
        }

        // A transport error of any step decides the give back, also after an earlier refusal. Else the first error.
        private Throwable decidingFailure() {
            return transportFailure != null ? transportFailure : failure;
        }

        private void abortAfterTransportError() {
            final Throwable noted = decidingFailure();
            if (noted != null && Smb2FileSystem.isTransport(client, noted)) {
                client.abort();
            }
        }
    }
}
