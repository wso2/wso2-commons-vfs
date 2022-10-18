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

package org.apache.commons.vfs2.provider.smb2;

import com.hierynomus.smbj.SMBClient;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.GenericFileName;

public class Smb2WrapperClientFactory {
    private final GenericFileName root;
    private final FileSystemOptions fileSystemOptions;

    Smb2WrapperClientFactory (GenericFileName root, FileSystemOptions fileSystemOptions) {
        this.root = root;
        this.fileSystemOptions = fileSystemOptions;
    }

    public SMBClient create() throws FileSystemException {
        return new Smb2ClientWrapper(root, fileSystemOptions);
    }
}
