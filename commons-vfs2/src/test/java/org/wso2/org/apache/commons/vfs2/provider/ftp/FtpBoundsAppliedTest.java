/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Bound;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsClientFactory;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * The FTP and FTPS clients carry the values that {@link EffectiveBounds} reports, with options set, with nothing set
 * and with an explicit zero.
 */
public class FtpBoundsAppliedTest {

    private static final char[] USER = ScriptedFtpServer.USER.toCharArray();

    private static final char[] PASSWORD = ScriptedFtpServer.PASSWORD.toCharArray();

    @TempDir
    File home;

    private ScriptedFtpServer server;

    @AfterEach
    public void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void ftpClientCarriesAnExplicitZero() throws Exception {
        server = ScriptedFtpServer.plain(home());
        final FtpFileSystemConfigBuilder builder = FtpFileSystemConfigBuilder.getInstance();
        final FileSystemOptions zero = new FileSystemOptions();
        builder.setConnectTimeout(zero, Duration.ZERO);
        builder.setSoTimeout(zero, Duration.ZERO);
        builder.setDataTimeout(zero, Duration.ZERO);
        final EffectiveBounds bounds = EffectiveBounds.of("ftp", zero);
        assertBound(0, Source.ENDPOINT, bounds.getConnectTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getSocketTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getDataTimeout());
        final FTPClient client = FtpClientFactory.createConnection("127.0.0.1", server.getPort(), USER, PASSWORD,
                null, zero, null, null, null, null, null, null, new SocketGroup(), System.nanoTime());
        try {
            assertCarries(bounds, client);
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void ftpsClientCarriesTheBoundsInEffect() throws Exception {
        server = ScriptedFtpServer.tls(home(), false);
        final FtpsFileSystemConfigBuilder builder = FtpsFileSystemConfigBuilder.getInstance();
        final FileSystemOptions set = new FileSystemOptions();
        builder.setConnectTimeout(set, Duration.ofMillis(15000));
        builder.setSoTimeout(set, Duration.ofMillis(20000));
        builder.setDataTimeout(set, Duration.ofMillis(25000));
        final FileSystemOptions zero = new FileSystemOptions();
        builder.setSoTimeout(zero, Duration.ZERO);
        builder.setDataTimeout(zero, Duration.ZERO);
        for (final FileSystemOptions options : new FileSystemOptions[] {set, new FileSystemOptions(), zero}) {
            final EffectiveBounds bounds = EffectiveBounds.of("ftps", options);
            final FTPClient client = ftps(options);
            try {
                assertCarries(bounds, client);
            } finally {
                client.disconnect();
            }
        }
        assertEquals(Source.ENDPOINT, EffectiveBounds.of("ftps", set).getSocketTimeout().getSource());
        assertBound(60000, Source.DEFAULT, EffectiveBounds.of("ftps", new FileSystemOptions()).getSocketTimeout());
        assertBound(0, Source.ENDPOINT, EffectiveBounds.of("ftps", zero).getSocketTimeout());
    }

    private FTPClient ftps(final FileSystemOptions options) throws FileSystemException {
        return FtpsClientFactory.createConnection("127.0.0.1", server.getPort(), USER, PASSWORD, null, options,
                new SocketGroup(), System.nanoTime());
    }

    private static void assertCarries(final EffectiveBounds bounds, final FTPClient client) throws SocketException {
        assertEquals(bounds.getConnectTimeout().getValue(), client.getConnectTimeout());
        assertEquals(bounds.getConnectTimeout().getValue(), client.getDefaultTimeout());
        assertEquals(bounds.getSocketTimeout().getValue(), client.getSoTimeout());
        assertEquals(bounds.getDataTimeout().getValue(), client.getDataTimeout().toMillis());
    }

    private File home() throws Exception {
        Files.write(new File(home, "small.txt").toPath(), "small file".getBytes(StandardCharsets.UTF_8));
        return home;
    }

    private static void assertBound(final long value, final Source source, final Bound bound) {
        assertEquals(value, bound.getValue(), String.valueOf(bound));
        assertEquals(source, bound.getSource(), String.valueOf(bound));
    }
}
