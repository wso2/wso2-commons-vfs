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
package org.apache.commons.vfs2.provider.ftp;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.StringTokenizer;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.AbstractOriginatingFileProvider;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.URLFileName;

/**
 * A provider for FTP file systems.
 *
 * @author <a href="http://commons.apache.org/vfs/team-list.html">Commons VFS team</a>
 * @version $Revision: 1040766 $ $Date: 2010-12-01 02:06:53 +0530 (Wed, 01 Dec 2010) $
 */
public class FtpFileProvider
    extends AbstractOriginatingFileProvider
{
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
    public static final UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = new UserAuthenticationData.Type[]
        {
            UserAuthenticationData.USERNAME, UserAuthenticationData.PASSWORD
        };

    static final Collection<Capability> capabilities = Collections.unmodifiableCollection(Arrays.asList(new Capability[]
    {
        Capability.CREATE,
        Capability.DELETE,
        Capability.RENAME,
        Capability.GET_TYPE,
        Capability.LIST_CHILDREN,
        Capability.READ_CONTENT,
        Capability.GET_LAST_MODIFIED,
        Capability.URI,
        Capability.WRITE_CONTENT,
        Capability.APPEND_CONTENT,
        Capability.RANDOM_ACCESS_READ,
    }));

    public FtpFileProvider()
    {
        super();
        setFileNameParser(FtpFileNameParser.getInstance());
    }

    /**
     * Creates the filesystem.
     */
    @Override
    protected FileSystem doCreateFileSystem(final FileName name, final FileSystemOptions fileSystemOptions)
        throws FileSystemException
    {
        // Create the file system
        final URLFileName rootName = (URLFileName) name;


        //FTPClientWrapper ftpClient = new FTPClientWrapper(rootName, fileSystemOptions);

        String queryString = rootName.getQueryString();
        FileSystemOptions opts = fileSystemOptions;
        if (opts == null) {
            opts = new FileSystemOptions();
        }
        if (queryString != null) {
            FtpFileSystemConfigBuilder cfgBuilder = FtpFileSystemConfigBuilder.getInstance();
            StringTokenizer st = new StringTokenizer(queryString, "?&!=");
            while (st.hasMoreTokens()) {
                if (PASSIVE_MODE.equalsIgnoreCase(st.nextToken()) &&
                        st.hasMoreTokens() && "true".equalsIgnoreCase(st.nextToken())) {
                    cfgBuilder.setPassiveMode(opts, true);
                }
            }
        }
        /*
        FTPClient ftpClient = FtpClientFactory.createConnection(rootName.getHostName(),
            rootName.getPort(),
            rootName.getUserName(),
            rootName.getPassword(),
            rootName.getPath(),
            fileSystemOptions);
        */
        FTPClientWrapper ftpClient = new FTPClientWrapper(rootName, opts);
        return new FtpFileSystem(rootName, ftpClient, fileSystemOptions);
    }

    @Override
    public FileSystemConfigBuilder getConfigBuilder()
    {
        return FtpFileSystemConfigBuilder.getInstance();
    }

    public Collection<Capability> getCapabilities()
    {
        return capabilities;
    }
}
