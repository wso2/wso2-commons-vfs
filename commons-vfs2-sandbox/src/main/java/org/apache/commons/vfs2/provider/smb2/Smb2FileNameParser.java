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
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.provider.HostFileNameParser;
import org.apache.commons.vfs2.provider.VfsComponentContext;

import java.net.URI;

public class Smb2FileNameParser extends HostFileNameParser {

    private static final Smb2FileNameParser INSTANCE = new Smb2FileNameParser();
    // default port for smb newer versions
    private static final int PORT = 445;

    protected Smb2FileNameParser() {

        super(PORT);
    }

    public static Smb2FileNameParser getInstance() {

        return INSTANCE;
    }

    /**
     * Extracts the share share name from URI.
     *
     * @param uri
     * @return share name String
     * @throws FileSystemException
     */
    protected String extractShareName(URI uri) throws FileSystemException {

        String path = uri.getPath();
        path = path.startsWith("/") ? path.substring(1) : path;
        String[] pathParts = path.split("/");
        String share = pathParts[0];
        if (share == null || share.isEmpty()) {
            throw new FileSystemException("vfs.provider.smb2/missing-share-name.error", uri.toString());
        }
        return pathParts[0];
    }

    @Override
    public FileName parseUri(final VfsComponentContext context, final FileName base, final String uri) throws FileSystemException {

        FileName parsedFileName = super.parseUri(context, base, uri);
        String share;
        if (base == null) {
            share = extractShareName(parseURIString(parsedFileName.toString()));
        } else {
            share = extractShareName(parseURIString(base.toString()));
        }

        StringBuilder sb = new StringBuilder();
        Authority auth = extractToPath(parsedFileName.toString(), sb);

        String path;

        if (sb.length() == 0 || (sb.length() == 1 && sb.charAt(0) == '/')) {
            //this must point to the share root
            path = "/" + share;
        } else {
            path = parsedFileName.getPath();
        }

        String relPathFromShare;
        try {
            relPathFromShare = removeShareFromAbsPath(path, share);
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/share-path-extraction.error", path, e.getCause());
        }

        return new Smb2FileName(auth.getScheme(), auth.getHostName(), auth.getPort(), PORT,
                auth.getUserName(), auth.getPassword(), relPathFromShare, parsedFileName.getType(), share);
    }

    /**
     * Parse string to URI.
     *
     * @param uriString uri
     * @return URI for path
     * @throws FileSystemException
     */
    public URI parseURIString(String uriString) throws FileSystemException {

        try {
            return new URI(uriString);
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.url/badly-formed-uri.error", uriString, e.getCause());
        }
    }

    /**
     * Remove share information from absolute path.
     *
     * @param path absolute path
     * @param shareName String
     * @return String
     * @throws Exception
     */
    public String removeShareFromAbsPath(String path, String shareName) throws Exception {

        if (shareName == null || shareName.length() == 0) {
            throw new Exception("No path provided!");
        }
        String modifiedPath = path.startsWith("/") ? path.substring(1) : path;

        if (!modifiedPath.substring(0, shareName.length()).equals(shareName)) {
            throw new IllegalArgumentException("Share does not match the provided path");
        }
        modifiedPath = modifiedPath.substring(shareName.length());

        if (modifiedPath.equals("") || modifiedPath.equals("/")) {
            return "";
        }
        return modifiedPath;
    }
}
