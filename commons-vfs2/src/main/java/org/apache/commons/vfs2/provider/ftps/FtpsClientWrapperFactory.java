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
 * under the License.
 *
 */
package org.apache.commons.vfs2.provider.ftps;

import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.ftp.ClientWrapperFactory;
import org.apache.commons.vfs2.provider.ftp.FtpClient;

/**
 * FTPS client wrapper creation factory class.
 */
public class FtpsClientWrapperFactory implements ClientWrapperFactory {

    private final GenericFileName root;
    private final FileSystemOptions fileSystemOptions;
    private final Integer defaultTimeout;

    FtpsClientWrapperFactory(GenericFileName root, FileSystemOptions fileSystemOptions, Integer defaultTimeout) {
        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public FtpClient create() throws FileSystemException {
        return new FtpsClientWrapper(root, fileSystemOptions, defaultTimeout);
    }
}
