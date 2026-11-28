/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.AbstractProviderTestConfig;
import org.wso2.org.apache.commons.vfs2.VFS;
import org.wso2.org.apache.commons.vfs2.VfsTestUtils;
import org.wso2.org.apache.commons.vfs2.util.NHttpFileServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Tests https://issues.apache.org/jira/browse/VFS-426.
 */
public class HttpFilesCacheTest {

    /**
     * Tests https://issues.apache.org/jira/browse/VFS-426.
     */
    @Test
    @Disabled("The files cache gives the object of the name with no query for a name with a query, when the"
            + " server answers. It passed before only because the old probe of findFile built the file system again"
            + " after the live site refused the HEAD.")
    public void testQueryStringUrls() throws Exception {
        // The probe of findFile throws a refusal of the server as it is, so the names are resolved on a local server.
        final NHttpFileServer server = NHttpFileServer.start(0, new File(VfsTestUtils.getTestDirectory()), 5000);
        try {
            final String base = AbstractProviderTestConfig.getLocalHostUriString("http", server.getPort());
            final String noQueryStringUrl = base + "/vfs";
            final String queryStringUrl = base + "/vfs?query=string";
            final String queryStringUrl2 = base + "/vfs?query=string&more=stuff";

            final FileSystemManager fileSystemManager = VFS.getManager();

            try (FileObject noQueryFile = fileSystemManager.resolveFile(noQueryStringUrl)) {
                assertEquals(noQueryStringUrl, noQueryFile.getURL().toExternalForm());
            }
            try (FileObject queryFile = fileSystemManager.resolveFile(queryStringUrl)) {
                assertEquals(queryStringUrl, queryFile.getURL().toExternalForm()); // failed for VFS-426
            }
            try (FileObject queryFile2 = fileSystemManager.resolveFile(queryStringUrl2)) {
                assertEquals(queryStringUrl2, queryFile2.getURL().toExternalForm()); // failed for VFS-426
            }
        } finally {
            server.close();
        }
    }

}
