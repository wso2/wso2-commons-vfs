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
package org.wso2.org.apache.commons.vfs2.provider.local;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.file.PathUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
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
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem.LinkMemory;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * A file object implementation which uses direct file access.
 */
public class LocalFile extends AbstractFileObject<LocalFileSystem> implements AtomicOps {

    private static final Log LOG = LogFactory.getLog(LocalFile.class);

    private final String rootFile;

    private File file;

    /**
     * Creates a non-root file.
     *
     * @param fileSystem the file system this file belongs to.
     * @param rootFile the root file for the file system.
     * @param name the file name on this file system.
     */
    protected LocalFile(final LocalFileSystem fileSystem, final String rootFile, final AbstractFileName name) {
        super(name, fileSystem);
        this.rootFile = rootFile;
    }

    /**
     * Attaches this file object to its file resource.
     */
    @Override
    protected void doAttach() throws Exception {
        if (file == null) {
            // Remove the "file:///"
            // LocalFileName localFileName = (LocalFileName) getName();
            final String fileName = rootFile + getName().getPathDecoded();
            // fileName = UriParser.decode(fileName);
            file = new File(fileName);
        }
    }

    /**
     * Creates this folder.
     */
    @Override
    protected void doCreateFolder() throws Exception {
        // The class and the reason of a failure reach the caller, which a false of java.io.File does not give.
        Files.createDirectories(file.toPath());
    }

    /**
     * Deletes this file, and all children.
     */
    @Override
    protected void doDelete() throws Exception {
        Files.delete(file.toPath());
    }

    /**
     * Returns the size of the file content (in bytes).
     */
    @Override
    protected long doGetContentSize() throws Exception {
        return file.length();
    }

    /**
     * Creates an input stream to read the file contents.
     */
    @Override
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        // FileInputStream gives FileNotFoundException for every failed open, also on an unavailable mount. So the
        // attributes are read once first, and a failure keeps its typed cause. Only a name that is not there, and a
        // folder, stay the public outcome that the file is not a readable file.
        final Path path = file.toPath();
        try {
            if (Files.readAttributes(path, BasicFileAttributes.class).isDirectory()) {
                throw new FileNotFoundException(file.getPath() + " (Is a directory)");
            }
            return Files.newInputStream(path);
        } catch (final NoSuchFileException | NotDirectoryException e) {
            final FileNotFoundException missing = new FileNotFoundException(file.getPath());
            missing.initCause(e);
            throw missing;
        }
    }

    /**
     * Gets the last modified time of this file.
     */
    @Override
    protected long doGetLastModifiedTime() throws FileSystemException {
        // Workaround OpenJDK 8 and 9 bug JDK-8177809
        // https://bugs.openjdk.java.net/browse/JDK-8177809
        try {
            return Files.getLastModifiedTime(file.toPath()).toMillis();
        } catch (final IOException e) {
            throw new FileSystemException("vfs.provider/get-last-modified.error", file, e);
        }
    }

    /**
     * Creates an output stream to write the file content to.
     */
    @Override
    protected OutputStream doGetOutputStream(final boolean append) throws IOException {
        return PathUtils.newOutputStream(file.toPath(), append);
    }

    @Override
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        return new LocalFileRandomAccessContent(file, mode);
    }

    /**
     * Returns the file's type.
     */
    @Override
    protected FileType doGetType() throws IOException {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            return attributes.isDirectory() ? FileType.FOLDER : FileType.FILE;
        } catch (final NoSuchFileException | NotDirectoryException missing) {
            return FileType.IMAGINARY;
        }
    }

    /**
     * Determines if this file is hidden.
     */
    @Override
    protected boolean doIsExecutable() {
        return file.canExecute();
    }

    /**
     * Determines if this file is hidden.
     */
    @Override
    protected boolean doIsHidden() {
        return file.isHidden();
    }

    /**
     * Determines if this file can be read.
     */
    @Override
    protected boolean doIsReadable() throws FileSystemException {
        return file.canRead();
    }

    @Override
    protected boolean doIsSameFile(final FileObject destFile) throws FileSystemException {
        if (!FileObjectUtils.isInstanceOf(destFile, LocalFile.class)) {
            return false;
        }

        final LocalFile destLocalFile = (LocalFile) FileObjectUtils.getAbstractFileObject(destFile);
        if (!exists() || !destLocalFile.exists()) {
            return false;
        }

        try {
            return file.getCanonicalPath().equals(destLocalFile.file.getCanonicalPath());
        } catch (final IOException e) {
            throw new FileSystemException(e);
        }
    }

    /**
     * Determines if this file is a symbolic link.
     *
     * @since 2.4
     */
    @Override
    protected boolean doIsSymbolicLink() throws FileSystemException {
        return Files.isSymbolicLink(file.toPath());
    }

    /**
     * Determines if this file can be written to.
     */
    @Override
    protected boolean doIsWriteable() throws FileSystemException {
        return file.canWrite();
    }

    /**
     * Returns the children of the file.
     */
    @Override
    protected String[] doListChildren() throws Exception {
        return UriParser.encode(file.list());
    }

    /**
     * rename this file
     */
    @Override
    protected void doRename(final FileObject newFile) throws Exception {
        Files.move(pathNow(), pathOf(newFile), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    protected boolean doSetExecutable(final boolean executable, final boolean ownerOnly) throws Exception {
        return file.setExecutable(executable, ownerOnly);
    }

    /**
     * Sets the last modified time of this file.
     *
     * @since 2.0
     */
    @Override
    protected boolean doSetLastModifiedTime(final long modtime) throws FileSystemException {
        return file.setLastModified(modtime);
    }

    @Override
    protected boolean doSetReadable(final boolean readable, final boolean ownerOnly) throws Exception {
        return file.setReadable(readable, ownerOnly);
    }

    @Override
    protected boolean doSetWritable(final boolean writable, final boolean ownerOnly) throws Exception {
        return file.setWritable(writable, ownerOnly);
    }

    /**
     * Gets the local file that this file object represents.
     *
     * @return the local file that this file object represents.
     */
    protected File getLocalFile() {
        return file;
    }

    /**
     * Returns the URI of the file.
     *
     * @return The URI of the file.
     */
    @Override
    public String toString() {
        try {
            // VFS-325: URI may contain percent-encoded values as part of file name, so decode
            // those characters before returning
            return UriParser.decode(getName().getURI());
        } catch (final FileSystemException e) {
            return getName().getURI();
        }
    }
    // The path of this name, as doAttach makes it, with no need of an attached object.
    private Path pathNow() throws FileSystemException {
        return new File(rootFile + getName().getPathDecoded()).toPath();
    }

    @Override
    protected boolean isSameEntry(final FileObject other) throws FileSystemException {
        if (!FileObjectUtils.isInstanceOf(other, LocalFile.class)) {
            return false;
        }
        final Path first = pathNow();
        final Path second = pathOf(other);
        if (first.toString().equals(second.toString())) {
            return true;
        }
        if (first.getParent() == null || second.getParent() == null
                || !first.getFileName().toString().equals(second.getFileName().toString())) {
            return false;
        }
        try {
            return Files.isSameFile(first.getParent(), second.getParent());
        } catch (final NoSuchFileException e) {
            return false;
        } catch (final IOException e) {
            throw new FileSystemException(e);
        }
    }

    @Override
    protected boolean isOtherHardLink(final FileObject other) throws FileSystemException {
        if (!FileObjectUtils.isInstanceOf(other, LocalFile.class)) {
            return false;
        }
        final Path source = pathNow();
        final Path target = pathOf(other);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !sameExistingFile(source, other)) {
            return false;
        }
        try {
            if (source.getFileName().toString().equalsIgnoreCase(target.getFileName().toString())
                    && Files.isSameFile(source.getParent(), target.getParent())) {
                boolean hasSource = false;
                boolean hasTarget = false;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(source.getParent())) {
                    for (final Path entry : entries) {
                        final String name = entry.getFileName().toString();
                        hasSource |= name.equals(source.getFileName().toString());
                        hasTarget |= name.equals(target.getFileName().toString());
                    }
                }
                return hasSource && hasTarget;
            }
            return true;
        } catch (final IOException e) {
            throw new FileSystemException(e);
        }
    }

    @Override
    protected boolean isUnsafeTempFor(final FileObject source, final FileObject target) throws FileSystemException {
        final Path temp = pathNow();
        return Files.isSymbolicLink(temp) || super.isUnsafeTempFor(source, target)
                || sameExistingFile(temp, source) || sameExistingFile(temp, target);
    }

    private static boolean sameExistingFile(final Path temp, final FileObject other) throws FileSystemException {
        if (!FileObjectUtils.isInstanceOf(other, LocalFile.class)) {
            return false;
        }
        try {
            return Files.isSameFile(temp, pathOf(other));
        } catch (final NoSuchFileException e) {
            return false;
        } catch (final IOException e) {
            throw new FileSystemException(e);
        }
    }

    private static Path pathOf(final FileObject file) throws FileSystemException {
        return ((LocalFile) FileObjectUtils.getAbstractFileObject(file)).pathNow();
    }

    private Path pathIn(final FileName folder, final String baseName) throws FileSystemException {
        return new File(rootFile + folder.getPathDecoded(), baseName).toPath();
    }

    // The folder of a name. A root has no parent, so it is its own folder.
    private static String folderOf(final FileName name) {
        final FileName parent = name.getParent();
        return (parent == null ? name : parent).getPath();
    }

    private static String pairKey(final FileName source, final FileName target) {
        return folderOf(source) + "\u0000" + folderOf(target);
    }

    private static String detail(final Exception error) {
        return error.getClass().getName() + ": " + error.getMessage();
    }

    // A change on the mount that ended with an error that decides nothing. The result is not known, and the error
    // keeps its cause.
    private UnclearResultException notKnown(final String step, final IOException cause, final MovePhase phase,
            final String tempPath) {
        return new UnclearResultException("vfs.provider/atomic-not-known.error", cause, phase, tempPath, false, step,
                getName().getFriendlyURI());
    }

    // A change on the mount that failed before it changed anything. A subclass of the JDK error names the answer of
    // the kernel, for example a missing folder, so the error keeps the fault of its cause. The plain class and any
    // other error decide nothing, and the result is not known.
    private FileSystemException changeFailed(final IOException cause, final String step, final MovePhase phase,
            final String tempPath, final String code, final Object... info) {
        if (cause instanceof java.nio.file.FileSystemException && !isPlain(cause)) {
            final FileSystemException error = new FileSystemException(code, cause, info);
            error.setFault(Faults.of(cause));
            return error;
        }
        return notKnown(step, cause, phase, tempPath);
    }

    // The plain class that the JDK gives for every error number of a failed link that has no subclass of its own.
    private static boolean isPlain(final Exception error) {
        return error.getClass() == java.nio.file.FileSystemException.class;
    }

    private Answer publishAnswer(final String pair) {
        final LinkMemory memory = getAbstractFileSystem().findLinkMemory(pair);
        if (memory == null) {
            return Answer.NOT_KNOWN;
        }
        if (memory.isLinked()) {
            return Answer.YES;
        }
        return memory.isMarked(TestPoints.nanoTime("linkmark")) ? Answer.NO : Answer.NOT_KNOWN;
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
        final Path path = pathNow();
        try {
            Files.createFile(path);
        } catch (final FileAlreadyExistsException e) {
            return new AtomicResult(AtomicOutcome.EXISTS, detail(e));
        } catch (final IOException e) {
            throw changeFailed(e, "exclusive create", MovePhase.COMMIT, null, "vfs.provider/create-file.error",
                    getName().getFriendlyURI());
        }
        try {
            Files.write(path, content, StandardOpenOption.WRITE);
        } catch (final IOException e) {
            throw notKnown("write of the created file", e, MovePhase.COMMIT, null);
        }
        return new AtomicResult(AtomicOutcome.DONE, null);
    }

    // The link from a source path to a target path, with the mount test and the memory rule of the folder pair. An
    // unconfirmed link reports the phase and the temp path of its public call.
    private AtomicResult link(final Path source, final Path target, final Path probe, final String pair,
            final List<String> leftovers, final MovePhase phase, final String tempPath) throws FileSystemException {
        final LocalFileSystem fileSystem = getAbstractFileSystem();
        final LinkMemory seen = fileSystem.findLinkMemory(pair);
        if (seen != null && seen.isMarked(TestPoints.nanoTime("linkmark"))) {
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
        }
        final boolean afterMark = seen != null && seen.isAfterMark();
        final java.nio.file.FileSystemException first;
        try {
            Files.createLink(target, source);
            fileSystem.getLinkMemory(pair).markLinks();
            return new AtomicResult(AtomicOutcome.DONE, null, leftovers, false);
        } catch (final FileAlreadyExistsException e) {
            return new AtomicResult(AtomicOutcome.EXISTS, detail(e), leftovers, false);
        } catch (final NoSuchFileException e) {
            return new AtomicResult(AtomicOutcome.GONE, detail(e), leftovers, false);
        } catch (final AccessDeniedException e) {
            return new AtomicResult(AtomicOutcome.REFUSED, detail(e), leftovers, false);
        } catch (final UnsupportedOperationException e) {
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, detail(e), leftovers, false);
        } catch (final java.nio.file.FileSystemException e) {
            if (!isPlain(e)) {
                throw notKnown("link", e, phase, tempPath);
            }
            first = e;
        } catch (final IOException e) {
            throw notKnown("link", e, phase, tempPath);
        }
        if (afterMark) {
            // The mark has ended. One control call and no count.
            control(source, first, phase, tempPath);
            fileSystem.getLinkMemory(pair).mark(TestPoints.nanoTime("linkmark"));
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, detail(first), leftovers, false);
        }
        // The mount test: a control call, then a probe that no other party contends for, then the control again.
        control(source, first, phase, tempPath);
        try {
            Files.createLink(probe, source);
        } catch (final java.nio.file.FileSystemException e) {
            if (!isPlain(e)) {
                throw notKnown("link", first, phase, tempPath);
            }
            control(source, first, phase, tempPath);
            final LinkMemory now = fileSystem.findLinkMemory(pair);
            if (now != null && now.isLinked()) {
                return new AtomicResult(AtomicOutcome.REFUSED, detail(first), leftovers, false);
            }
            if (fileSystem.getLinkMemory(pair).count(TestPoints.nanoTime("linkmark"))) {
                LOG.warn("The mount refused a hard link from \"" + source.getParent() + "\" to \"" + target.getParent()
                        + "\" three times, each at least 10 s after the one before, while it answered the control"
                        + " read. The reason was \"" + first.getReason() + "\". This folder pair is not used for a"
                        + " link for 10 minutes.");
            }
            return new AtomicResult(AtomicOutcome.UNSUPPORTED, detail(first), leftovers, false);
        } catch (final IOException e) {
            throw notKnown("link", first, phase, tempPath);
        }
        // The folder can link. The first failure was not an existing target, which has its own class.
        fileSystem.getLinkMemory(pair).markLinks();
        remove(probe, leftovers);
        return new AtomicResult(AtomicOutcome.REFUSED, detail(first), leftovers, false);
    }

    // The control call: the attributes of the source, which was just written through this mount. If it fails, the
    // mount has an I/O problem, and the first failure leaves with nothing decided.
    private void control(final Path source, final java.nio.file.FileSystemException first, final MovePhase phase,
            final String tempPath) throws FileSystemException {
        try {
            Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final IOException e) {
            final FileSystemException error = notKnown("link", first, phase, tempPath);
            error.addSuppressed(e);
            throw error;
        }
    }

    private static void remove(final Path path, final List<String> leftovers) {
        try {
            Files.delete(path);
        } catch (final NoSuchFileException e) {
            // It is gone.
        } catch (final IOException e) {
            leftovers.add(path.toString());
        }
    }

    @Override
    public AtomicResult linkExclusive(final FileObject target, final String probeName) throws FileSystemException {
        return link(pathNow(), pathOf(target), pathIn(target.getName().getParent(), probeName),
                pairKey(getName(), target.getName()), new ArrayList<>(), MovePhase.RENAME, null);
    }

    @Override
    public AtomicResult publishExclusive(final FileObject target, final String probeName) throws FileSystemException {
        final List<String> leftovers = new ArrayList<>();
        final Path temp = pathNow();
        final AtomicResult linked = link(temp, pathOf(target), pathIn(target.getName().getParent(), probeName),
                pairKey(getName(), target.getName()), leftovers, MovePhase.COMMIT, getName().getPath());
        if (linked.getOutcome() != AtomicOutcome.DONE) {
            return linked;
        }
        TestPoints.hold("publish.after-link", target.getName().getURI());
        remove(temp, leftovers);
        return new AtomicResult(AtomicOutcome.DONE, null, leftovers, false);
    }

    @Override
    public AtomicResult renameReplacing(final FileObject target, final FileObject probeA, final FileObject probeB)
            throws FileSystemException {
        // One rename call on a POSIX file system, which replaces an existing target. The probe files are not used.
        try {
            Files.move(pathNow(), pathOf(target), StandardCopyOption.ATOMIC_MOVE);
            return new AtomicResult(AtomicOutcome.DONE, null);
        } catch (final NoSuchFileException e) {
            return new AtomicResult(AtomicOutcome.GONE, detail(e));
        } catch (final FileAlreadyExistsException | AtomicMoveNotSupportedException | AccessDeniedException e) {
            return new AtomicResult(AtomicOutcome.REFUSED, detail(e));
        } catch (final IOException e) {
            throw changeFailed(e, "replacing rename", MovePhase.COMMIT, getName().getPath(),
                    "vfs.provider.local/rename-file.error", getName().getFriendlyURI(),
                    target.getName().getFriendlyURI());
        }
    }

    @Override
    public AtomicResult takeAway(final FileObject awayTarget) throws FileSystemException {
        try {
            Files.move(pathNow(), pathOf(awayTarget), StandardCopyOption.ATOMIC_MOVE);
            return new AtomicResult(AtomicOutcome.DONE, null);
        } catch (final NoSuchFileException e) {
            return new AtomicResult(AtomicOutcome.GONE, detail(e));
        } catch (final IOException e) {
            throw changeFailed(e, "take away", MovePhase.RENAME, null, "vfs.provider.local/rename-file.error",
                    getName().getFriendlyURI(), awayTarget.getName().getFriendlyURI());
        }
    }

    // One read of the attributes of this name, with no link followed. Null when the name is not there. Files.exists
    // is not used, because it answers false when it can not tell.
    private BasicFileAttributes lookNow() throws FileSystemException {
        try {
            return Files.readAttributes(pathNow(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (final NoSuchFileException | NotDirectoryException e) {
            return null;
        } catch (final IOException e) {
            final FileSystemException error = new FileSystemException("vfs.provider/look.error", e,
                    getName().getFriendlyURI());
            error.setFault(Faults.of(e));
            throw error;
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
        final BasicFileAttributes found = lookNow();
        if (found == null) {
            return false;
        }
        if (!found.isDirectory()) {
            throw applicationError("vfs.provider/look-not-folder.error", getName().getFriendlyURI());
        }
        return true;
    }

    @Override
    public StatNow statNow() throws FileSystemException {
        final BasicFileAttributes found = lookNow();
        return found == null ? StatNow.notThere()
                : StatNow.there(found.size(), found.lastModifiedTime().toMillis(), false);
    }
}
