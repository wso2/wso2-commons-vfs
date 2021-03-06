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

package org.apache.commons.vfs2.provider.smb2;

import org.apache.commons.vfs2.FileObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * This class wraps the InputStream ana vfs FileObject.
 * Used to close inputstream and fileobject
 */
public class Smb2InputStreamWrapper extends InputStream {

    private InputStream inputStream;
    private final FileObject fileObject;

    public Smb2InputStreamWrapper(InputStream inputStream, final FileObject fileObject) {

        this.inputStream = inputStream;
        this.fileObject = fileObject;
    }

    @Override
    public int read() throws IOException {

        return inputStream.read();
    }

    @Override
    public void close() throws IOException
    {
        inputStream.close();
        fileObject.close();
    }
}
