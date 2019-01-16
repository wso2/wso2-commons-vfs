/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.1
 *
 */
package org.apache.commons.vfs2.provider.ftp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.GenericFileName;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Base class with FTP client wrapper logic.
 */
public abstract class AbstractFtpClientWrapper implements FtpClient {

    private static final Log LOG = LogFactory.getLog(AbstractFtpClientWrapper.class);
    private final GenericFileName root;
    private final FileSystemOptions fileSystemOptions;

    private final Integer defaultTimeout;

    private FTPClient ftpClient;

    public AbstractFtpClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions,
                                    Integer defaultTimeout)
            throws FileSystemException {
        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        this.defaultTimeout = defaultTimeout;
        getFtpClient(); // fail-fast
    }

    public GenericFileName getRoot() {
        return root;
    }

    public FileSystemOptions getFileSystemOptions() {
        return fileSystemOptions;
    }

    protected Integer getDefaultTimeout() {
        return defaultTimeout;
    }

    protected abstract FTPClient createClient() throws FileSystemException;

    private synchronized FTPClient getFtpClient() throws FileSystemException {
        if (ftpClient == null) {
            ftpClient = createClient();
        }
        if (root.getURI().contains("vfs.passive=true") &&
            ftpClient.getDataConnectionMode() == FTPClient.ACTIVE_LOCAL_DATA_CONNECTION_MODE) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("FTP Client is entering into passive mode.");
            }
            ftpClient.enterLocalPassiveMode();
        }

        return ftpClient;
    }

    public boolean isConnected() throws FileSystemException {
        return ftpClient != null && ftpClient.isConnected();
    }

    public void disconnect() throws IOException {
        try {
            getFtpClient().disconnect();
        } finally {
            ftpClient = null;
        }
    }

    public FTPFile[] listFiles(String relPath) throws IOException {
        try {
            return listFilesInDirectory(relPath);
        } catch (IOException e) {
            disconnect();
            return listFilesInDirectory(relPath);
        }
    }

    private FTPFile[] listFilesInDirectory(String relPath) throws IOException {
        FTPFile[] files;

        // VFS-307: no check if we can simply list the files, this might fail if there are spaces in the path
        files = getFtpClient().listFiles(relPath);
        if (FTPReply.isPositiveCompletion(getFtpClient().getReplyCode())) {
            return files;
        }

        // VFS-307: now try the hard way by cd'ing into the directory, list and cd back
        // if VFS is required to fallback here the user might experience a real bad FTP performance
        // as then every list requires 4 ftp commands.
        String workingDirectory = null;
        if (relPath != null) {
            workingDirectory = getFtpClient().printWorkingDirectory();
            if (!getFtpClient().changeWorkingDirectory(relPath)) {
                return null;
            }
        }

        files = getFtpClient().listFiles();

        if (relPath != null && !getFtpClient().changeWorkingDirectory(workingDirectory)) {
            throw new FileSystemException("vfs.provider.ftp.wrapper/change-work-directory-back.error",
                                          workingDirectory);
        }
        return files;
    }

    public boolean removeDirectory(String relPath) throws IOException {
        try {
            return getFtpClient().removeDirectory(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().removeDirectory(relPath);
        }
    }

    public boolean deleteFile(String relPath) throws IOException {
        try {
            return getFtpClient().deleteFile(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().deleteFile(relPath);
        }
    }

    public boolean rename(String oldName, String newName) throws IOException {
        try {
            return getFtpClient().rename(oldName, newName);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().rename(oldName, newName);
        }
    }

    public boolean makeDirectory(String relPath) throws IOException {
        try {
            return getFtpClient().makeDirectory(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().makeDirectory(relPath);
        }
    }

    public boolean completePendingCommand() throws IOException {
        if (ftpClient != null) {
            return getFtpClient().completePendingCommand();
        }

        return true;
    }

    public InputStream retrieveFileStream(String relPath) throws IOException {
        try {
            return getFtpClient().retrieveFileStream(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().retrieveFileStream(relPath);
        }
    }

    public InputStream retrieveFileStream(String relPath, long restartOffset) throws IOException {
        try {
            FTPClient client = getFtpClient();
            client.setRestartOffset(restartOffset);
            return client.retrieveFileStream(relPath);
        } catch (IOException e) {
            disconnect();

            FTPClient client = getFtpClient();
            client.setRestartOffset(restartOffset);
            return client.retrieveFileStream(relPath);
        }
    }

    public OutputStream appendFileStream(String relPath) throws IOException {
        try {
            return getFtpClient().appendFileStream(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().appendFileStream(relPath);
        }
    }

    public OutputStream storeFileStream(String relPath) throws IOException {
        try {
            return getFtpClient().storeFileStream(relPath);
        } catch (IOException e) {
            disconnect();
            return getFtpClient().storeFileStream(relPath);
        }
    }

    public boolean abort() throws IOException {
        try {
            disconnect();
            return true;
        } catch (IOException e) {
            disconnect();
        }
        return true;
    }

    public String getReplyString() throws IOException {
        return getFtpClient().getReplyString();
    }
}
