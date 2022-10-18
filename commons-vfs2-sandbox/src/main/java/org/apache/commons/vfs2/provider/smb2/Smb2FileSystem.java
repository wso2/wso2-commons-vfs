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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.AbstractFileName;
import org.apache.commons.vfs2.provider.AbstractFileSystem;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

public class Smb2FileSystem extends AbstractFileSystem {

    // making the client thread safe
    private final AtomicReference<SMBClient> client = new AtomicReference<>();

    private volatile boolean isClosed = false;
    private static final Log LOG = LogFactory.getLog(Smb2FileSystem.class);

    private final Smb2WrapperClientFactory clientWrapperFactory;

    protected Smb2FileSystem(FileName rootName, FileSystemOptions fileSystemOptions, Smb2WrapperClientFactory wrapperClientFactory) throws FileSystemException {

        super(rootName, null, fileSystemOptions);
        this.clientWrapperFactory = wrapperClientFactory;
        client.set(this.clientWrapperFactory.create());
    }

    @Override
    public void doCloseCommunicationLink() {
        isClosed = true;
        SMBClient smb2FSClient = client.getAndSet(null);
        // Client could be null if the file system has already been closed.
        // Some file objects may be having references to this already closed FS, if they have not been re-resolved after FS closure.
        // Those file objects will attempt to close this again, causing NPE's.
        if (smb2FSClient != null) {
            smb2FSClient.close();
        } else {
            LOG.debug("Attempting to close already closed client.");
        }
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
    public synchronized SMBClient getClient() throws FileSystemException {
        SMBClient existingClient = client.getAndSet(null);
        if (existingClient == null) {
            existingClient = this.clientWrapperFactory.create();
        }
        return existingClient;
    }

    /**
     * Set the smbclient back to atomic reference.
     *
     * @param smbClient
     */
    public void putClient(SMBClient smbClient) {
        if (isClosed) {
            smbClient.close();
        } else if (!client.compareAndSet(null, smbClient)) {
            // An idle client is already present so close the connection.
            smbClient.close();
        }
    }
}
