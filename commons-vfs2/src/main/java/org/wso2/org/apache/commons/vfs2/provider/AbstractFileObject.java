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
package org.wso2.org.apache.commons.vfs2.provider;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.io.function.Uncheck;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileContent;
import org.wso2.org.apache.commons.vfs2.FileContentInfoFactory;
import org.wso2.org.apache.commons.vfs2.FileMonitor;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileNotFolderException;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSelector;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.RandomAccessContent;
import org.wso2.org.apache.commons.vfs2.Selectors;
import org.wso2.org.apache.commons.vfs2.operations.DefaultFileOperations;
import org.wso2.org.apache.commons.vfs2.operations.FileOperations;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.RandomAccessMode;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * A partial file object implementation.
 *
 * TODO - Chop this class up - move all the protected methods to several interfaces, so that structure and content can
 * be separately overridden.
 *
 * <p>
 * TODO - Check caps in methods like getChildren(), etc, and give better error messages (eg 'this file type does not
 * support listing children', vs 'this is not a folder')
 * </p>
 *
 * @param <AFS> An AbstractFileSystem subclass
 */
public abstract class AbstractFileObject<AFS extends AbstractFileSystem> implements FileObject {

    /**
     * Same as {@link BufferedInputStream}.
     */
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private static final Log LOG = LogFactory.getLog(AbstractFileObject.class);

    private static final int INITIAL_LIST_SIZE = 5;
    private static final String DO_GET_INPUT_STREAM_INT = "doGetInputStream(int)";
    public boolean getUpdateLastModified() {
        return updateLastModified;
    }

    public void setUpdateLastModified(boolean updateLastModified) {
        this.updateLastModified = updateLastModified;
    }

    public boolean getIsMounted() {
        return isMounted;
    }

    public void setIsMounted(boolean isMounted) {
        this.isMounted = isMounted;
    }

    /**
     * Traverses a file.
     */
    private static void traverse(final DefaultFileSelectorInfo fileInfo, final FileSelector selector,
            final boolean depthwise, final List<FileObject> selected) throws Exception {
        // Check the file itself
        final FileObject file = fileInfo.getFile();
        final int index = selected.size();

        // If the file is a folder, traverse it
        if (file.getType().hasChildren() && selector.traverseDescendants(fileInfo)) {
            final int curDepth = fileInfo.getDepth();
            fileInfo.setDepth(curDepth + 1);

            // Traverse the children
            final FileObject[] children = file.getChildren();
            for (final FileObject child : children) {
                fileInfo.setFile(child);
                traverse(fileInfo, selector, depthwise, selected);
            }

            fileInfo.setFile(file);
            fileInfo.setDepth(curDepth);
        }

        // Add the file if doing depthwise traversal
        if (selector.includeFile(fileInfo)) {
            if (depthwise) {
                // Add this file after its descendants
                selected.add(file);
            } else {
                // Add this file before its descendants
                selected.add(index, file);
            }
        }
    }
    private final AbstractFileName fileName;

    private final AFS fileSystem;
    private FileContent content;
    // Cached info
    private boolean attached;

    private FileType type;
    private FileObject parent;

    // Changed to hold only the name of the children and let the object
    // go into the global files cache
    // private FileObject[] children;
    private FileName[] children;

    private List<Object> objects;
    private boolean updateLastModified;
    /**
     * FileServices instance.
     */
    private FileOperations operations;
    private boolean isMounted;


    /**
     * Constructs a new instance for subclasses.
     *
     * @param fileName the file name.
     * @param fileSystem the file system.
     */
    protected AbstractFileObject(final AbstractFileName fileName, final AFS fileSystem) {
        this.fileName = fileName;
        this.fileSystem = fileSystem;
        fileSystem.fileObjectHanded(this);
        this.updateLastModified = true;
        this.isMounted = false;
    }

    /**
     * Attaches to the file.
     *
     * @throws FileSystemException if an error occurs.
     */
    private void attach() throws FileSystemException {
        synchronized (fileSystem) {
            if (attached) {
                return;
            }

            try {
                // Attach and determine the file type
                doAttach();
                attached = true;
                // now the type could already be injected by doAttach (e.g. from parent to child)

                /*
                 * VFS-210: determine the type when really asked fore if (type == null) { setFileType(doGetType()); } if
                 * (type == null) { setFileType(FileType.IMAGINARY); }
                 */
            } catch (final Exception exc) {
                throw new FileSystemException("vfs.provider/get-type.error", exc, fileName);
            }

            // fs.fileAttached(this);
        }
    }

    /**
     * Queries the object if a simple rename to the file name of {@code newfile} is possible.
     *
     * @param newfile the new file name
     * @return true if rename is possible
     */
    @Override
    public boolean canRenameTo(final FileObject newfile) {
        // If volume mounted, it is considered as two different file systems, hence cannot rename.
        if (this.getIsMounted() || newfile.getIsMounted()) {
            return false;
        }
        return fileSystem == newfile.getFileSystem();
    }

    /**
     * Notifies the file that its children have changed.
     *
     * @param childName The name of the child.
     * @param newType The type of the child.
     * @throws Exception if an error occurs.
     */
    protected void childrenChanged(final FileName childName, final FileType newType) throws Exception {
        // TODO - this may be called when not attached

        if (children != null && childName != null && newType != null) {
            // TODO - figure out if children[] can be replaced by list
            final ArrayList<FileName> list = new ArrayList<>(Arrays.asList(children));
            if (newType.equals(FileType.IMAGINARY)) {
                list.remove(childName);
            } else {
                list.add(childName);
            }
            children = list.toArray(FileName.EMPTY_ARRAY);
        }

        // removeChildrenCache();
        onChildrenChanged(childName, newType);
    }

    /**
     * Closes this file, and its content.
     *
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void close() throws FileSystemException {
        FileSystemException exc = null;

        synchronized (fileSystem) {
            // Close the content
            if (content != null) {
                try {
                    content.close();
                    content = null;
                } catch (final FileSystemException e) {
                    exc = e;
                }
            }

            // Detach from the file
            try {
                detach();
            } catch (final Exception e) {
                exc = new FileSystemException("vfs.provider/close.error", fileName, e);
            }

            if (exc != null) {
                throw exc;
            }
        }
    }

    /**
     * Compares two FileObjects (ignores case).
     *
     * @param file the object to compare.
     * @return a negative integer, zero, or a positive integer when this object is less than, equal to, or greater than
     *         the given object.
     */
    @Override
    public int compareTo(final FileObject file) {
        if (file == null) {
            return 1;
        }
        return this.toString().compareToIgnoreCase(file.toString());
    }

    /**
     * Copies another file to this file.
     *
     * @param file The FileObject to copy.
     * @param selector The FileSelector.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void copyFrom(final FileObject file, final FileSelector selector) throws FileSystemException {
        if (!FileObjectUtils.exists(file)) {
            throw new FileSystemException("vfs.provider/copy-missing-file.error", file);
        }

        // Locate the files to copy across
        final ArrayList<FileObject> files = new ArrayList<>();
        file.findFiles(selector, false, files);

        // Copy everything across
        for (final FileObject srcFile : files) {
            // Determine the destination file
            final String relPath = file.getName().getRelativeName(srcFile.getName());
            final FileObject destFile = resolveFile(relPath, NameScope.DESCENDENT_OR_SELF);

            // Clean up the destination file, if necessary
            if (FileObjectUtils.exists(destFile) && destFile.getType() != srcFile.getType()) {
                // The destination file exists, and is not of the same type,
                // so delete it
                // TODO - add a pluggable policy for deleting and overwriting existing files
                destFile.deleteAll();
            }

            // Copy across
            try {
                if (srcFile.getType().hasContent()) {
                    FileObjectUtils.writeContent(srcFile, destFile);
                } else if (srcFile.getType().hasChildren()) {
                    destFile.createFolder();
                }
            } catch (final IOException e) {
                throw new FileSystemException("vfs.provider/copy-file.error", e, srcFile, destFile);
            }
        }
    }

    /**
     * Creates this file, if it does not exist.
     *
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void createFile() throws FileSystemException {
        synchronized (fileSystem) {
            try {
                // VFS-210: We do not want to trunc any existing file, checking for its existence is
                // still required
                if (exists() && !isFile()) {
                    throw new FileSystemException("vfs.provider/create-file.error", fileName);
                }

                if (!exists()) {
                    try (FileContent content = getContent()) {
                        if (content != null) {
                            try (OutputStream ignored = content.getOutputStream()) {
                                // Avoids NPE on OutputStream#close()
                            }
                        }
                    }
                }
            } catch (final RuntimeException re) {
                throw re;
            } catch (final Exception e) {
                throw new FileSystemException("vfs.provider/create-file.error", fileName, e);
            }
        }
    }

    /**
     * Creates this folder, if it does not exist. Also creates any ancestor files which do not exist.
     *
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void createFolder() throws FileSystemException {
        synchronized (fileSystem) {
            // VFS-210: we create a folder only if it does not already exist. So this check should be safe.
            if (getType().hasChildren()) {
                // Already exists as correct type
                return;
            }
            if (getType() != FileType.IMAGINARY) {
                throw new FileSystemException("vfs.provider/create-folder-mismatched-type.error", fileName);
            }
            /*
             * VFS-210: checking for writable is not always possible as the security constraint might be more complex
             * if (!isWriteable()) { throw new FileSystemException("vfs.provider/create-folder-read-only.error", name);
             * }
             */

            // Traverse up the hierarchy and make sure everything is a folder
            final FileObject parent = getParent();
            if (parent != null) {
                parent.createFolder();
            }

            try {
                // Create the folder
                doCreateFolder();

                // Update cached info
                handleCreate(FileType.FOLDER);
            } catch (final RuntimeException re) {
                throw re;
            } catch (final Exception exc) {
                throw new FileSystemException("vfs.provider/create-folder.error", fileName, exc);
            }
        }
    }

    /**
     * Deletes this file.
     * <p>
     * TODO - This will not fail if this is a non-empty folder.
     * </p>
     *
     * @return true if this object has been deleted
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public boolean delete() throws FileSystemException {
        return delete(Selectors.SELECT_SELF) > 0;
    }

    /**
     * Deletes this file, and all children matching the {@code selector}.
     *
     * @param selector The FileSelector.
     * @return the number of deleted files.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public int delete(final FileSelector selector) throws FileSystemException {
        int nuofDeleted = 0;

        /*
         * VFS-210 if (getType() == FileType.IMAGINARY) { // File does not exist return nuofDeleted; }
         */

        // Locate all the files to delete
        final ArrayList<FileObject> files = new ArrayList<>();
        findFiles(selector, true, files);

        // Delete 'em
        for (final FileObject fileObject : files) {
            final AbstractFileObject file = FileObjectUtils.getAbstractFileObject(fileObject);
            // file.attach();
            // VFS-210: It seems impossible to me that findFiles will return a list with hidden files/directories
            // in it, else it would not be hidden. Checking for the file-type seems ok in this case
            // If the file is a folder, make sure all its children have been deleted
            if (file.getType().hasChildren() && file.getChildren().length != 0) {
                // Skip - as the selector forced us not to delete all files
                continue;
            }
            // Delete the file
            if (file.deleteSelf()) {
                nuofDeleted++;
            }
        }
        return nuofDeleted;
    }

    /**
     * Deletes this file and all children. Shorthand for {@code delete(Selectors.SELECT_ALL)}
     *
     * @return the number of deleted files.
     * @throws FileSystemException if an error occurs.
     * @see #delete(FileSelector)
     * @see Selectors#SELECT_ALL
     */
    @Override
    public int deleteAll() throws FileSystemException {
        return this.delete(Selectors.SELECT_ALL);
    }

    /**
     * Deletes this file, once all its children have been deleted
     *
     * @return true if this file has been deleted
     * @throws FileSystemException if an error occurs.
     */
    private boolean deleteSelf() throws FileSystemException {
        synchronized (fileSystem) {
            // It's possible to delete a read-only file if you have write-execute access to the directory

            /*
             * VFS-210 if (getType() == FileType.IMAGINARY) { // File does not exist return false; }
             */

            boolean confirmed = false;
            try {
                // Delete the file
                doDelete();
                confirmed = true;

                // Update cached info
                handleDelete();
            } catch (final RuntimeException re) {
                if (confirmed) {
                    re.addSuppressed(new ConfirmedRemoval(this));
                }
                throw re;
            } catch (final Exception exc) {
                final FileSystemException failure = new FileSystemException("vfs.provider/delete.error", exc, fileName);
                if (confirmed) {
                    failure.addSuppressed(new ConfirmedRemoval(this));
                }
                throw failure;
            }

            return true;
        }
    }

    /**
     * Detaches this file, invalidating all cached info. This will force a call to {@link #doAttach} next time this file
     * is used.
     *
     * @throws Exception if an error occurs.
     */
    private void detach() throws Exception {
        synchronized (fileSystem) {
            if (attached) {
                try {
                    doDetach();
                } finally {
                    attached = false;
                    setFileType(null);
                    parent = null;

                    // fs.fileDetached(this);

                    removeChildrenCache();
                    // children = null;
                }
            }
        }
    }

    /**
     * Attaches this file object to its file resource.
     * <p>
     * This method is called before any of the doBlah() or onBlah() methods. Sub-classes can use this method to perform
     * lazy initialization.
     * </p>
     * <p>
     * This implementation does nothing.
     * </p>
     *
     * @throws Exception if an error occurs.
     */
    protected void doAttach() throws Exception {
        // noop
    }

    /**
     * Create a FileContent implementation.
     *
     * @return The FileContent.
     * @throws FileSystemException if an error occurs.
     * @since 2.0
     */
    protected FileContent doCreateFileContent() throws FileSystemException {
        return new DefaultFileContent(this, getFileContentInfoFactory());
    }

    /**
     * Creates this file as a folder. Is only called when:
     * <ul>
     * <li>{@link #doGetType} returns {@link FileType#IMAGINARY}.</li>
     * <li>The parent folder exists and is writable, or this file is the root of the file system.</li>
     * </ul>
     * This implementation throws an exception.
     *
     * @throws Exception if an error occurs.
     */
    protected void doCreateFolder() throws Exception {
        throw new FileSystemException("vfs.provider/create-folder-not-supported.error");
    }

    /**
     * Deletes the file. Is only called when:
     * <ul>
     * <li>{@link #doGetType} does not return {@link FileType#IMAGINARY}.</li>
     * <li>{@link #doIsWriteable} returns true.</li>
     * <li>This file has no children, if a folder.</li>
     * </ul>
     * This implementation throws an exception.
     *
     * @throws Exception if an error occurs.
     */
    protected void doDelete() throws Exception {
        throw new FileSystemException("vfs.provider/delete-not-supported.error");
    }

    /**
     * Detaches this file object from its file resource.
     * <p>
     * Called when this file is closed. Note that the file object may be reused later, so should be able to be
     * reattached.
     * </p>
     * <p>
     * This implementation does nothing.
     * </p>
     *
     * @throws Exception if an error occurs.
     */
    protected void doDetach() throws Exception {
        // noop
    }

    /**
     * Returns the attributes of this file. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns an empty map.
     * </p>
     *
     * @return The attributes of the file.
     * @throws Exception if an error occurs.
     */
    protected Map<String, Object> doGetAttributes() throws Exception {
        return Collections.emptyMap();
    }

    /**
     * Returns the certificates used to sign this file. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns null.
     * </p>
     *
     * @return The certificates used to sign the file.
     * @throws Exception if an error occurs.
     */
    protected Certificate[] doGetCertificates() throws Exception {
        return null;
    }

    /**
     * Returns the size of the file content (in bytes). Is only called if {@link #doGetType} returns
     * {@link FileType#FILE}.
     *
     * @return The size of the file in bytes.
     * @throws Exception if an error occurs.
     */
    protected abstract long doGetContentSize() throws Exception;

    /**
     * Creates an input stream to read the file content from. Is only called if {@link #doGetType} returns
     * {@link FileType#FILE}.
     * <p>
     * It is guaranteed that there are no open output streams for this file when this method is called.
     * </p>
     * <p>
     * The returned stream does not have to be buffered.
     * </p>
     *
     * @return An InputStream to read the file content.
     * @throws Exception if an error occurs.
     */
    protected InputStream doGetInputStream() throws Exception {
        // Backward compatibility.
        return doGetInputStream(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates an input stream to read the file content from. Is only called if {@link #doGetType} returns
     * {@link FileType#FILE}.
     * <p>
     * It is guaranteed that there are no open output streams for this file when this method is called.
     * </p>
     * <p>
     * The returned stream does not have to be buffered.
     * </p>
     * @param bufferSize Buffer size hint.
     * @return An InputStream to read the file content.
     * @throws Exception if an error occurs.
     */
    protected InputStream doGetInputStream(final int bufferSize) throws Exception {
        throw new UnsupportedOperationException(DO_GET_INPUT_STREAM_INT);
    }

    /**
     * Returns the last modified time of this file. Is only called if {@link #doGetType} does not return
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @return The last modification time.
     * @throws Exception if an error occurs.
     */
    protected long doGetLastModifiedTime() throws Exception {
        throw new FileSystemException("vfs.provider/get-last-modified-not-supported.error");
    }

    /**
     * Creates an output stream to write the file content to. Is only called if:
     * <ul>
     * <li>{@link #doIsWriteable} returns true.
     * <li>{@link #doGetType} returns {@link FileType#FILE}, or {@link #doGetType} returns {@link FileType#IMAGINARY},
     * and the file's parent exists and is a folder.
     * </ul>
     * It is guaranteed that there are no open stream (input or output) for this file when this method is called.
     * <p>
     * The returned stream does not have to be buffered.
     * </p>
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @param bAppend true if the file should be appended to, false if it should be overwritten.
     * @return An OutputStream to write to the file.
     * @throws Exception if an error occurs.
     */
    protected OutputStream doGetOutputStream(final boolean bAppend) throws Exception {
        throw new FileSystemException("vfs.provider/write-not-supported.error");
    }

    /**
     * Creates access to the file for random i/o. Is only called if {@link #doGetType} returns {@link FileType#FILE}.
     * <p>
     * It is guaranteed that there are no open output streams for this file when this method is called.
     * </p>
     *
     * @param mode The mode to access the file.
     * @return The RandomAccessContext.
     * @throws Exception if an error occurs.
     */
    protected RandomAccessContent doGetRandomAccessContent(final RandomAccessMode mode) throws Exception {
        throw new FileSystemException("vfs.provider/random-access-not-supported.error");
    }

    /**
     * Determines the type of this file. Must not return null. The return value of this method is cached, so the
     * implementation can be expensive.
     *
     * @return the type of the file.
     * @throws Exception if an error occurs.
     */
    protected abstract FileType doGetType() throws Exception;

    /**
     * Determines if this file is executable. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns false.
     * </p>
     *
     * @return true if the file is executable, false otherwise.
     * @throws Exception if an error occurs.
     */
    protected boolean doIsExecutable() throws Exception {
        return false;
    }

    /**
     * Determines if this file is hidden. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns false.
     * </p>
     *
     * @return true if the file is hidden, false otherwise.
     * @throws Exception if an error occurs.
     */
    protected boolean doIsHidden() throws Exception {
        return false;
    }

    /**
     * Determines if this file can be read. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns true.
     * </p>
     *
     * @return true if the file is readable, false otherwise.
     * @throws Exception if an error occurs.
     */
    protected boolean doIsReadable() throws Exception {
        return true;
    }

    /**
     * Checks if this fileObject is the same file as {@code destFile} just with a different name. E.g. for
     * case-insensitive file systems like Windows.
     *
     * @param destFile The file to compare to.
     * @return true if the FileObjects are the same.
     * @throws FileSystemException if an error occurs.
     */
    protected boolean doIsSameFile(final FileObject destFile) throws FileSystemException {
        return false;
    }

    /**
     * Determines if this file is a symbolic link. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns false.
     * </p>
     *
     * @return true if the file is readable, false otherwise.
     * @throws Exception if an error occurs.
     * @since 2.4
     */
    protected boolean doIsSymbolicLink() throws Exception {
        return false;
    }

    /**
     * Determines if this file can be written to. Is only called if {@link #doGetType} does not return
     * {@link FileType#IMAGINARY}.
     * <p>
     * This implementation always returns true.
     * </p>
     *
     * @return true if the file is writable.
     * @throws Exception if an error occurs.
     */
    protected boolean doIsWriteable() throws Exception {
        return true;
    }

    /**
     * Lists the children of this file. Is only called if {@link #doGetType} returns {@link FileType#FOLDER}. The return
     * value of this method is cached, so the implementation can be expensive.
     *
     * @return a possible empty String array if the file is a directory or null or an exception if the file is not a
     *         directory or can't be read.
     * @throws Exception if an error occurs.
     */
    protected abstract String[] doListChildren() throws Exception;

    /**
     * Lists the children of this file.
     * <p>
     * Is only called if {@link #doGetType} returns {@link FileType#FOLDER}.
     * </p>
     * <p>
     * The return value of this method is cached, so the implementation can be expensive.
     * Other than {@code doListChildren} you could return FileObject's to e.g. reinitialize the type of the file.
     * </p>
     * <p>
     * (Introduced for WebDAV: "permission denied on resource" during getType())
     * </p>
     *
     * @return The children of this FileObject.
     * @throws Exception if an error occurs.
     */
    protected FileObject[] doListChildrenResolved() throws Exception {
        return null;
    }

    /**
     * Removes an attribute of this file.
     * <p>
     * Is only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @param attrName The name of the attribute to remove.
     * @throws Exception if an error occurs.
     * @since 2.0
     */
    protected void doRemoveAttribute(final String attrName) throws Exception {
        throw new FileSystemException("vfs.provider/remove-attribute-not-supported.error");
    }

    /**
     * Renames the file.
     * <p>
     * Is only called when:
     * </p>
     * <ul>
     * <li>{@link #doIsWriteable} returns true.</li>
     * </ul>
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @param newFile A FileObject with the new file name.
     * @throws Exception if an error occurs.
     */
    protected void doRename(final FileObject newFile) throws Exception {
        throw new FileSystemException("vfs.provider/rename-not-supported.error");
    }

    /**
     * Sets an attribute of this file.
     * <p>
     * Is only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @param attrName The attribute name.
     * @param value The value to be associated with the attribute name.
     * @throws Exception if an error occurs.
     */
    protected void doSetAttribute(final String attrName, final Object value) throws Exception {
        throw new FileSystemException("vfs.provider/set-attribute-not-supported.error");
    }

    /**
     * Make the file executable.
     * <p>
     * Only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     * <p>
     * This implementation returns false.
     * </p>
     *
     * @param executable True to allow access, false to disallow.
     * @param ownerOnly If {@code true}, the permission applies only to the owner; otherwise, it applies to everybody.
     * @return true if the operation succeeded.
     * @throws Exception Any Exception thrown is wrapped in FileSystemException.
     * @see #setExecutable(boolean, boolean)
     * @since 2.1
     */
    protected boolean doSetExecutable(final boolean executable, final boolean ownerOnly) throws Exception {
        return false;
    }

    /**
     * Sets the last modified time of this file.
     * <p>
     * Is only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     * <p>
     * This implementation throws an exception.
     * </p>
     *
     * @param modtime The last modification time.
     * @return true if the time was set.
     * @throws Exception Any Exception thrown is wrapped in FileSystemException.
     */
    protected boolean doSetLastModifiedTime(final long modtime) throws Exception {
        throw new FileSystemException("vfs.provider/set-last-modified-not-supported.error");
    }

    /**
     * Make the file or folder readable.
     * <p>
     * Only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     * <p>
     * This implementation returns false.
     * </p>
     *
     * @param readable True to allow access, false to disallow
     * @param ownerOnly If {@code true}, the permission applies only to the owner; otherwise, it applies to everybody.
     * @return true if the operation succeeded
     * @throws Exception Any Exception thrown is wrapped in FileSystemException.
     * @see #setReadable(boolean, boolean)
     * @since 2.1
     */
    protected boolean doSetReadable(final boolean readable, final boolean ownerOnly) throws Exception {
        return false;
    }

    /**
     * Make the file or folder writable.
     * <p>
     * Only called if {@link #doGetType} does not return {@link FileType#IMAGINARY}.
     * </p>
     *
     * @param writable True to allow access, false to disallow
     * @param ownerOnly If {@code true}, the permission applies only to the owner; otherwise, it applies to everybody.
     * @return true if the operation succeeded
     * @throws Exception Any Exception thrown is wrapped in FileSystemException.
     * @see #setWritable(boolean, boolean)
     * @since 2.1
     */
    protected boolean doSetWritable(final boolean writable, final boolean ownerOnly) throws Exception {
        return false;
    }

    /**
     * Called when the output stream for this file is closed.
     *
     * @throws Exception if an error occurs.
     */
    protected void endOutput() throws Exception {
        if (getType() == FileType.IMAGINARY) {
            // File was created
            handleCreate(FileType.FILE);
        } else {
            // File has changed
            onChange();
        }
    }

    /**
     * Determines if the file exists.
     *
     * @return true if the file exists, false otherwise,
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public boolean exists() throws FileSystemException {
        return getType() != FileType.IMAGINARY;
    }

    private FileName[] extractNames(final FileObject[] objects) {
        if (objects == null) {
            return null;
        }
        return Stream.of(objects).filter(Objects::nonNull).map(FileObject::getName).toArray(FileName[]::new);
    }

    @Override
    protected void finalize() throws Throwable {
        fileSystem.fileObjectDestroyed(this);

        super.finalize();
    }

    /**
     * Finds the set of matching descendants of this file, in depthwise order.
     *
     * @param selector The FileSelector.
     * @return list of files or null if the base file (this object) do not exist
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject[] findFiles(final FileSelector selector) throws FileSystemException {
        final List<FileObject> list = this.listFiles(selector);
        return list == null ? null : list.toArray(EMPTY_ARRAY);
    }

    /**
     * Traverses the descendants of this file, and builds a list of selected files.
     *
     * @param selector The FileSelector.
     * @param depthwise if true files are added after their descendants, before otherwise.
     * @param selected A List of the located FileObjects.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void findFiles(final FileSelector selector, final boolean depthwise, final List<FileObject> selected)
            throws FileSystemException {
        try {
            if (exists()) {
                // Traverse starting at this file
                final DefaultFileSelectorInfo info = new DefaultFileSelectorInfo();
                info.setBaseFolder(this);
                info.setDepth(0);
                info.setFile(this);
                traverse(info, selector, depthwise, selected);
            }
        } catch (final Exception e) {
            throw new FileSystemException("vfs.provider/find-files.error", fileName, e);
        }
    }

    /**
     * Returns the file system this file belongs to.
     *
     * @return The FileSystem this file is associated with.
     */
    protected AFS getAbstractFileSystem() {
        return fileSystem;
    }

    /**
     * Returns a child of this file.
     *
     * @param name The name of the child to locate.
     * @return The FileObject for the file or null if the child does not exist.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject getChild(final String name) throws FileSystemException {
        // TODO - use a hashtable when there are a large number of children
        final FileObject[] children = getChildren();
        for (final FileObject element : children) {
            final FileName child = element.getName();
            final String childBaseName = child.getBaseName();
            // TODO - use a comparator to compare names
            if (childBaseName.equals(name) || UriParser.decode(childBaseName).equals(name)) {
                return resolveFile(child);
            }
        }
        return null;
    }

    /**
     * Returns the children of the file.
     *
     * @return an array of FileObjects, one per child.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject[] getChildren() throws FileSystemException {
        synchronized (fileSystem) {
            // VFS-210
            if (!fileSystem.hasCapability(Capability.LIST_CHILDREN)) {
                throw new FileNotFolderException(fileName);
            }

            /*
             * VFS-210 if (!getType().hasChildren()) { throw new
             * FileSystemException("vfs.provider/list-children-not-folder.error", name); }
             */
            attach();

            // Use cached info, if present
            if (children != null) {
                return resolveFiles(children);
            }

            // allow the filesystem to return resolved children. e.g. prefill type for webdav
            final FileObject[] childrenObjects;
            try {
                childrenObjects = doListChildrenResolved();
                children = extractNames(childrenObjects);
            } catch (final FileSystemException exc) {
                // VFS-210
                throw exc;
            } catch (final Exception exc) {
                throw new FileSystemException("vfs.provider/list-children.error", exc, fileName);
            }

            if (childrenObjects != null) {
                return childrenObjects;
            }

            // List the children
            final String[] files;
            try {
                files = doListChildren();
            } catch (final FileSystemException exc) {
                // VFS-210
                throw exc;
            } catch (final Exception exc) {
                throw new FileSystemException("vfs.provider/list-children.error", exc, fileName);
            }

            if (files == null) {
                // VFS-210
                // honor the new doListChildren contract
                // return null;
                throw new FileNotFolderException(fileName);
            }
            if (files.length == 0) {
                // No children
                children = FileName.EMPTY_ARRAY;
            } else {
                // Create file objects for the children
                final FileName[] cache = new FileName[files.length];
                for (int i = 0; i < files.length; i++) {
                    final String file = "./" + files[i]; // VFS-741: assume scheme prefix is file name only
                    cache[i] = fileSystem.getFileSystemManager().resolveName(fileName, file, NameScope.CHILD);
                }
                // VFS-285: only assign the children file names after all of them have been
                // resolved successfully to prevent an inconsistent internal state
                children = cache;
            }

            return resolveFiles(children);
        }
    }

    /**
     * Returns the file's content.
     *
     * @return the FileContent for this FileObject.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileContent getContent() throws FileSystemException {
        synchronized (fileSystem) {
            attach();
            if (content == null) {
                content = doCreateFileContent();
            }
            return content;
        }
    }

    /**
     * Creates the FileContentInfo factory.
     *
     * @return The FileContentInfoFactory.
     */
    protected FileContentInfoFactory getFileContentInfoFactory() {
        return fileSystem.getFileSystemManager().getFileContentInfoFactory();
    }

    /**
     * @return FileOperations interface that provides access to the operations API.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileOperations getFileOperations() throws FileSystemException {
        if (operations == null) {
            operations = new DefaultFileOperations(this);
        }

        return operations;
    }

    /**
     * Returns the file system this file belongs to.
     *
     * @return The FileSystem this file is associated with.
     */
    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    /**
     * Returns an input stream to use to read the content of the file.
     *
     * @return The InputStream to access this file's content.
     * @throws FileSystemException if an error occurs.
     */
    public InputStream getInputStream() throws FileSystemException {
        return getInputStream(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Returns an input stream to use to read the content of the file.
     *
     * @param bufferSize buffer size hint.
     * @return The InputStream to access this file's content.
     * @throws FileSystemException if an error occurs.
     */
    public InputStream getInputStream(final int bufferSize) throws FileSystemException {
        // Get the raw input stream
        try {
            return doGetInputStream(bufferSize);
        } catch (final org.wso2.org.apache.commons.vfs2.FileNotFoundException | FileNotFoundException exc) {
            throw new org.wso2.org.apache.commons.vfs2.FileNotFoundException(fileName, exc);
        } catch (final FileSystemException exc) {
            throw exc;
        } catch (final UnsupportedOperationException uoe) {
            // TODO Remove for 3.0
            // Backward compatibility for subclasses before 2.5.0
            if (DO_GET_INPUT_STREAM_INT.equals(uoe.getMessage())) {
                try {
                    // Invoke old API.
                    return doGetInputStream();
                } catch (final Exception e) {
                    if (e instanceof FileSystemException) {
                        throw (FileSystemException) e;
                    }
                    throw new FileSystemException("vfs.provider/read.error", fileName, e);
                }
            }
            throw uoe;
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/read.error", fileName, exc);
        }
    }

    /**
     * Returns the name of the file.
     *
     * @return The FileName, never {@code null}.
     */
    @Override
    public FileName getName() {
        return fileName;
    }

    // TODO: remove this method for the next major version as it is unused
    /**
     * Prepares this file for writing. Makes sure it is either a file, or its parent folder exists. Returns an output
     * stream to use to write the content of the file to.
     *
     * @return An OutputStream where the new contents of the file can be written.
     * @throws FileSystemException if an error occurs.
     */
    public OutputStream getOutputStream() throws FileSystemException {
        return getOutputStream(false);
    }

    // TODO: mark this method as `final` and package-private for the next major version because
    // it shouldn't be used from anywhere other than `DefaultFileContent`
    /**
     * Prepares this file for writing. Makes sure it is either a file, or its parent folder exists. Returns an output
     * stream to use to write the content of the file to.
     *
     * @param bAppend true when append to the file.
     *            Note: If the underlying file system does not support appending, a FileSystemException is thrown.
     * @return An OutputStream where the new contents of the file can be written.
     * @throws FileSystemException if an error occurs; for example:
     *             bAppend is true, and the underlying FileSystem does not support it
     */
    public OutputStream getOutputStream(final boolean bAppend) throws FileSystemException {
        /*
         * VFS-210 if (getType() != FileType.IMAGINARY && !getType().hasContent()) { throw new
         * FileSystemException("vfs.provider/write-not-file.error", name); } if (!isWriteable()) { throw new
         * FileSystemException("vfs.provider/write-read-only.error", name); }
         */

        if (bAppend && !fileSystem.hasCapability(Capability.APPEND_CONTENT)) {
            throw new FileSystemException("vfs.provider/write-append-not-supported.error", fileName);
        }

        if (getType() == FileType.IMAGINARY) {
            // Does not exist - make sure parent does
            final FileObject parent = getParent();
            if (parent != null) {
                parent.createFolder();
            }
        }

        // Get the raw output stream
        try {
            return doGetOutputStream(bAppend);
        } catch (final RuntimeException re) {
            throw re;
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/write.error", exc, fileName);
        }
    }

    /**
     * Returns the parent of the file.
     *
     * @return the parent FileObject.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject getParent() throws FileSystemException {
        // equals is not implemented :-/
        if (this.compareTo(fileSystem.getRoot()) == 0) {
            if (fileSystem.getParentLayer() == null) {
                // Root file has no parent
                return null;
            }
            // Return the parent of the parent layer
            return fileSystem.getParentLayer().getParent();
        }

        synchronized (fileSystem) {
            // Locate the parent of this file
            if (parent == null) {
                final FileName name = fileName.getParent();
                if (name == null) {
                    return null;
                }
                parent = fileSystem.resolveFile(name);
            }
            return parent;
        }
    }

    /**
     * Returns the receiver as a URI String for public display, like, without a password.
     *
     * @return A URI String without a password, never {@code null}.
     */
    @Override
    public String getPublicURIString() {
        return fileName.getFriendlyURI();
    }

    /**
     * Returns an input/output stream to use to read and write the content of the file in and random manner.
     *
     * @param mode The RandomAccessMode.
     * @return The RandomAccessContent.
     * @throws FileSystemException if an error occurs.
     */
    public RandomAccessContent getRandomAccessContent(final RandomAccessMode mode) throws FileSystemException {
        //
        // VFS-210 if (!getType().hasContent()) { throw new FileSystemException("vfs.provider/read-not-file.error",
        // name); }
        //
        if (mode.requestRead()) {
            if (!fileSystem.hasCapability(Capability.RANDOM_ACCESS_READ)) {
                throw new FileSystemException("vfs.provider/random-access-read-not-supported.error");
            }
            if (!isReadable()) {
                throw new FileSystemException("vfs.provider/read-not-readable.error", fileName);
            }
        }

        if (mode.requestWrite()) {
            if (!fileSystem.hasCapability(Capability.RANDOM_ACCESS_WRITE)) {
                throw new FileSystemException("vfs.provider/random-access-write-not-supported.error");
            }
            if (!isWriteable()) {
                throw new FileSystemException("vfs.provider/write-read-only.error", fileName);
            }
        }

        // Get the raw input stream
        try {
            return doGetRandomAccessContent(mode);
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/random-access.error", fileName, exc);
        }
    }

    /**
     * Returns the file's type.
     *
     * @return The FileType.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileType getType() throws FileSystemException {
        synchronized (fileSystem) {
            attach();

            // VFS-210: get the type only if requested for
            try {
                if (type == null) {
                    setFileType(doGetType());
                }
                if (type == null) {
                    setFileType(FileType.IMAGINARY);
                }
            } catch (final Exception e) {
                throw new FileSystemException("vfs.provider/get-type.error", e, fileName);
            }

            return type;
        }
    }

    /**
     * Returns a URL representation of the file.
     *
     * @return The URL representation of the file.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public URL getURL() throws FileSystemException {
        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<URL>) () -> {
                final StringBuilder buf = new StringBuilder();
                final String scheme = UriParser.extractScheme(fileSystem.getContext().getFileSystemManager().getSchemes(), fileName.getURI(), buf);
                return new URL(scheme, "", -1, buf.toString(),
                        new DefaultURLStreamHandler(fileSystem.getContext(), fileSystem.getFileSystemOptions()));
            });
        } catch (final PrivilegedActionException e) {
            throw new FileSystemException("vfs.provider/get-url.error", fileName, e.getException());
        }
    }

    /**
     * Called when this file is changed.
     * <p>
     * This will only happen if you monitor the file using {@link FileMonitor}.
     * </p>
     *
     * @throws Exception if an error occurs.
     */
    protected void handleChanged() throws Exception {
        // Notify the file system
        fileSystem.fireFileChanged(this);
    }

    /**
     * Called when this file is created. Updates cached info and notifies the parent and file system.
     *
     * @param newType The type of the file.
     * @throws Exception if an error occurs.
     */
    protected void handleCreate(final FileType newType) throws Exception {
        synchronized (fileSystem) {
            if (attached) {
                // Fix up state
                injectType(newType);

                removeChildrenCache();

                // Notify subclass
                onChange();
            }

            // Notify parent that its child list may no longer be valid
            notifyParent(this.getName(), newType);

            // Notify the file system
            fileSystem.fireFileCreated(this);
        }
    }

    /**
     * Called when this file is deleted. Updates cached info and notifies subclasses, parent and file system.
     *
     * @throws Exception if an error occurs.
     */
    protected void handleDelete() throws Exception {
        synchronized (fileSystem) {
            if (attached) {
                // Fix up state
                injectType(FileType.IMAGINARY);
                removeChildrenCache();

                // Notify subclass
                onChange();
            }

            // Notify parent that its child list may no longer be valid
            notifyParent(this.getName(), FileType.IMAGINARY);

            // Notify the file system
            fileSystem.fireFileDeleted(this);
        }
    }

    /**
     * This method is meant to add an object where this object holds a strong reference then. E.g. an archive-file system
     * creates a list of all children and they shouldn't get garbage collected until the container is garbage collected
     *
     * @param strongRef The Object to add.
     */
    // TODO should this be a FileObject?
    public void holdObject(final Object strongRef) {
        if (objects == null) {
            objects = new ArrayList<>(INITIAL_LIST_SIZE);
        }
        objects.add(strongRef);
    }

    /**
     * Sets the file type.
     *
     * @param fileType the file type.
     */
    protected void injectType(final FileType fileType) {
        setFileType(fileType);
    }

    /**
     * Check if the internal state is "attached".
     *
     * @return true if this is the case
     */
    @Override
    public boolean isAttached() {
        return attached;
    }

    /**
     * Check if the content stream is open.
     *
     * @return true if this is the case
     */
    @Override
    public boolean isContentOpen() {
        if (content == null) {
            return false;
        }

        return content.isOpen();
    }

    /**
     * Determines if this file is executable.
     *
     * @return {@code true} if this file is executable, {@code false} if not.
     * @throws FileSystemException On error determining if this file exists.
     */
    @Override
    public boolean isExecutable() throws FileSystemException {
        try {
            return exists() && doIsExecutable();
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/check-is-executable.error", fileName, exc);
        }
    }

    /**
     * Checks if this file is a regular file by using its file type.
     *
     * @return true if this file is a regular file.
     * @throws FileSystemException if an error occurs.
     * @see #getType()
     * @see FileType#FILE
     */
    @Override
    public boolean isFile() throws FileSystemException {
        // Use equals instead of == to avoid any class loader worries.
        return FileType.FILE.equals(this.getType());
    }

    /**
     * Checks if this file is a folder by using its file type.
     *
     * @return true if this file is a regular file.
     * @throws FileSystemException if an error occurs.
     * @see #getType()
     * @see FileType#FOLDER
     */
    @Override
    public boolean isFolder() throws FileSystemException {
        // Use equals instead of == to avoid any class loader worries.
        return FileType.FOLDER.equals(this.getType());
    }

    /**
     * Determines if this file can be read.
     *
     * @return true if the file is a hidden file, false otherwise.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public boolean isHidden() throws FileSystemException {
        try {
            return exists() && doIsHidden();
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/check-is-hidden.error", fileName, exc);
        }
    }

    /**
     * Determines if this file can be read.
     *
     * @return true if the file can be read, false otherwise.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public boolean isReadable() throws FileSystemException {
        try {
            return exists() && doIsReadable();
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/check-is-readable.error", fileName, exc);
        }
    }

    /**
     * Checks if this fileObject is the same file as {@code destFile} just with a different name. E.g. for
     * case-insensitive file systems like windows.
     *
     * @param destFile The file to compare to.
     * @return true if the FileObjects are the same.
     * @throws FileSystemException if an error occurs.
     */
    protected boolean isSameFile(final FileObject destFile) throws FileSystemException {
        attach();
        return doIsSameFile(destFile);
    }

    /**
     * Determines if this file can be read.
     *
     * @return true if the file can be read, false otherwise.
     * @throws FileSystemException if an error occurs.
     * @since 2.4
     */
    @Override
    public boolean isSymbolicLink() throws FileSystemException {
        try {
            return exists() && doIsSymbolicLink();
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/check-is-symbolic-link.error", fileName, exc);
        }
    }

    /**
     * Determines if this file can be written to.
     *
     * @return true if the file can be written to, false otherwise.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public boolean isWriteable() throws FileSystemException {
        try {
            if (exists()) {
                return doIsWriteable();
            }
            final FileObject parent = getParent();
            if (parent != null) {
                return parent.isWriteable();
            }
            return true;
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/check-is-writable.error", fileName, exc);
        }
    }

    /**
     * Returns an iterator over a set of all FileObject in this file object.
     *
     * @return an Iterator.
     */
    @Override
    public Iterator<FileObject> iterator() {
        try {
            return listFiles(Selectors.SELECT_ALL).iterator();
        } catch (final FileSystemException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Lists the set of matching descendants of this file, in depthwise order.
     *
     * @param selector The FileSelector.
     * @return list of files or null if the base file (this object) do not exist or the {@code selector} is null
     * @throws FileSystemException if an error occurs.
     */
    public List<FileObject> listFiles(final FileSelector selector) throws FileSystemException {
        if (!exists() || selector == null) {
            return null;
        }

        final ArrayList<FileObject> list = new ArrayList<>();
        this.findFiles(selector, true, list);
        return list;
    }

    /**
     * Moves (rename) the file to another one.
     *
     * @param destFile The target FileObject.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void moveTo(final FileObject destFile) throws FileSystemException {
        new Move(destFile, new MoveOptions(true, null, null, null, null), true).run();
    }

    public void moveTo(final FileObject target, final FileObject tempFile) throws FileSystemException {
        moveTo(target, new MoveOptions(true, tempFile, null, null, null));
    }

    public void moveTo(final FileObject target, final MoveOptions options) throws FileSystemException {
        new Move(target, options, false).run();
    }

    private static final class ConfirmedRemoval extends Exception {
        private static final long serialVersionUID = 1L;
        private final transient FileObject entry;

        ConfirmedRemoval(final FileObject entry) {
            super("The provider confirmed removal before the notification failed", null, false, false);
            this.entry = entry;
        }
    }

    static boolean confirmedRemoval(final Throwable failure, final FileObject target) throws FileSystemException {
        for (final Throwable detail : failure.getSuppressed()) {
            if (detail instanceof ConfirmedRemoval
                    && ((ConfirmedRemoval) detail).entry == FileObjectUtils.getAbstractFileObject(target)) {
                return true;
            }
        }
        return false;
    }

    protected enum EntryRelation { SAME, DISTINCT, UNKNOWN }

    protected EntryRelation entryRelation(final FileObject other) throws FileSystemException {
        return isSameEntry(other) ? EntryRelation.SAME : EntryRelation.DISTINCT;
    }

    protected final EntryRelation remoteEntry(final FileObject other, final boolean caseUncertain,
            final boolean sameSettings) throws FileSystemException {
        final String path = getName().getPathDecoded();
        final String otherPath = other.getName().getPathDecoded();
        final boolean exact = path.equals(otherPath);
        if (!exact && !(caseUncertain && path.equalsIgnoreCase(otherPath))) {
            return EntryRelation.DISTINCT;
        }
        if (getFileSystem() == other.getFileSystem()) {
            return exact ? EntryRelation.SAME : EntryRelation.UNKNOWN;
        }
        final FileName left = getFileSystem().getRootName();
        final FileName right = other.getFileSystem().getRootName();
        if (!(left instanceof GenericFileName) || !(right instanceof GenericFileName)
                || !left.getScheme().equals(right.getScheme())) {
            return EntryRelation.DISTINCT;
        }
        final GenericFileName first = (GenericFileName) left;
        final GenericFileName second = (GenericFileName) right;
        if (!NumericHost.same(first.getHostName(), second.getHostName()) || first.getPort() != second.getPort()
                || !first.getPathDecoded().equals(second.getPathDecoded())) {
            return EntryRelation.DISTINCT;
        }
        if (!Objects.equals(first.getUserName(), second.getUserName())) {
            return EntryRelation.UNKNOWN;
        }
        return exact && sameSettings ? EntryRelation.SAME : EntryRelation.UNKNOWN;
    }

    /** FTP and SFTP start relative names at the login directory or at the server root selected by the provider. */
    protected final EntryRelation workingDirectoryEntry(final FileObject other, final boolean loginRoot,
            final boolean otherLoginRoot, final boolean sameSettings) throws FileSystemException {
        final GenericFileName first = (GenericFileName) getFileSystem().getRootName();
        final GenericFileName second = (GenericFileName) other.getFileSystem().getRootName();
        if (!first.getScheme().equals(second.getScheme()) || first.getPort() != second.getPort()
                || !NumericHost.same(first.getHostName(), second.getHostName())) {
            return EntryRelation.DISTINCT;
        }
        final String left = workingPath(this, loginRoot);
        final String right = workingPath(other, otherLoginRoot);
        if (getFileSystem() == other.getFileSystem()
                || (loginRoot == otherLoginRoot && sameSettings
                        && Objects.equals(first.getUserName(), second.getUserName()))) {
            return left.equals(right) ? EntryRelation.SAME : EntryRelation.DISTINCT;
        }
        // An unknown directory prefix can align only equal names or a suffix of complete path components.
        if (left.equals("/") || right.equals("/") || left.endsWith(right) || right.endsWith(left)) {
            return EntryRelation.UNKNOWN;
        }
        return EntryRelation.DISTINCT;
    }

    private static String workingPath(final FileObject file, final boolean loginRoot) throws FileSystemException {
        String path = file.getName().getPathDecoded();
        if (loginRoot) {
            final String relative = file.getFileSystem().getRootName().getRelativeName(file.getName());
            path = ".".equals(relative) ? "/" : "/" + UriParser.decode(relative);
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    final FileType observedType() {
        synchronized (fileSystem) {
            return type;
        }
    }

    protected boolean isOtherHardLink(final FileObject target) throws FileSystemException {
        return false;
    }

    protected boolean isSameEntry(final FileObject other) throws FileSystemException {
        return getFileSystem() == other.getFileSystem() && getName().getPath().equals(other.getName().getPath());
    }

    protected boolean isUnsafeTempFor(final FileObject source, final FileObject target) throws FileSystemException {
        return entryRelation(source) != EntryRelation.DISTINCT || entryRelation(target) != EntryRelation.DISTINCT;
    }

    /** The state reported to the caller belongs to one attempt. */
    private final class Move {
        private final FileObject target;
        private final MoveOptions options;
        private final boolean legacy;
        private final String sourceKey = CommitStep.pointKey(AbstractFileObject.this);
        private MovePhase phase = MovePhase.RENAME;
        private String tempPath;
        private FileObject temp;
        private boolean oldTargetDeleted;

        Move(final FileObject target, final MoveOptions options, final boolean legacy) {
            this.legacy = legacy;
            this.target = target;
            this.options = options;
            temp = options.getTempFile();
        }

        void run() throws FileSystemException {
            try {
                final boolean rename = canRenameTo(target);
                phase = rename ? MovePhase.RENAME : MovePhase.COPY;
                if (rename) {
                    final FileObject parent = getParent();
                    if (parent != null && !parent.isWriteable()) {
                        throw new FileSystemException("vfs.provider/rename-parent-read-only.error", getName(),
                                parent.getName());
                    }
                } else if (!isWriteable()) {
                    throw new FileSystemException("vfs.provider/rename-read-only.error", getName());
                }
                final FileType type = getType();
                final EntryRelation relation = entryRelation(target);
                if (relation == EntryRelation.SAME) {
                    if (!AtomicOps.existsNow(AbstractFileObject.this)) {
                        throw failed(new AtomicResult(AtomicOutcome.GONE, null));
                    }
                    if (!options.isReplaceExisting()) {
                        throw failed(new AtomicResult(AtomicOutcome.EXISTS, null));
                    }
                    return;
                }
                if (!legacy && (AtomicOps.of(target) == null || (rename && AtomicOps.of(AbstractFileObject.this) == null))) {
                    throw failed(new AtomicResult(AtomicOutcome.UNSUPPORTED, null));
                }
                if (temp != null && (temp.getFileSystem() != target.getFileSystem() || AtomicOps.of(temp) == null
                        || FileObjectUtils.getAbstractFileObject(temp).isUnsafeTempFor(AbstractFileObject.this, target))) {
                    throw failed(new AtomicResult(AtomicOutcome.REFUSED, "Invalid temp file"));
                }
                if (rename && options.isReplaceExisting() && type == FileType.FILE && isOtherHardLink(target)) {
                    deleteSource(type, true);
                    return;
                }
                if (rename && rename(type, relation)) {
                    return;
                }
                phase = MovePhase.COPY;
                if (relation == EntryRelation.UNKNOWN) {
                    throw failed(new AtomicResult(AtomicOutcome.REFUSED, "Cannot distinguish overlapping entries"));
                }
                if (legacy && AtomicOps.of(target) == null) {
                    target.copyFrom(AbstractFileObject.this, Selectors.SELECT_SELF);
                    if (target.getUpdateLastModified() && fileSystem.hasCapability(Capability.GET_LAST_MODIFIED)
                            && target.getFileSystem().hasCapability(type == FileType.FOLDER
                                    ? Capability.SET_LAST_MODIFIED_FOLDER : Capability.SET_LAST_MODIFIED_FILE)) {
                        target.getContent().setLastModifiedTime(getContent().getLastModifiedTime());
                    }
                } else if (type == FileType.FOLDER) {
                    if (!options.isReplaceExisting() && AtomicOps.existsNow(target)) {
                        throw failed(new AtomicResult(AtomicOutcome.EXISTS, null));
                    }
                    target.createFolder();
                    setCopiedTime(target, Capability.SET_LAST_MODIFIED_FOLDER);
                } else {
                    copy();
                }
                deleteSource(type, false);
            } catch (final MoveFailedException e) {
                throw e;
            } catch (final Exception e) {
                oldTargetDeleted |= confirmedRemoval(e, target);
                final Fault fault = Faults.of(e);
                if (fault == Fault.TRANSPORT) {
                    throw CommitStep.unclear(e, phase, tempPath, oldTargetDeleted, "move", AbstractFileObject.this);
                }
                throw failure(e, fault, false, false);
            }
        }

        private boolean rename(final FileType type, final EntryRelation relation) throws Exception {
            final AtomicOps ops = AtomicOps.of(AbstractFileObject.this);
            TestPoints.hold("move.before-rename", sourceKey);
            if (ops == null) {
                if (!options.isReplaceExisting()) {
                    throw failed(new AtomicResult(AtomicOutcome.UNSUPPORTED, null));
                }
                doRename(target);
                events(type);
                return true;
            }
            AtomicResult result;
            if (options.isReplaceExisting()) {
                result = CommitStep.replace(ops, tempFile(), target, options.commitOptions());
                if (fallback(result)) {
                    if (relation == EntryRelation.UNKNOWN) {
                        return false;
                    }
                    if (AtomicOps.existsNow(target)) {
                        if (!CommitStep.deleteObservedTarget(target)) {
                            throw failed(new AtomicResult(AtomicOutcome.REFUSED, "Target delete returned false"));
                        }
                        oldTargetDeleted = true;
                        TestPoints.hold("move.after-target-delete", sourceKey);
                    }
                    result = ops.takeAway(target);
                } else if (result.getOutcome() == AtomicOutcome.DONE && !result.isConnectionLost()) {
                    TestPoints.hold("move.after-rename-done", sourceKey);
                }
            } else {
                if (type != FileType.FOLDER) {
                    result = ops.linkExclusive(target, options.probeName(target, tempFile()));
                    if (result.isConnectionLost()) {
                        if (result.getOutcome() == AtomicOutcome.DONE) {
                            throw lostAfterPublication(result);
                        }
                        throw failed(result);
                    }
                    if (result.getOutcome() == AtomicOutcome.DONE) {
                        phase = MovePhase.DELETE_SOURCE;
                        TestPoints.hold("move.after-link", sourceKey);
                        deleteSource(type, true);
                        return true;
                    }
                    if (!fallback(result)) {
                        return finishRename(result, type);
                    }
                }
                if (AtomicOps.existsNow(target)) {
                    throw failed(new AtomicResult(AtomicOutcome.EXISTS, null));
                }
                TestPoints.hold("move.no-replace.after-look", sourceKey);
                result = ops.takeAway(target);
            }
            return finishRename(result, type);
        }

        private boolean fallback(final AtomicResult result) {
            return !result.isConnectionLost() && result.getOutcome() == AtomicOutcome.UNSUPPORTED;
        }

        private boolean finishRename(final AtomicResult result, final FileType type) throws MoveFailedException {
            if (result.isConnectionLost()) {
                throw failed(result);
            }
            switch (result.getOutcome()) {
            case DONE:
                events(type);
                return true;
            case REFUSED:
                return false;
            default:
                throw failed(result);
            }
        }

        private void events(final FileType type) {
            try {
                FileObjectUtils.getAbstractFileObject(target).handleCreate(type);
                target.close();
                handleDelete();
            } catch (final Exception e) {
                LOG.warn("Could not complete the file events after the move", e);
            }
        }

        private FileObject tempFile() throws FileSystemException {
            if (temp == null) {
                temp = CommitStep.sibling(target, CommitStep.tempName(target.getName().getBaseName(), ".part"));
            }
            return temp;
        }

        private void setCopiedTime(final FileObject destination, final Capability capability) throws FileSystemException {
            if (target.getUpdateLastModified() && target.getFileSystem().hasCapability(capability)
                    && fileSystem.hasCapability(Capability.GET_LAST_MODIFIED)) {
                final long time = getContent().getLastModifiedTime();
                TestPoints.hold("move.before-time-set", sourceKey);
                try {
                    destination.getContent().setLastModifiedTime(time);
                } catch (final FileSystemException e) {
                    if (Faults.of(e) != Fault.APPLICATION) {
                        throw e;
                    }
                    LOG.warn(capability == Capability.SET_LAST_MODIFIED_FOLDER
                            ? "The server refused the modified time on the folder"
                            : "The server refused the modified time on the temp file", e);
                }
            }
        }

        private void copy() throws Exception {
            tempPath = tempFile().getName().getPath();
            TestPoints.hold("move.before-copy", sourceKey);
            try (InputStream input = getContent().getInputStream();
                    OutputStream output = temp.getContent().getOutputStream()) {
                final byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                long beforeHold = Long.MAX_VALUE;
                if (TestPoints.enabled()) {
                    final String bytes = TestPoints.arguments("move.copy.half", sourceKey).get("bytes");
                    beforeHold = bytes == null ? 1024L * 1024 : Math.max(1L, Long.parseLong(bytes));
                }
                long written = 0;
                int count;
                while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, beforeHold))) != -1) {
                    output.write(buffer, 0, count);
                    written += count;
                    beforeHold -= count;
                    if (beforeHold == 0) {
                        output.flush();
                        TestPoints.hold("move.copy.half", sourceKey, "bytes=" + written);
                        beforeHold = Long.MAX_VALUE;
                    }
                }
            }
            setCopiedTime(temp, Capability.SET_LAST_MODIFIED_FILE);
            phase = MovePhase.COMMIT;
            TestPoints.hold("move.before-commit", sourceKey);
            final CommitStep.Mode mode = options.isReplaceExisting() ? CommitStep.Mode.OVERWRITE : CommitStep.Mode.CREATE_NEW;
            final CommitResult result;
            try {
                result = CommitStep.commit(temp, target, mode, options.commitOptions(), sourceKey);
            } catch (final UnclearResultException e) {
                phase = e.getPhase();
                oldTargetDeleted |= e.isOldTargetDeleted();
                throw e;
            }
            oldTargetDeleted |= result.isOldTargetDeleted();
            if (result.getOutcome() != CommitResult.Outcome.COMMITTED || result.isConnectionLost()) {
                if (result.getOutcome() == CommitResult.Outcome.COMMITTED) {
                    throw lostAfterPublication(result.getAtomicResult());
                }
                final AtomicResult reply = result.getAtomicResult();
                if (!reply.isConnectionLost()) {
                    try {
                        temp.delete();
                    } catch (final FileSystemException e) {
                        final MoveFailedException failure = failure(e, fault(reply),
                                result.getOutcome() == CommitResult.Outcome.TARGET_EXISTS, Faults.of(e) == Fault.TRANSPORT);
                        throw failure;
                    }
                }
                throw failed(reply);
            }
        }

        private UnclearResultException lostAfterPublication(final AtomicResult result) {
            phase = MovePhase.DELETE_SOURCE;
            return new UnclearResultException("vfs.provider/atomic-not-known.error", new IOException(result.toString()),
                    phase, tempPath, oldTargetDeleted, "source delete", getName().getFriendlyURI());
        }

        private void deleteSource(final FileType type, final boolean linked) throws Exception {
            phase = MovePhase.DELETE_SOURCE;
            TestPoints.hold("move.before-source-delete", sourceKey);
            try {
                synchronized (fileSystem) {
                    doDelete();
                }
            } catch (final Exception e) {
                if (type != FileType.FILE || Faults.of(e) != Fault.MISSING) {
                    throw e;
                }
            }
            if (linked) {
                events(type);
                return;
            }
            try {
                handleDelete();
            } catch (final Exception e) {
                LOG.warn("Could not complete the file events after the move", e);
            }
        }

        private Fault fault(final AtomicResult result) {
            if (result.getFault() != null) {
                return result.getFault();
            }
            switch (result.getOutcome()) {
            case GONE:
                return Fault.MISSING;
            case BUSY:
                return Fault.BUSY;
            default:
                return Fault.APPLICATION;
            }
        }

        private MoveFailedException failed(final AtomicResult result) {
            return failure(new IOException(result.toString()), fault(result),
                    !options.isReplaceExisting() && result.getOutcome() == AtomicOutcome.EXISTS, result.isConnectionLost());
        }

        private MoveFailedException failure(final Throwable cause, final Fault fault, final boolean exists,
                final boolean lost) {
            return new MoveFailedException(getName().getFriendlyURI(), target.getName().getFriendlyURI(), cause, fault,
                    phase, tempPath, oldTargetDeleted, exists, lost);
        }
    }

    /**
     * Called after this file-object closed all its streams.
     */
    protected void notifyAllStreamsClosed() {
        // noop
    }

    /**
     * Notify the parent of a change to its children, when a child is created or deleted.
     *
     * @param childName The name of the child.
     * @param newType The type of the child.
     * @throws Exception if an error occurs.
     */
    private void notifyParent(final FileName childName, final FileType newType) throws Exception {
        if (parent == null) {
            final FileName parentName = fileName.getParent();
            if (parentName != null) {
                // Locate the parent, if it is cached
                parent = fileSystem.getFileFromCache(parentName);
            }
        }

        if (parent != null) {
            FileObjectUtils.getAbstractFileObject(parent).childrenChanged(childName, newType);
        }
    }

    /**
     * Called when the type or content of this file changes.
     * <p>
     * This implementation does nothing.
     * </p>
     *
     * @throws Exception if an error occurs.
     */
    protected void onChange() throws Exception {
        // noop
    }

    /**
     * Called when the children of this file change. Allows subclasses to refresh any cached information about the
     * children of this file.
     * <p>
     * This implementation does nothing.
     * </p>
     *
     * @param child The name of the child that changed.
     * @param newType The type of the file.
     * @throws Exception if an error occurs.
     */
    protected void onChildrenChanged(final FileName child, final FileType newType) throws Exception {
        // noop
    }

    /**
     * This will prepare the fileObject to get resynchronized with the underlying file system if required.
     *
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public void refresh() throws FileSystemException {
        // Detach from the file
        try {
            detach();
        } catch (final Exception e) {
            throw new FileSystemException("vfs.provider/resync.error", fileName, e);
        }
    }

    final void detachAfterCommit() throws FileSystemException {
        synchronized (fileSystem) {
            final FileName parentName = getName().getParent();
            final FileObject cached = parent != null || parentName == null ? parent : fileSystem.getFileFromCache(parentName);
            if (cached != null) {
                FileObjectUtils.getAbstractFileObject(cached).invalidateCommitCache();
            }
            invalidateCommitCache();
        }
    }

    private void invalidateCommitCache() throws FileSystemException {
        try {
            if (attached) {
                detach();
            } else {
                doDetach();
                removeChildrenCache();
            }
        } catch (final Exception e) {
            throw new FileSystemException("vfs.provider/close.error", e, getName());
        }
    }

    private void removeChildrenCache() {
        children = null;
    }

    private FileObject resolveFile(final FileName child) throws FileSystemException {
        return fileSystem.resolveFile(child);
    }

    /**
     * Finds a file, relative to this file.
     *
     * @param path The path of the file to locate. Can either be a relative path, which is resolved relative to this
     *            file, or an absolute path, which is resolved relative to the file system that contains this file.
     * @return The FileObject.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject resolveFile(final String path) throws FileSystemException {
        final FileName otherName = fileSystem.getFileSystemManager().resolveName(fileName, path);
        return fileSystem.resolveFile(otherName);
    }

    /**
     * Returns a child by name.
     *
     * @param name The name of the child to locate.
     * @param scope the NameScope.
     * @return The FileObject for the file or null if the child does not exist.
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject resolveFile(final String name, final NameScope scope) throws FileSystemException {
        // return fs.resolveFile(this.name.resolveName(name, scope));
        return fileSystem.resolveFile(fileSystem.getFileSystemManager().resolveName(fileName, name, scope));
    }

    private FileObject[] resolveFiles(final FileName[] children) throws FileSystemException {
        if (children == null) {
            return null;
        }

        final FileObject[] objects = new FileObject[children.length];
        for (int iterChildren = 0; iterChildren < children.length; iterChildren++) {
            objects[iterChildren] = resolveFile(children[iterChildren]);
        }

        return objects;
    }

    @Override
    public boolean setExecutable(final boolean readable, final boolean ownerOnly) throws FileSystemException {
        try {
            return exists() && doSetExecutable(readable, ownerOnly);
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/set-executable.error", fileName, exc);
        }
    }

    private void setFileType(final FileType type) {
        if (type != null && type != FileType.IMAGINARY) {
            Uncheck.run(() -> fileName.setType(type));
        }
        this.type = type;
    }

    @Override
    public boolean setReadable(final boolean readable, final boolean ownerOnly) throws FileSystemException {
        try {
            return exists() && doSetReadable(readable, ownerOnly);
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/set-readable.error", fileName, exc);
        }
    }

    @Override
    public boolean setWritable(final boolean readable, final boolean ownerOnly) throws FileSystemException {
        try {
            return exists() && doSetWritable(readable, ownerOnly);
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider/set-writable.error", fileName, exc);
        }
    }

    /**
     * Returns the URI as a String.
     *
     * @return the URI as a String.
     */
    @Override
    public String toString() {
        return fileName.getURI();
    }
}
