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

import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileType;
import org.apache.commons.vfs2.provider.GenericFileName;

public class Smb2FileName extends GenericFileName {

    private final String shareName;
    private String rootUri;
    private String uri;

    protected Smb2FileName(String scheme, String hostName, int port, int defaultPort,
                           String userName, String password, String path, FileType type, String shareName) {

        super(scheme, hostName, port, defaultPort, userName, password, path, type);
        this.shareName = shareName;
        createURI();
    }

    /**
     * Returns share name of the file.
     *
     * @return String share name
     */
    public String getShareName() {

        return shareName;
    }

    @Override
    public String getFriendlyURI() {

        return createURI( false);
    }

    @Override
    public String getURI() {

        if (uri == null) {
            uri = createURI();
        }
        return uri;
    }

    protected String createURI() {

        return createURI(true);
    }

    /**
     * Create URI with share name.
     *
     * @param usePassword
     * @return uri
     */
    private String createURI(final boolean usePassword) {

        StringBuilder sb = new StringBuilder();
        appendRootUri(sb, usePassword);
        if (sb.charAt(sb.length() - 1) != '/') {
            sb.append('/');
        }
        sb.append(shareName);
        String path = getPath();
        if (!(path == null || path.equals("/"))) {
            if (!path.startsWith("/")) {
                sb.append('/');
            }
            sb.append(path);
        }
        return sb.toString();
    }

    @Override
    public String getRootURI() {

        if (this.rootUri == null) {
            String uri = super.getRootURI();
            this.rootUri = uri + shareName;
        }
        return this.rootUri;
    }

    @Override
    public FileName getParent() {

        if (this.rootUri == null) {
            getRootURI();
        }
        String path = getPath();
        if (path.replaceAll("/", "").equals(shareName) || path.equals("/") || path.equals("")) {
            return null; //if this method is called from the root name, return null because there is no parent
        } else {
            return new Smb2FileName(this.getScheme(), this.getHostName(), this.getPort(),
                    this.getDefaultPort(), this.getUserName(), this.getPassword(),
                    path.substring(0, path.lastIndexOf("/")), this.getType(), shareName);
        }
    }
}
