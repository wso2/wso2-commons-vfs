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

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.share.DiskEntry;
import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileSystem;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

public class Smb2FileSystem extends AbstractFileSystem {

    // making the client thread safe
    private final AtomicReference<SMBClient> client = new AtomicReference<>();

    protected Smb2FileSystem(FileName rootName, FileSystemOptions fileSystemOptions, SMBClient smbClient) {

        super(rootName, null, fileSystemOptions);
        client.set(smbClient);
    }

    @Override
    protected FileObject createFile(AbstractFileName name) {

        return new Smb2FileObject(name, this, getRootName());
    }

    @Override
    protected void addCapabilities(Collection<Capability> caps) {

        caps.addAll(Smb2FileProvider.capabilities);
    }

    /**
     * Returns the SMB client thread safe.
     *
     * @return SMBClient
     */
    public SMBClient getClient() {

        return client.getAndSet(null);
    }

    /**
     * Set the smbclient back to atomic reference.
     *
     * @param smbClient
     */
    public void putClient(SMBClient smbClient) {

        client.set(smbClient);
    }

    /**
     * Get write handle for file.
     *
     * @param path file path
     * @return DiskEntry
     */
    public DiskEntry getDiskEntryWrite(String path) {

        return ((Smb2ClientWrapper) client.get()).getDiskEntryWrite(path);
    }

    /**
     * Get read handle for file.
     *
     * @param path file path
     * @return DiskEntry
     */
    public DiskEntry getDiskEntryRead(String path) {

        return ((Smb2ClientWrapper) client.get()).getDiskEntryRead(path);
    }

    /**
     * Returns the write handle to folder.
     *
     * @param path folder path
     * @return DiskEntry
     */
    public DiskEntry getDiskEntryFolderWrite(String path) {

        return ((Smb2ClientWrapper) client.get()).getDiskEntryFolderWrite(path);
    }
}
