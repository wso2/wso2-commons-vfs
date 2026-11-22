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

import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.UserAuthenticationData;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AbstractOriginatingFileProvider;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.URLFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.StringTokenizer;
import java.util.function.Supplier;

/**
 * A provider for FTP file systems.
 */
public class FtpFileProvider extends AbstractOriginatingFileProvider {

    /**
     * File Entry Parser.
     */
    public static final String ATTR_FILE_ENTRY_PARSER = "FEP";

    /**
     * Passive mode
     */
    public static final String PASSIVE_MODE = "vfs.passive";

    /**
     * FTPS implicit mode
     */
    public static final String IMPLICIT_MODE = "vfs.implicit";

    public static final String PROTECTION_MODE = "vfs.protection";

    /**
     * Authenticator types.
     */
    public static final UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = {
            UserAuthenticationData.USERNAME, UserAuthenticationData.PASSWORD };

    static final Collection<Capability> CAPABILITIES = Collections
        .unmodifiableCollection(Arrays.asList(Capability.CREATE, Capability.DELETE, Capability.RENAME,
            Capability.GET_TYPE, Capability.LIST_CHILDREN, Capability.READ_CONTENT, Capability.GET_LAST_MODIFIED,
            Capability.URI, Capability.WRITE_CONTENT, Capability.APPEND_CONTENT, Capability.RANDOM_ACCESS_READ));

    /**
     * Constructs a new provider.
     */
    public FtpFileProvider() {
        setFileNameParser(FtpFileNameParser.getInstance());
    }

    /**
     * Creates the file system.
     *
     * @return a new FileSystem, never null.
     */
    @Override
    protected FileSystem doCreateFileSystem(final FileName name, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        // Create the file system
        final GenericFileName rootName = (GenericFileName) name;

        // The values of the URI go into a private copy. The options of the caller stay as they were passed, and they
        // stay the key of the file system in the cache.
        final FileSystemOptions opts = fileSystemOptions == null ? new FileSystemOptions()
                : (FileSystemOptions) fileSystemOptions.clone();
        FtpFileSystemConfigBuilder builder = FtpFileSystemConfigBuilder.getInstance();
        if (this.defaultTimeout != null && (this.defaultTimeout > 0)) {
            builder.setConnectTimeout(opts, this.defaultTimeout);
            builder.setSoTimeout(opts, this.defaultTimeout);
        }

        String queryString = ((URLFileName)(rootName)).getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            FtpFileSystemConfigBuilder cfgBuilder = FtpFileSystemConfigBuilder.getInstance();
            StringTokenizer st = new StringTokenizer(queryString, "?&!=");
            while (st.hasMoreTokens()) {
                if (PASSIVE_MODE.equalsIgnoreCase(st.nextToken()) &&
                        st.hasMoreTokens() && "true".equalsIgnoreCase(st.nextToken())) {
                    cfgBuilder.setPassiveMode(opts, true);
                }
            }
        }

        final FTPClientWrapper ftpClient = new FTPClientWrapper(rootName, opts, getSocketGroups(), windowStart);
        /*
         * FTPClient ftpClient = FtpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(),
         * rootName.getUserName(), rootName.getPassword(), rootName.getPath(), fileSystemOptions);
         */
        return new FtpFileSystem(rootName, ftpClient, fileSystemOptions, opts);
    }

    /**
     * Gets the source of the socket groups of new clients. The manager keeps each group, so an abort of the manager
     * reaches every socket of the clients.
     *
     * @return the source.
     */
    protected Supplier<SocketGroup> getSocketGroups() {
        return ((DefaultFileSystemManager) getContext().getFileSystemManager())::newSocketGroup;
    }

    @Override
    public Collection<Capability> getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public FileSystemConfigBuilder getConfigBuilder() {
        return FtpFileSystemConfigBuilder.getInstance();
    }
}
