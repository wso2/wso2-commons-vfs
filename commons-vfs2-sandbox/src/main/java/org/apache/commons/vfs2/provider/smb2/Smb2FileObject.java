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

package org.apache.commons.vfs2.provider.smb2;

import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.File;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileObject;
import org.apache.commons.vfs2.provider.UriParser;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Class containing all its handles from the current Instance but NOT all
 * possible handles to the same file (read/write separated)
 */
public class Smb2FileObject extends AbstractFileObject<Smb2FileSystem> {

    private final String relPathToShare;
    private FileAllInformation fileInfo;
    private FileName rootName;
    private DiskEntry diskEntryWrite;
    private DiskEntry diskEntryRead;
    private DiskEntry diskEntryFolderWrite;

    /**
     * @param name the file name - muse be an instance of {@link AbstractFileName}
     * @param fileSystem the file system
     * @throws ClassCastException if {@code name} is not an instance of {@link AbstractFileName}
     */
    protected Smb2FileObject(AbstractFileName name, Smb2FileSystem fileSystem, final FileName rootName) {

        super(name, fileSystem);
        String relPath = name.getURI().substring(rootName.getURI().length());
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
        if (diskEntryRead == null) {
            getDiskEntryRead();
        }
        InputStream is = ((File) diskEntryRead).getInputStream();

        // Wrapper will close the file object and input stream
        return new Smb2InputStreamWrapper(is, this);
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

    private void getFileInfo() {

        if (fileInfo == null) {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
                try {
                    fileInfo = client.getFileInfo(getRelPathToShare());
                } finally {
                    fileSystem.putClient(client);
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

        if (diskEntryWrite == null) {
            getDiskEntryWrite();
        }

        return ((File) diskEntryWrite).getOutputStream();
    }

    @Override
    protected void doCreateFolder() throws Exception {

        try {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
                try {
                    client.createFolder(getRelPathToShare());
                } finally {
                    fileSystem.putClient(client);
                }
            }
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/folder-create.error", getName(), e.getCause());
        }
    }

    @Override
    protected void endOutput() throws Exception {

        super.endOutput();
        closeAllHandles();
    }

    /**
     * Get file write handle.
     *
     * @throws FileSystemException handle create error
     */
    private void getDiskEntryWrite() throws FileSystemException {

        closeAllHandles();
        try {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                diskEntryWrite = fileSystem.getDiskEntryWrite(getRelPathToShare());
            }
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
        }
    }

    /**
     * Get file read handle.
     *
     * @throws FileSystemException handle create error
     */
    private void getDiskEntryRead() throws FileSystemException {

        try {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                diskEntryRead = fileSystem.getDiskEntryRead(getRelPathToShare());
            }
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
        }
    }

    /**
     * Get directory write handle.
     *
     * @throws FileSystemException handle create error
     */
    private void getDiskEntryFolderWrite() throws FileSystemException {

        try {
            synchronized (getFileSystem()) {
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                diskEntryFolderWrite = fileSystem.getDiskEntryFolderWrite(getRelPathToShare());
            }
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
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

    @Override
    protected void doRename(final FileObject newFile) throws Exception {

        Smb2FileObject fileObject = (Smb2FileObject) newFile;
        if (doGetType() == FileType.FOLDER) {
            if (diskEntryFolderWrite == null) {
                getDiskEntryFolderWrite();
            }
            diskEntryFolderWrite.rename(fileObject.getRelPathToShare());
        } else {
            if (diskEntryWrite == null) {
                getDiskEntryWrite();
            }
            diskEntryWrite.rename(fileObject.getRelPathToShare());
            closeAllHandles();
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
            try {
                childrenNames = client.getChildren(getRelPathToShare());
            } finally {
                fileSystem.putClient(client);
            }

            for (String child : childrenNames) {
                children.add(fileSystem.getFileSystemManager().resolveFile(this, UriParser.encode(child)));
            }
            return children.toArray(new FileObject[children.size()]);
        }
    }

    @Override
    protected void doDelete() throws Exception {

        synchronized (getFileSystem()) {
            if (diskEntryRead != null) {
                diskEntryRead.close();
            }
            endOutput();

            Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
            Smb2ClientWrapper client = (Smb2ClientWrapper) fileSystem.getClient();
            try {
                client.delete(getRelPathToShare());
            } finally {
                fileSystem.putClient(client);
            }
        }
    }

    @Override
    protected long doGetLastModifiedTime() throws Exception {

        getFileInfo();
        return fileInfo.getBasicInformation().getChangeTime().getWindowsTimeStamp();
    }

    @Override
    public void close() throws FileSystemException {

        super.close();
        closeAllHandles();
    }

    /**
     * Closes DiskEntry Objects.
     */
    private void closeAllHandles() {

        if (diskEntryRead != null) {
            diskEntryRead.close();
            diskEntryRead = null;
        }
        if (diskEntryWrite != null) {
            diskEntryWrite.close();
            diskEntryWrite = null;
        }
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
}
