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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsMode;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.provider.FileNameParser;
import org.wso2.org.apache.commons.vfs2.provider.GenericFileName;
import org.wso2.org.apache.commons.vfs2.provider.HostFileNameParser;
import org.wso2.org.apache.commons.vfs2.provider.URLFileName;

/**
 * Tests https://issues.apache.org/jira/browse/VFS-793.
 *
 * See also https://issues.apache.org/jira/browse/VFS-810.
 */
public class FtpFileNameParserTest {
    @Test
    public void ftpsRetainsTheQueryOutsideThePath() throws Exception {
        final String uri = "ftps://ftpuser:ftppass@host:21/in/file.json";
        final FileNameParser parser = HostFileNameParser.getInstance();
        final FileName plain = parser.parseUri(null, null, uri);
        final FileName selected = parser.parseUri(null, null, uri + "?vfs.passive=true");
        assertEquals("/in/file.json", selected.getPath());
        assertEquals(plain.getPath(), selected.getPath());
        assertFalse(plain instanceof URLFileName);
        assertEquals("vfs.passive=true", ((URLFileName) selected).getQueryString());
        assertEquals("vfs.passive=true",
                ((URLFileName) parser.parseUri(null, selected, uri)).getQueryString());
    }


    @Test
    public void listedQuestionMarkAndItsNeighborKeepTheirWireNames(@TempDir final Path directory) throws Exception {
        final Set<String> expected = Set.of("q", "q?.dat", "q%3F.dat");
        for (final String name : expected) {
            Files.write(directory.resolve(name), name.getBytes(StandardCharsets.UTF_8));
        }
        for (final String scheme : new String[] {"ftp", "ftps"}) {
            final boolean tls = "ftps".equals(scheme);
            try (ScriptedFtpServer server = tls ? ScriptedFtpServer.tls(directory.toFile(), false, "TLSv1.2")
                    : ScriptedFtpServer.plain(directory.toFile());
                    StandardFileSystemManager manager = new StandardFileSystemManager()) {
                manager.init();
                final FileSystemOptions options = new FileSystemOptions();
                if (tls) {
                    FtpsFileSystemConfigBuilder.getInstance().setFtpsMode(options, FtpsMode.EXPLICIT);
                    FtpsFileSystemConfigBuilder.getInstance().setPassiveMode(options, true);
                } else {
                    FtpFileSystemConfigBuilder.getInstance().setPassiveMode(options, true);
                }
                final Set<String> names = new HashSet<>();
                final String query = "vfs.passive=true";
                final FileObject folder = manager.resolveFile(server.uri(scheme, "/?" + query), options);
                for (final FileObject child : folder.getChildren()) {
                    final FileName name = child.getName();
                    final String literal = name.getPathDecoded().substring(1);
                    names.add(literal);
                    assertEquals(query, ((URLFileName) name).getQueryString());
                    assertEquals(name.getPathDecoded(), manager.resolveURI(name.getURI()).getPathDecoded());
                    assertEquals(literal, new String(child.getContent().getByteArray(), StandardCharsets.UTF_8));
                }
                assertEquals(expected, names);
                final FileName direct = manager.resolveURI(server.uri(scheme, "/q%3F.dat"));
                assertEquals("/q?.dat", manager.resolveURI(direct.getURI()).getPathDecoded());
            }
        }
    }

    @Test
    public void testGenericFileName1() throws Exception {
        final String uri = "ftp://blanks:blanks@localhost/path/file_b%20lanks";
        final FileName n = FtpFileNameParser.getInstance().parseUri(null, null, uri);
        assertInstanceOf(GenericFileName.class, n);
        final String genericUri = n.getURI();
        assertEquals(genericUri, uri.toString());
    }

    @Test
    public void testGenericFileName2() throws Exception {
        final String uri = "ftp://b%20lanks:b%20lanks@localhost/path/file";
        final FileName n = FtpFileNameParser.getInstance().parseUri(null, null, uri);
        assertInstanceOf(GenericFileName.class, n);
        final String genericUri = n.getURI();
        assertEquals(genericUri, uri.toString());
    }

}
