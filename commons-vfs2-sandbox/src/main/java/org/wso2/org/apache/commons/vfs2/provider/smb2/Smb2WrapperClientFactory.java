/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import com.hierynomus.smbj.SMBClient;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import java.util.function.Supplier;

public class Smb2WrapperClientFactory {
    private final GenericFileName root;
    private final FileSystemOptions fileSystemOptions;
    // The manager makes the group of each new client, so an abort of the manager reaches every socket of it.
    private final Supplier<SocketGroup> socketGroups;

    Smb2WrapperClientFactory (GenericFileName root, FileSystemOptions fileSystemOptions,
            Supplier<SocketGroup> socketGroups) {
        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
        this.socketGroups = socketGroups;
    }

    public SMBClient create() throws FileSystemException {
        return new Smb2ClientWrapper(root, fileSystemOptions, socketGroups.get());
    }
}