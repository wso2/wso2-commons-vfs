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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/**
 * The SSH algorithms of one endpoint go into the config of its own session only. An endpoint with no value keeps the
 * defaults of JSch.
 */
public class SftpAlgorithmsTest {

    private static final SftpFileSystemConfigBuilder BUILDER = SftpFileSystemConfigBuilder.getInstance();

    private static SftpTestServer server;

    @BeforeAll
    public static void startServer() throws Exception {
        server = new SftpTestServer();
        server.write("in/small.txt", "small");
    }

    @AfterAll
    public static void stopServer() throws Exception {
        server.close();
    }

    private static StandardFileSystemManager manager() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        return manager;
    }

    // Options with no algorithm, so the session takes the defaults of JSch.
    private static FileSystemOptions noAlgorithms() throws FileSystemException {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setStrictHostKeyChecking(options, "no");
        BUILDER.setUserInfo(options, new TrustEveryoneUserInfo());
        BUILDER.setKnownHosts(options, TestServerOptions.knownHosts());
        return options;
    }

    @Test
    public void fiveSettingsOfOneEndpointReachItsSessionAndASecondEndpointKeepsTheDefaults() throws Exception {
        // The defaults of JSch offer nothing that the old test server speaks. So for this test alone the defaults get
        // one key exchange and two host key types more, and they are put back exactly after.
        final String kex = JSch.getConfig("kex");
        final String hostKeys = JSch.getConfig("server_host_key");
        JSch.setConfig("kex", kex + ",diffie-hellman-group14-sha1");
        JSch.setConfig("server_host_key", hostKeys + ",ssh-rsa,ssh-dss");
        try {
            fiveSettingsAndTheDefaults();
        } finally {
            JSch.setConfig("kex", kex);
            JSch.setConfig("server_host_key", hostKeys);
        }
        assertEquals(kex, JSch.getConfig("kex"));
        assertEquals(hostKeys, JSch.getConfig("server_host_key"));
    }

    private void fiveSettingsAndTheDefaults() throws Exception {
        final FileSystemOptions set = SftpTestServer.options();
        BUILDER.setKeyExchangeAlgorithm(set, "diffie-hellman-group14-sha1");
        BUILDER.setHostKeyAlgorithms(set, "ssh-dss,ssh-rsa");
        BUILDER.setCiphers(set, "aes128-ctr");
        BUILDER.setMacs(set, "hmac-sha1");
        BUILDER.setPubkeyAcceptedAlgorithms(set, "ssh-rsa");
        try (StandardFileSystemManager manager = manager()) {
            final FileObject first = manager.resolveFile(server.uri("in/small.txt"), set);
            assertTrue(first.exists());
            final Session session = SftpTimeoutLadderTest.sessionOf(first);
            assertEquals("diffie-hellman-group14-sha1", session.getConfig("kex"));
            assertEquals("ssh-dss,ssh-rsa", session.getConfig("server_host_key"));
            assertEquals("aes128-ctr", session.getConfig("cipher.s2c"));
            assertEquals("aes128-ctr", session.getConfig("cipher.c2s"));
            assertEquals("hmac-sha1", session.getConfig("mac.s2c"));
            assertEquals("hmac-sha1", session.getConfig("mac.c2s"));
            assertEquals("ssh-rsa", session.getConfig("PubkeyAcceptedAlgorithms"));

            final FileObject second = manager.resolveFile(server.uri("in/small.txt"), noAlgorithms());
            assertTrue(second.exists());
            final Session other = SftpTimeoutLadderTest.sessionOf(second);
            assertEquals(JSch.getConfig("kex"), other.getConfig("kex"));
            assertEquals(JSch.getConfig("cipher.c2s"), other.getConfig("cipher.c2s"));
            assertEquals(JSch.getConfig("mac.c2s"), other.getConfig("mac.c2s"));
            assertEquals(JSch.getConfig("server_host_key"), other.getConfig("server_host_key"));
            assertEquals(JSch.getConfig("PubkeyAcceptedAlgorithms"), other.getConfig("PubkeyAcceptedAlgorithms"));
        }
    }

    @Test
    public void fixtureOfTheTestServerLeavesTheDefaultsOfJschAndUsesItsOwnKnownHosts() throws Exception {
        final String kex = JSch.getConfig("kex");
        final String hostKeys = JSch.getConfig("server_host_key");
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(server.uri("in/small.txt"), SftpTestServer.options());
            assertTrue(file.exists());
            final Session session = SftpTimeoutLadderTest.sessionOf(file);
            assertEquals(kex + ",diffie-hellman-group14-sha1", session.getConfig("kex"));
            assertEquals(hostKeys + ",ssh-rsa,ssh-dss", session.getConfig("server_host_key"));
        }
        assertEquals(kex, JSch.getConfig("kex"));
        assertEquals(hostKeys, JSch.getConfig("server_host_key"));
        assertFalse(kex.contains("diffie-hellman-group14-sha1,") || kex.endsWith("diffie-hellman-group14-sha1"),
            kex);
        // The host key of the test server went into the file of the fixture, not into a file of the user.
        final String hosts = new String(Files.readAllBytes(TestServerOptions.knownHosts().toPath()),
            StandardCharsets.UTF_8);
        assertTrue(hosts.contains("localhost") || hosts.contains("127.0.0.1"), hosts);
    }

    @Test
    public void cipherThatTheServerDoesNotOfferFailsTheHandshake() throws Exception {
        final FileSystemOptions set = SftpTestServer.options();
        BUILDER.setCiphers(set, "aes256-gcm@openssh.com");
        try (StandardFileSystemManager manager = manager()) {
            assertThrows(FileSystemException.class, () -> manager.resolveFile(server.uri("in/small.txt"), set));
        }
    }
}
