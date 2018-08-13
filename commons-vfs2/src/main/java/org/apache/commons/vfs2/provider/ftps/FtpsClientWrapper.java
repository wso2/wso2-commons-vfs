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
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.ftp.FTPClientWrapper;
import org.apache.commons.vfs2.provider.ftp.FtpFileProvider;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.io.IOException;

/**
 * A wrapper to the FTPSClient to allow automatic reconnect on connection loss.
 * <p>
 * The only difference to the {@link FTPClientWrapper} is the creation of a {@link FTPSClient} instead of a
 * {@link FTPClient}.
 *
 * @since 2.0
 */
class FtpsClientWrapper extends FTPClientWrapper {

    private static final Log log = LogFactory.getLog(FtpsClientWrapper.class);
    private FTPSClient ftpsClient;

    FtpsClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions) throws
            FileSystemException {
        super(root, fileSystemOptions);
    }

    @Override
    protected FTPSClient getFtpClient() throws FileSystemException {
        if (ftpsClient == null) {
            ftpsClient = createClient();
        }
        return ftpsClient;
    }

    @Override
    protected FTPSClient createClient() throws FileSystemException {
        final GenericFileName rootName = getRoot();
        UserAuthenticationData authData = null;
        try {
            authData = UserAuthenticatorUtils.authenticate(fileSystemOptions, FtpFileProvider.AUTHENTICATOR_TYPES);
            char[] username = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
                    UserAuthenticatorUtils.toChar(rootName.getUserName()));
            char[] password = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                    UserAuthenticatorUtils.toChar(rootName.getPassword()));
            return FtpsClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), username, password,
                    rootName.getPath(), getFileSystemOptions());
        } finally {
            UserAuthenticatorUtils.cleanup(authData);
        }
    }

    @Override
    protected FTPSClient createClient(final GenericFileName rootName, final UserAuthenticationData authData) throws
            FileSystemException {
        return FtpsClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), UserAuthenticatorUtils
                .getData(authData, UserAuthenticationData.USERNAME, UserAuthenticatorUtils.toChar(rootName
                        .getUserName())), UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                UserAuthenticatorUtils.toChar(rootName.getPassword())), rootName.getPath(), getFileSystemOptions());
    }
}
