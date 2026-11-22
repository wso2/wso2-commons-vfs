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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.HostFileNameParser;
import org.wso2.org.apache.commons.vfs2.provider.URLFileName;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import org.wso2.org.apache.commons.vfs2.provider.VfsComponentContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Smb2FileNameParser extends HostFileNameParser {

    private static final Smb2FileNameParser INSTANCE = new Smb2FileNameParser();
    // default port for smb newer versions
    private static final int PORT = 445;

    private static final char[] RESERVED_CHARS = { '#' };

    List<Character> validPasswordChars;

    protected Smb2FileNameParser() {

        super(PORT);

        // rfc3986
        validPasswordChars = new ArrayList<Character>(
                Arrays.asList('/', '-', '.', '_', '~', '%', '!', '$', '&', '\'', '(', ')',
                        '*', '+', ',', ';', '=', ':', '@'));
        for (char c = 'a'; c <= 'z'; c++) {
            validPasswordChars.add(c);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            validPasswordChars.add(c);
        }
        for (char c = '0'; c <= '9'; c++) {
            validPasswordChars.add(c);
        }
    }

    public static Smb2FileNameParser getInstance() {

        return INSTANCE;
    }

    @Override
    public boolean encodeCharacter(final char ch) {
        return super.encodeCharacter(ch) || !validPasswordChars.contains(ch);
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

        final GenericFileName parsed = (GenericFileName) super.parseUri(context, base, uri);
        String path = parsed.getPath();
        final String share;
        String query = parsed instanceof URLFileName ? ((URLFileName) parsed).getQueryString() : null;
        if (base instanceof Smb2FileName) {
            final Smb2FileName parent = (Smb2FileName) base;
            share = parent.getShareName();
            if (query == null) {
                query = parent.getQueryString();
            }
        } else {
            final String sharePath = base == null ? path : base.getPath();
            final String first = sharePath.startsWith("/") ? sharePath.substring(1) : sharePath;
            final int separator = first.indexOf('/');
            share = separator < 0 ? first : first.substring(0, separator);
            if (share.isEmpty()) {
                throw new FileSystemException("vfs.provider.smb2/missing-share-name.error", parsed.getFriendlyURI());
            }
        }
        if (path.isEmpty() || "/".equals(path)) {
            path = "/" + share;
        }
        try {
            path = removeShareFromAbsPath(path, share);
        } catch (Exception e) {
            throw new FileSystemException("vfs.provider.smb2/share-path-extraction.error", path, e);
        }
        return new Smb2FileName(parsed.getScheme(), parsed.getHostName(), parsed.getPort(), PORT,
                parsed.getUserName(), parsed.getPassword(), path, parsed.getType(), share, query);
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
            uriString = UriParser.encode(uriString, RESERVED_CHARS);
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
