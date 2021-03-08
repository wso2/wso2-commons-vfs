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

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.AbstractOriginatingFileProvider;
import org.apache.commons.vfs2.provider.GenericFileName;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class Smb2FileProvider extends AbstractOriginatingFileProvider {

    /**
     * Authenticator types.
     */
    public static final UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = new UserAuthenticationData.Type[]
            {UserAuthenticationData.USERNAME, UserAuthenticationData.PASSWORD};

    static final Collection<Capability> capabilities = Collections.unmodifiableCollection(
            Arrays.asList(Capability.CREATE, Capability.DELETE, Capability.RENAME, Capability.GET_TYPE,
                          Capability.LIST_CHILDREN, Capability.READ_CONTENT, Capability.GET_LAST_MODIFIED,
                          Capability.URI, Capability.WRITE_CONTENT, Capability.APPEND_CONTENT));

    public Smb2FileProvider() {

        super();
        setFileNameParser(Smb2FileNameParser.getInstance());
    }

    @Override
    protected FileSystem doCreateFileSystem(FileName name, FileSystemOptions fileSystemOptions) throws FileSystemException {

        final GenericFileName rootName = (GenericFileName) name;
        final Smb2ClientWrapper smbClient = new Smb2ClientWrapper(rootName, fileSystemOptions);
        return new Smb2FileSystem(rootName, fileSystemOptions, smbClient);
    }

    @Override
    public Collection<Capability> getCapabilities() {

        return capabilities;
    }

    @Override
    public FileName parseUri(final FileName base, final String uri) throws FileSystemException {

        if (getFileNameParser() != null) {
            return getFileNameParser().parseUri(getContext(), base, uri);
        }
        throw new FileSystemException("vfs.provider/filename-parser-missing.error");
    }
}
