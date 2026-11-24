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
package org.wso2.org.apache.commons.vfs2.provider.ftps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.util.TrustManagerUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.ftp.ScriptedFtpServer;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocket;

/**
 * The FTPS data path: a TLS data socket on top of a plain watched socket that resumes the session
 * of the control connection, the protection level that the server accepted last, a refused data connection, and the
 * order of the key and trust managers.
 */
public class FtpsDataChannelTest {

    private static final FtpsFileSystemConfigBuilder BUILDER = FtpsFileSystemConfigBuilder.getInstance();

    private static final byte[] SMALL = "small file over tls".getBytes(StandardCharsets.UTF_8);

    private static final char[] USER = ScriptedFtpServer.USER.toCharArray();

    private static final char[] PASSWORD = ScriptedFtpServer.PASSWORD.toCharArray();

    @TempDir
    File home;

    @TempDir
    File stores;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        Files.write(new File(home, "small.txt").toPath(), SMALL);
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
        if (server != null) {
            server.close();
        }
    }

    private static FileSystemOptions passive() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setPassiveMode(options, true);
        return options;
    }

    private FTPSClientWithSessionResumption connect(final FileSystemOptions options, final SocketGroup group)
            throws FileSystemException {
        return (FTPSClientWithSessionResumption) FtpsClientFactory.createConnection("127.0.0.1", server.getPort(),
                USER, PASSWORD, null, options, group, System.nanoTime());
    }

    private static boolean hasCause(final Throwable error, final Class<?> type) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void dataSocketResumesTheSessionOfTheControlConnectionOnTls12() throws Exception {
        for (final boolean implicit : new boolean[] {false, true}) {
            server = ScriptedFtpServer.tls(home, implicit, "TLSv1.2");
            final FileSystemOptions options = passive();
            BUILDER.setFtpsMode(options, implicit ? FtpsMode.IMPLICIT : FtpsMode.EXPLICIT);
            final SocketGroup group = new SocketGroup();
            final FTPSClientWithSessionResumption client = connect(options, group);
            try {
                assertEquals("P", client.getProtection());
                final Socket data = client._openDataConnection_("LIST", null);
                assertTrue(data instanceof SSLSocket);
                final SSLSocket tls = (SSLSocket) data;
                assertEquals("TLSv1.2", tls.getSession().getProtocol());
                // The context of the client holds one session: the data connection resumed the control session.
                final List<byte[]> ids = Collections.list(tls.getSession().getSessionContext().getIds());
                assertEquals(1, ids.size());
                assertArrayEquals(ids.get(0), tls.getSession().getId());
                // Both connections are plain watched sockets of the group. TLS sits on top of them.
                assertEquals(2, group.getSockets().size());
                assertTrue(group.getSockets().stream().allMatch(socket -> socket instanceof WatchedSocket));
                data.close();
                assertTrue(client.completePendingCommand());
            } finally {
                client.disconnect();
                server.close();
                server = null;
            }
        }
    }

    @Test
    public void listReadAndWriteMoveDataOnEveryModeAndVersion() throws Exception {
        for (final String version : new String[] {"TLSv1.2", "TLSv1.3"}) {
            for (final boolean implicit : new boolean[] {false, true}) {
                server = ScriptedFtpServer.tls(home, implicit, version);
                final FileSystemOptions options = passive();
                BUILDER.setFtpsMode(options, implicit ? FtpsMode.IMPLICIT : FtpsMode.EXPLICIT);
                final String name = "w-" + version + "-" + implicit + ".bin";
                final byte[] body = (name + " body").getBytes(StandardCharsets.UTF_8);
                final FileObject write = manager.resolveFile(server.uri("ftps", "/" + name), options);
                try (OutputStream out = write.getContent().getOutputStream()) {
                    out.write(body);
                }
                assertArrayEquals(body, Files.readAllBytes(new File(home, name).toPath()));
                final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), options);
                assertArrayEquals(SMALL, read.getContent().getByteArray());
                final FileObject root = manager.resolveFile(server.uri("ftps", "/"), options);
                assertTrue(Stream.of(root.getChildren()).anyMatch(child -> child.getName().getBaseName().equals(name)));
                manager.closeFileSystem(root.getFileSystem());
                server.close();
                server = null;
            }
        }
    }

    @Test
    public void protectionLevelCKeepsAClearDataChannel() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = passive();
        BUILDER.setDataChannelProtectionLevel(options, FtpsDataChannelProtectionLevel.C);
        final FTPSClientWithSessionResumption client = connect(options, new SocketGroup());
        try {
            assertEquals("C", client.getProtection());
            final Socket data = client._openDataConnection_("LIST", null);
            assertTrue(data instanceof WatchedSocket);
            data.close();
            client.completePendingCommand();
        } finally {
            client.disconnect();
        }
        final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), options);
        assertArrayEquals(SMALL, read.getContent().getByteArray());
    }

    @Test
    public void refusedProtectionLevelCKeepsP() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        server.reply("PROT", "C", 536, 1);
        final FileSystemOptions options = passive();
        BUILDER.setDataChannelProtectionLevel(options, FtpsDataChannelProtectionLevel.C);
        final FTPSClientWithSessionResumption client = connect(options, new SocketGroup());
        try {
            assertEquals("P", client.getProtection());
            final Socket data = client._openDataConnection_("LIST", null);
            assertTrue(data instanceof SSLSocket);
            data.close();
            client.completePendingCommand();
        } finally {
            client.disconnect();
        }
        server.reply("PROT", "C", 536, 1);
        final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), options);
        assertArrayEquals(SMALL, read.getContent().getByteArray());
    }

    @Test
    public void refusedPassiveModeGivesNoDataSocketAndKeepsTheConnection() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final SocketGroup group = new SocketGroup();
        final FTPSClientWithSessionResumption client = connect(passive(), group);
        try {
            server.reply("PASV", null, 502, 1);
            server.reply("EPSV", null, 502, 1);
            assertNull(client._openDataConnection_("LIST", null));
            assertEquals(502, client.getReplyCode());
            // No data socket was made, and the control connection is still in use.
            assertEquals(1, group.getSockets().size());
            assertFalse(group.isAborted());
            assertTrue(client.isConnected());
            assertTrue(FTPReply.isPositiveCompletion(client.noop()));
        } finally {
            client.disconnect();
        }
    }

    @Test
    public void trustStoreWithoutTheServerCertificateFailsTheConnectWithItsCause() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = passive();
        trustStore(options, storeOfAnotherCertificate());
        final SocketGroup group = new SocketGroup();
        final FileSystemException error = assertThrows(FileSystemException.class, () -> connect(options, group));
        assertTrue(hasCause(error, SSLHandshakeException.class), chain(error));
        assertEquals(Fault.TRANSPORT, Faults.of(error));
        assertTrue(group.isAborted());
        assertEquals(0, server.count("USER"));
    }

    @Test
    public void trustStoreWithTheServerCertificateConnects() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = passive();
        trustStore(options, storeOfTheServerCertificate());
        final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), options);
        assertArrayEquals(SMALL, read.getContent().getByteArray());
    }

    @Test
    public void trustManagerSetInCodeWinsOverTheStore() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = passive();
        trustStore(options, storeOfAnotherCertificate());
        BUILDER.setTrustManager(options, TrustManagerUtils.getAcceptAllTrustManager());
        assertTrue(BUILDER.isTrustManagerSet(options));
        final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), options);
        assertArrayEquals(SMALL, read.getContent().getByteArray());
    }

    @Test
    public void keyStoreGivesTheClientCertificate() throws Exception {
        server = ScriptedFtpServer.tlsWithClientCertificate(home);
        final FileSystemOptions withKey = passive();
        BUILDER.setKeyStore(withKey, ScriptedFtpServer.serverKeyStore().getAbsolutePath());
        BUILDER.setKeyStoreType(withKey, "JKS");
        BUILDER.setKeyStorePW(withKey, "password");
        BUILDER.setKeyPW(withKey, "password");
        final FileObject read = manager.resolveFile(server.uri("ftps", "/small.txt"), withKey);
        assertArrayEquals(SMALL, read.getContent().getByteArray());

        final FileSystemException refused = assertThrows(FileSystemException.class,
                () -> connect(passive(), new SocketGroup()));
        assertEquals(Fault.TRANSPORT, Faults.of(refused));
    }

    @Test
    public void storeWithAWrongPasswordThrowsAndClosesItsStream() throws Exception {
        server = ScriptedFtpServer.tls(home, false);
        final File store = storeOfTheServerCertificate();
        final FileSystemOptions options = passive();
        BUILDER.setTrustStore(options, store.getAbsolutePath());
        BUILDER.setTrustStoreType(options, "PKCS12");
        BUILDER.setTrustStorePW(options, "wrong");
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> connect(options, new SocketGroup()));
        assertTrue(hasCause(error, IOException.class), String.valueOf(error));
        assertEquals(Collections.emptyList(), openFilesOf(store.toPath()));
        assertEquals(0, server.getConnects());
    }

    private static void trustStore(final FileSystemOptions options, final File store) {
        BUILDER.setTrustStore(options, store.getAbsolutePath());
        BUILDER.setTrustStoreType(options, "PKCS12");
        BUILDER.setTrustStorePW(options, "secret");
    }

    // A trust store that holds only the client certificate of the key store of the server, which the server does not
    // use.
    private File storeOfAnotherCertificate() throws Exception {
        return storeOf("ftpclient", "other.p12");
    }

    private File storeOfTheServerCertificate() throws Exception {
        return storeOf("ftpserver", "server.p12");
    }

    private File storeOf(final String alias, final String name) throws Exception {
        final KeyStore server = KeyStore.getInstance("JKS");
        try (FileInputStream in = new FileInputStream(ScriptedFtpServer.serverKeyStore())) {
            server.load(in, "password".toCharArray());
        }
        final Certificate certificate = server.getCertificate(alias);
        assertNotNull(certificate);
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry(alias, certificate);
        return save(store, name);
    }

    private static String chain(final Throwable error) {
        final StringBuilder text = new StringBuilder();
        for (Throwable link = error; link != null; link = link.getCause()) {
            text.append(link).append(" <- ");
        }
        return text.toString();
    }

    private File save(final KeyStore store, final String name) throws Exception {
        final File file = new File(stores, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            store.store(out, "secret".toCharArray());
        }
        return file;
    }

    // The open files of this JVM that are the given file, read from the file descriptors of the process.
    private static List<Path> openFilesOf(final Path file) throws IOException {
        try (Stream<Path> descriptors = Files.list(Paths.get("/proc/self/fd"))) {
            return descriptors.map(descriptor -> {
                try {
                    return Files.readSymbolicLink(descriptor);
                } catch (final IOException e) {
                    return null;
                }
            }).filter(target -> file.toAbsolutePath().equals(target)).collect(Collectors.toList());
        }
    }
}
