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
package org.apache.commons.vfs2.provider.ftps;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.URLFileName;
import org.apache.commons.vfs2.provider.ftp.FtpFileProvider;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystem;

import java.util.StringTokenizer;

/**
 * A provider for FTP file systems.
 *
 * NOTE: Most of the heavy lifting for FTPS is done by the org.apache.commons.vfs2.provider.ftp package since
 * they both use commons-net package
 *
 * @author <a href="http://commons.apache.org/vfs/team-list.html">Commons VFS team</a>
 * @version $Revision: 480428 $ $Date: 2006-11-29 07:15:24 +0100 (Mi, 29 Nov 2006) $
 * @since 2.0
 */
public class FtpsFileProvider extends FtpFileProvider
{

    /**
     * SSL Keystore.
     */
    public static final String KEY_STORE = "vfs.ssl.keystore";

    /**
     * SSL Truststore.
     */
    public static final String TRUST_STORE = "vfs.ssl.truststore";

    /**
     * SSL Keystore password.
     */
    public static final String KS_PASSWD = "vfs.ssl.kspassword";

    /**
     * SSL Truststore password.
     */
    public static final String TS_PASSWD = "vfs.ssl.tspassword";

    /**
     * SSL Key password.
     */
    public static final String KEY_PASSWD = "vfs.ssl.keypassword";

    private static final Log log = LogFactory.getLog(FtpsFileProvider.class);
    public FtpsFileProvider()
    {
        super();
    }

    /**
     * Creates the filesystem.
     */
    @Override
    protected FileSystem doCreateFileSystem(final FileName name, final FileSystemOptions fileSystemOptions)
        throws FileSystemException
    {
        // Create the file system
        final GenericFileName rootName = (GenericFileName) name;

        FileSystemOptions opts = new FileSystemOptions();

        if (name instanceof URLFileName) {
            getLogger().info("FileName :" + name.getURI());
            if (fileSystemOptions != null) {
                opts = fileSystemOptions;
            }
            String queryString = ((URLFileName) name).getQueryString();
            if (queryString != null) {
                FtpsFileSystemConfigBuilder cfgBuilder = FtpsFileSystemConfigBuilder.getInstance();
                StringTokenizer st = new StringTokenizer(queryString, "&");
                while (st.hasMoreTokens()) {
                    String param = st.nextToken();
                    String[] arg = param.split("=");
                    if (PASSIVE_MODE.equalsIgnoreCase(arg[0]) && "true".equalsIgnoreCase(arg[1])) {
                        cfgBuilder.setPassiveMode(opts, true);
                    } else if (IMPLICIT_MODE.equalsIgnoreCase(arg[0]) && "true".equalsIgnoreCase(arg[1])) {
                        cfgBuilder.setFtpsType(opts, "implicit");
                    } else if (PROTECTION_MODE.equalsIgnoreCase(arg[0])) {
                        if ("P".equalsIgnoreCase(arg[1])) {
                            cfgBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.P);
                        } else if ("C".equalsIgnoreCase(arg[1])) {
                            cfgBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.C);
                        } else if ("S".equalsIgnoreCase(arg[1])) {
                            cfgBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.S);
                        } else if ("E".equalsIgnoreCase(arg[1])) {
                            cfgBuilder.setDataChannelProtectionLevel(opts, FtpsDataChannelProtectionLevel.E);
                        }
                    } else if (KEY_STORE.equalsIgnoreCase(arg[0])){
                        cfgBuilder.setKeyStore(opts,arg[1].trim());
                    }else if (TRUST_STORE.equalsIgnoreCase(arg[0])){
                        cfgBuilder.setTrustStore(opts,arg[1].trim());
                    } else if (KS_PASSWD.equalsIgnoreCase(arg[0])){
                        cfgBuilder.setKeyStorePW(opts,arg[1]);
                    } else if (TS_PASSWD.equalsIgnoreCase(arg[0])){
                        cfgBuilder.setTrustStorePW(opts,arg[1]);
                    } else if (KEY_PASSWD.equalsIgnoreCase(arg[0])){
                        cfgBuilder.setKeyPW(opts,arg[1]);
                    }
                }
            }
        }

        FtpsClientWrapper ftpClient = new FtpsClientWrapper(rootName, opts);

        return new FtpFileSystem(rootName, ftpClient, fileSystemOptions);
    }

    @Override
    public FileSystemConfigBuilder getConfigBuilder()
    {
        return FtpsFileSystemConfigBuilder.getInstance();
    }
}
