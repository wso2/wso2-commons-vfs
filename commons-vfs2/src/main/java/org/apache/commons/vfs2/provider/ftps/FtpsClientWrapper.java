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
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.ftp.AbstractFtpClientWrapper;
import org.apache.commons.vfs2.provider.ftp.FtpFileProvider;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.io.IOException;

/**
 * FTPS client wrapper class with FTPS client creation logic.
 */
class FtpsClientWrapper extends AbstractFtpClientWrapper {

    private static final Log log = LogFactory.getLog(FtpsClientWrapper.class);

    FtpsClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions,
                      Integer connectionTimeout) throws FileSystemException {
        super(root, fileSystemOptions, connectionTimeout);
    }

    @Override
    protected FTPSClient createClient() throws FileSystemException {
        final GenericFileName rootName = getRoot();
        UserAuthenticationData authData = null;
        try {
            authData = UserAuthenticatorUtils.authenticate(getFileSystemOptions(), FtpFileProvider.AUTHENTICATOR_TYPES);
            char[] userName = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
                                                             UserAuthenticatorUtils.toChar(rootName.getUserName()));
            char[] password = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                                                             UserAuthenticatorUtils.toChar(rootName.getPassword()));

            FTPSClient ftpsClient = FtpsClientFactory.createConnection(rootName.getHostName(), rootName.getPort(),
                                                                       userName, password,
                                                                       rootName.getPath(), getFileSystemOptions());

            FtpsDataChannelProtectionLevel level =
                    FtpsFileSystemConfigBuilder.getInstance().getDataChannelProtectionLevel(getFileSystemOptions());
            if (level != null) {
                try {
                    ftpsClient.execPBSZ(0);
                    ftpsClient.execPROT(level.name());
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }

            return ftpsClient;
        } finally {
            UserAuthenticatorUtils.cleanup(authData);
        }
    }

}
