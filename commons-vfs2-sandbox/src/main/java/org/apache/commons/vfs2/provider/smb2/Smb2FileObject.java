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
import org.apache.commons.vfs2.util.MonitorInputStream;
import org.apache.commons.vfs2.util.MonitorOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Class containing all its handles from the current Instance but NOT all
 * possible handles to the same file (read/write separated)
 */
public class Smb2FileObject extends AbstractFileObject<Smb2FileSystem> {

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
        return getDiskEntryReadInputStream();

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
        DiskEntry diskEntryWrite;
        Smb2ClientWrapper smb2ClientWrapper;
        OutputStream os;
        Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
        smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
        try {
            synchronized (getFileSystem()) {
                diskEntryWrite = smb2ClientWrapper.getDiskEntryWrite(getRelPathToShare(), bAppend);
                os = ((File) diskEntryWrite).getOutputStream(bAppend);
            }
        } catch (Exception e) {
            fileSystem.putClient(smb2ClientWrapper);
            throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
        }
        return new Smb2OutputStream(smb2ClientWrapper, os, diskEntryWrite);
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

    /**
     * Get file read handle.
     *
     * @throws FileSystemException handle create error
     */
    private Smb2InputStream getDiskEntryReadInputStream() throws FileSystemException {

        InputStream is;
        Smb2ClientWrapper smb2ClientWrapper = null;
        DiskEntry diskEntry;
        Smb2FileSystem fileSystem = null;
        try {
            synchronized (getFileSystem()) {
                fileSystem = (Smb2FileSystem) getFileSystem();

                smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
                diskEntry = smb2ClientWrapper.getDiskEntryRead(getRelPathToShare());
                is = ((File) diskEntry).getInputStream();

            }
        } catch (Exception e) {
            fileSystem.putClient(smb2ClientWrapper);
            throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
        }
        return new Smb2InputStream(smb2ClientWrapper, is, this, diskEntry);
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
        synchronized (getFileSystem()) {

            Smb2FileObject fileObject = (Smb2FileObject) newFile;
            if (doGetType() == FileType.FOLDER) {
                DiskEntry diskEntryFolderWrite;
                Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                Smb2ClientWrapper smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
                try {
                    diskEntryFolderWrite = smb2ClientWrapper.getDiskEntryFolderWrite(getRelPathToShare());
                    diskEntryFolderWrite.rename(fileObject.getRelPathToShare());
                    diskEntryFolderWrite.close();
                } catch (Exception e) {
                    throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
                } finally {
                    fileSystem.putClient(smb2ClientWrapper);
                }

            } else {
                try {
                    synchronized (getFileSystem()) {
                        Smb2FileSystem fileSystem = (Smb2FileSystem) getFileSystem();
                        Smb2ClientWrapper smb2ClientWrapper = (Smb2ClientWrapper) fileSystem.getClient();
                        DiskEntry diskEntry;

                        try {
                            diskEntry = smb2ClientWrapper.getDiskEntryWrite(getRelPathToShare(), true);
                            diskEntry.rename(fileObject.getRelPathToShare());
                            diskEntry.close();
                        } finally {
                            fileSystem.putClient(smb2ClientWrapper);
                        }
                    }
                } catch (Exception e) {
                    throw new FileSystemException("vfs.provider.smb2/diskentry-create.error", getName(), e.getCause());
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
            try {
                childrenNames = client.getChildren(getRelPathToShare());
            } finally {
                fileSystem.putClient(client);
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
     * An InputStream that monitors for end-of-file.
     */
    class Smb2InputStream extends MonitorInputStream {
        private final Smb2ClientWrapper client;
        private final FileObject fileObject;
        private final DiskEntry diskEntry;

        public Smb2InputStream(final Smb2ClientWrapper client, final InputStream in, FileObject fileObject, DiskEntry diskEntry) {
            super(in);
            this.client = client;
            this.fileObject = fileObject;
            this.diskEntry = diskEntry;

        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            super.close();
            try {
                if (in != null) {
                    in.close();
                }
                fileObject.close();
                diskEntry.close();
            } finally {
                getAbstractFileSystem().putClient(client);
            }
        }
    }

    /**
     * An InputStream that monitors for end-of-file.
     */
    class Smb2OutputStream extends MonitorOutputStream {
        private final Smb2ClientWrapper client;
        private final DiskEntry diskEntry;

        public Smb2OutputStream(final Smb2ClientWrapper client, final OutputStream out, DiskEntry diskEntry) {
            super(out);
            this.client = client;
            this.diskEntry = diskEntry;
        }

        /**
         * Called after the stream has been closed.
         */
        @Override
        protected void onClose() throws IOException {
            try {
                if (out != null) {
                    out.close();
                }
                diskEntry.close();
            } finally {
                getAbstractFileSystem().putClient(client);
            }
        }
    }
}
