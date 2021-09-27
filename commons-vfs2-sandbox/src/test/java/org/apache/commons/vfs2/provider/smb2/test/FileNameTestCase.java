/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.apache.commons.vfs2.provider.smb2.test;

import org.apache.commons.AbstractVfsTestCase;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.provider.smb.SmbFileName;
import org.apache.commons.vfs2.provider.smb.SmbFileNameParser;
import org.apache.commons.vfs2.provider.smb2.Smb2FileNameParser;

public class FileNameTestCase extends AbstractVfsTestCase {
    /**
     * Tests parsing a URI into its parts.
     *
     * @throws Exception in case of error
     */
    public void testParseUri() throws Exception {
        // Simple name
        SmbFileName name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null,
                "smb2://hostname/share/file");
        assertEquals("smb2", name.getScheme());
        assertNull(name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals(name.getDefaultPort(), name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file", name.getPath());
        assertEquals("smb2://hostname/share/", name.getRootURI());
        assertEquals("smb2://hostname/share/file", name.getURI());

        // Name with port
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null, "smb2://hostname:9090/share/file");
        assertEquals("smb2", name.getScheme());
        assertNull(name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(9090, name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file", name.getPath());
        assertEquals("smb2://hostname:9090/share/", name.getRootURI());
        assertEquals("smb2://hostname:9090/share/file", name.getURI());

        // Name with no path
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null, "smb2://hostname/share");
        assertEquals("smb2", name.getScheme());
        assertNull(name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/", name.getPath());
        assertEquals("smb2://hostname/share/", name.getRootURI());
        assertEquals("smb2://hostname/share/", name.getURI());

        // Name with username
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null, "smb2://user@hostname/share/file");
        assertEquals("smb2", name.getScheme());
        assertEquals("user", name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file", name.getPath());
        assertEquals("smb2://user@hostname/share/", name.getRootURI());
        assertEquals("smb2://user@hostname/share/file", name.getURI());

        // Name with extension
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null, "smb2://user@hostname/share/file" +
                ".txt");
        assertEquals("smb2", name.getScheme());
        assertEquals("user", name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file.txt", name.getPath());
        assertEquals("file.txt", name.getBaseName());
        assertEquals("txt", name.getExtension());
        assertEquals("smb2://user@hostname/share/", name.getRootURI());
        assertEquals("smb2://user@hostname/share/file.txt", name.getURI());

        // Name look likes extension, but isnt
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null, "smb2://user@hostname/share/.bashrc");
        assertEquals("smb2", name.getScheme());
        assertEquals("user", name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/.bashrc", name.getPath());
        assertEquals(".bashrc", name.getBaseName());
        assertEquals("", name.getExtension());
        assertEquals("smb2://user@hostname/share/", name.getRootURI());
        assertEquals("smb2://user@hostname/share/.bashrc", name.getURI());

        // Name with spaces
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null,
                "smb2://hostname/share/file file");
        assertEquals("smb2", name.getScheme());
        assertNull(name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals(name.getDefaultPort(), name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file file", name.getPath());
        assertEquals("smb2://hostname/share/", name.getRootURI());
        assertEquals("smb2://hostname/share/file file", name.getURI());
        
        // Name with hashes
        name = (SmbFileName) SmbFileNameParser.getInstance().parseUri(null, null,
                "smb2://hostname/share/file#file");
        assertEquals("smb2", name.getScheme());
        assertNull(name.getUserName());
        assertNull(name.getPassword());
        assertEquals("hostname", name.getHostName());
        assertEquals(139, name.getPort());
        assertEquals(name.getDefaultPort(), name.getPort());
        assertEquals("share", name.getShare());
        assertEquals("/file#file", name.getPath());
        assertEquals("smb2://hostname/share/", name.getRootURI());
        assertEquals("smb2://hostname/share/file#file", name.getURI());
    }
    
    /**
     * Tests error handling in URI parser.
     *
     * @throws Exception in case of error
     */
    public void testBadlyFormedUri() throws Exception {
        // Does not start with smb://
        testBadlyFormedUri("smb2:", "vfs.provider/missing-double-slashes.error");
        testBadlyFormedUri("smb2:/", "vfs.provider/missing-double-slashes.error");
        testBadlyFormedUri("smb2:a", "vfs.provider/missing-double-slashes.error");

        // Missing hostname
        testBadlyFormedUri("smb2://", "vfs.provider/missing-hostname.error");
        testBadlyFormedUri("smb2://:21/share", "vfs.provider/missing-hostname.error");
        testBadlyFormedUri("smb2:///share", "vfs.provider/missing-hostname.error");

        // Empty port
        testBadlyFormedUri("smb2://host:", "vfs.provider/missing-port.error");
        testBadlyFormedUri("smb2://host:/share", "vfs.provider/missing-port.error");
        testBadlyFormedUri("smb2://host:port/share/file", "vfs.provider/missing-port.error");

        // Missing absolute path
        testBadlyFormedUri("smb2://host:90a", "vfs.provider/missing-hostname-path-sep.error");
        testBadlyFormedUri("smb2://host?a", "vfs.provider/missing-hostname-path-sep.error");
    }

    /**
     * Assert that parsing a URI fails with the expected error.
     */
    private void testBadlyFormedUri(final String uri, final String errorMsg) {
        try {
            Smb2FileNameParser.getInstance().parseUri(null, null, uri);
            fail();
        } catch (final FileSystemException e) {
            assertSameMessage(errorMsg, uri, e);
        }
    }
}
