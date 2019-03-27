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

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.URLFileName;
import org.apache.commons.vfs2.provider.sftp.SftpConstants;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * FTP client wrapper class with FTP client creation logic.
 */
class FtpClientWrapper extends AbstractFtpClientWrapper {
    FtpClientWrapper(final GenericFileName root, final FileSystemOptions fileSystemOptions,
                     Integer defaultTimeout) throws FileSystemException {
        super(root, fileSystemOptions, defaultTimeout);
    }

    protected FTPClient createClient() throws FileSystemException {
        final GenericFileName rootName = getRoot();
        Map<String, String> mParams = null;
        if (rootName instanceof URLFileName) {
            mParams = getQueryParams((URLFileName) rootName);
        }
        UserAuthenticationData authData = null;
        try {
            authData = UserAuthenticatorUtils.authenticate(getFileSystemOptions(), FtpFileProvider.AUTHENTICATOR_TYPES);

            char[] username = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
                                                             UserAuthenticatorUtils.toChar(rootName.getUserName()));

            char[] password = UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                                                             UserAuthenticatorUtils.toChar(rootName.getPassword()));

            if (mParams == null) {

                return FtpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), username, password,
                                                         rootName.getPath(), getFileSystemOptions());
            } else {

                return FtpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(), username, password,
                                                         rootName.getPath(), getFileSystemOptions(),
                                                         mParams.get(SftpConstants.PROXY_SERVER),
                                                         mParams.get(SftpConstants.PROXY_PORT),
                                                         mParams.get(SftpConstants.PROXY_USERNAME),
                                                         mParams.get(SftpConstants.PROXY_PASSWORD),
                                                         mParams.get(SftpConstants.TIMEOUT),
                                                         mParams.get(SftpConstants.RETRY_COUNT));
            }
        } finally {
            UserAuthenticatorUtils.cleanup(authData);
        }
    }

    private Map<String, String> getQueryParams(URLFileName urlFileName) {
        Map<String, String> mQueryParams = new HashMap<>();
        String strQuery = urlFileName.getQueryString();
        if (strQuery != null && !strQuery.equals("")) {
            for (String strParam : strQuery.split("&")) {
                String[] arrParam = strParam.split("=");
                if (arrParam.length >= 2) {
                    mQueryParams.put(arrParam[0], arrParam[1]);
                }
            }
        }
        return mQueryParams;
    }
}
