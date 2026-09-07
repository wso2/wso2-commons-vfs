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

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.security.jce.JceSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;

import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A wrapper to the SMBClient for bundling the related client & connection instances.
 */
public class Smb2ClientWrapper extends SMBClient {

    private static final Log LOG = LogFactory.getLog(Smb2ClientWrapper.class);
    private static SmbConfig config = SmbConfig.builder()
            .withDfsEnabled(true)
            .withMultiProtocolNegotiate(true)
            .build();
    private static final String BCFIPS = "BCFIPS";
    protected final FileSystemOptions fileSystemOptions;
    private final GenericFileName root;
    private SMBClient smbClient;
    private Connection connection;
    private Session session;
    private DiskShare diskShare;

    protected Smb2ClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {

        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        boolean encryptionEnabled = Smb2FileSystemConfigBuilder.getInstance().getEncryptionEnabled(fileSystemOptions);

        if (encryptionEnabled) {
            config = SmbConfig.builder()
                    .withDfsEnabled(true)
                    .withEncryptData(true)
                    .withMultiProtocolNegotiate(true)
                    .build();
        }
        // Detect FIPS
        final String activeProvider = Security.getProviders()[0].getName();
        final boolean isFipsMode = BCFIPS.equalsIgnoreCase(activeProvider);
        if (isFipsMode) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("FIPS mode detected.");
            }
            System.setProperty("crypto.policy", "limited");
            config = SmbConfig.builder()
                    .withDialects(SMB2Dialect.SMB_2_1)
                    .withSecurityProvider(new JceSecurityProvider())
                    .withSigningRequired(true).build();
        }
        smbClient = new SMBClient(config);
        setupClient();
    }

    private void setupClient() throws FileSystemException {

        final GenericFileName rootName = getRoot();

        //the relevant data to authenticate a connection
        final String userString = rootName.getUserName();
        String userName = "";
        if (userString != null && !userString.isEmpty()) {

            if (userString.contains(";")) {
                userName = userString.substring(userString.indexOf(";") + 1, userString.length());
            } else if (userString.contains("\\")) {
                userName = userString.substring(userString.indexOf("\\") + 1, userString.length());
            } else {
                userName = userString;
            }
        }

        String password = rootName.getPassword();

        String authDomain = null;

        if (userString.contains(";")) {
            authDomain = userString.substring(0, userString.indexOf(";"));
        } else if (userString.contains("\\")) {
            authDomain = userString.substring(0, userString.indexOf("\\"));
        } else {
            authDomain = null;
        }

        //if username == "" the client tries to authenticate "anonymously". It's also possible to submit "guest" as
        // username Password may be null depending on upstream auth handling. Avoid NullPointerException when
        // converting to char[] by falling back to an empty array. This preserves constructor contract without
        // altering authentication flow.
        char[] pwd = (password != null) ? password.toCharArray() : new char[0];
        AuthenticationContext authContext = new AuthenticationContext(userName, pwd, authDomain);

        //a connection stack is: SMBClient > Connection > Session > DiskShare
        try {
            //the port parsed from the smb2:// URI is stored on the file name; when it differs from the
            //SMB2 default it has to be passed explicitly, since connect(host) always uses the default port
            int port = rootName.getPort();
            connection = (port > 0 && port != rootName.getDefaultPort())
                    ? smbClient.connect(rootName.getHostName(), port)
                    : smbClient.connect(rootName.getHostName());
            session = connection.authenticate(authContext);
            String share = ((Smb2FileName) rootName).getShareName();
            diskShare = (DiskShare) session.connectShare(share);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Error while creating connection to " + rootName.getHostName());
            }
            close();
            throw new FileSystemException("vfs.provider.smb2/connect.error", rootName.getHostName(), e);
        }
    }

    public GenericFileName getRoot() {

        return root;
    }

    /**
     * Returns file information of a given file.
     *
     * @param relativePath of file
     * @return FileAllInformation object
     */
    public FileAllInformation getFileInfo(String relativePath) throws FileSystemException {

        try {
            return diskShare.getFileInformation(relativePath);
        } catch (Exception e) {
            if (e instanceof SMBApiException) {
                return null;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not get information for file: " + relativePath);
            }
            // Throw File System Exception for Transport Level Exceptions to trigger re-try mechanism
            throw new FileSystemException("Could not get information for file: " + relativePath);
        }
    }

    /**
     * Returns a write handle on the file.
     *
     * @param path   path to file
     * @param append append to file
     * @return DiskEntry file
     */
    public DiskEntry getDiskEntryWrite(String path, boolean append) throws FileSystemException {
        ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        Set<AccessMask> accessMask = resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.MAXIMUM_ALLOWED);
        return diskShare.open(path, accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                append ? SMB2CreateDisposition.FILE_OPEN_IF : SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    /**
     * Retrieves the set of \AccessMask\ values based on the provided configuration string.
     *
     * @param config the access mask configuration string
     * @param defaultMask the default \AccessMask\ value to use if the configuration string is null or empty
     * @return a set of \AccessMask\ values corresponding to the configuration
     * @throws IllegalArgumentException if the configuration string is null or invalid
     */
    private static Set<AccessMask> resolveAccessMaskOrDefault(ArrayList<String> config, AccessMask defaultMask) {
        if (config == null || config.isEmpty()) {
            // To keep backward compatibility
            LOG.debug("Disk share access mask is not configured. Using default access mask: " + defaultMask);
            return EnumSet.of(defaultMask);
        }

        Set<AccessMask> accessMasks = EnumSet.noneOf(AccessMask.class);

        for (String maskValue : config) {
            try {
                if (maskValue != null) {
                    AccessMask mask = AccessMask.valueOf(maskValue.trim().toUpperCase());
                    accessMasks.add(mask);
                }
            } catch (IllegalArgumentException e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Invalid access mask value: " + maskValue);
                }
            }
        }

        if (accessMasks.isEmpty()) {
            // To keep backward compatibility
            return EnumSet.of(defaultMask);
        }

        return accessMasks;
    }


    /**
     * Creates a folder on a given path.
     *
     * @param path of folder
     */
    public void createFolder(String path) {

        DiskEntry de = getDiskEntryFolderWrite(path);
        de.close();
    }

    /**
     * Returns a handle for a folder.
     *
     * @param path folder path
     * @return DiskEntry
     */
    public DiskEntry getDiskEntryFolderWrite(String path) {

        ArrayList<String> diskShareAccessMask = Smb2FileSystemConfigBuilder.getInstance()
                .getDiskShareAccessMask(fileSystemOptions);
        Set<AccessMask> accessMask = resolveAccessMaskOrDefault(diskShareAccessMask, AccessMask.GENERIC_ALL);
        return diskShare.openDirectory(path,
                accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN_IF,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE));
    }

    /**
     * Returns a read handle on the file.
     *
     * @param path path to file
     * @return DiskEntry file
     */
    public DiskEntry getDiskEntryRead(String path) {

        return diskShare.open(path,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NO_COMPRESSION));
    }

    /**
     * Returns directory listing of path.
     *
     * @param path
     * @return listing string array
     */
    public String[] getChildren(String path) {

        List<String> children = new ArrayList<>();

        for (FileIdBothDirectoryInformation file : diskShare.list(path)) {
            String name = file.getFileName();
            if (name.equals(".") || name.equals("..") || name.equals("./") || name.equals("../")) {
                // ignore if there are no files in the directory and only relative path exists
                continue;
            }
            children.add(file.getFileName());
        }
        return children.toArray(new String[children.size()]);
    }

    /**
     * Delete item in given path of share.
     *
     * @param path string
     * @throws FileSystemException
     */
    public void delete(String path) throws FileSystemException {

        FileAllInformation info;
        try {
            info = diskShare.getFileInformation(path);
        } catch (SMBApiException e) {
            //file or folder does not exist
            throw new FileSystemException("Could not delete file " + path);
        }
        if (info.getStandardInformation().isDirectory()) {
            diskShare.rmdir(path, true);
        } else {
            diskShare.rm(path);
        }
    }

    public void close() {
        try {
            if (diskShare != null) {
                diskShare.close();
            }
            if (session != null) {
                session.close();
            }
            if (connection != null) {
                connection.close(true);
            }
        } catch (Exception e) {
            LOG.debug("Error while closing the connection.", e);
        } finally {
            if (connection != null) {
                try {
                    LOG.debug("Connection closed forcefully for : " + smbClient);
                    connection.close(true);
                } catch (IOException e) {
                    //Ignore
                }
            }
            // Close the client
            super.close();
        }
    }
}
