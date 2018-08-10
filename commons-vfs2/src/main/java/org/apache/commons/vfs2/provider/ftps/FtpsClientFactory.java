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
package org.apache.commons.vfs2.provider.ftps;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftp.FtpClientFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

/**
 * Create FTPSClient instances.
 *
 * @since 2.0
 */
public final class FtpsClientFactory {

    private FtpsClientFactory() {
    }

    /**
     * Creates a new connection to the server.
     *
     * @param hostname The host name.
     * @param port The port.
     * @param username The user name for authentication.
     * @param password The user's password.
     * @param workingDirectory The directory to use.
     * @param fileSystemOptions The FileSystemOptions.
     * @return The FTPSClient.
     * @throws FileSystemException if an error occurs.
     */
    public static FTPSClient createConnection(final String hostname, final int port, final char[] username,
            final char[] password, final String workingDirectory, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        final FtpsConnectionFactory factory = new FtpsConnectionFactory(FtpsFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions);
    }

    /** Connection Factory for FTPS case. */
    private static final class FtpsConnectionFactory
            extends FtpClientFactory.ConnectionFactory<FTPSClient, FtpsFileSystemConfigBuilder> {

        private final Log log = LogFactory.getLog(getClass());
        private FtpsConnectionFactory(final FtpsFileSystemConfigBuilder builder) {
            super(builder);
        }

        @Override
        protected FTPSClient createClient(final FileSystemOptions fileSystemOptions) throws FileSystemException {
            final FTPSClient client;
            if (builder.getFtpsMode(fileSystemOptions) == FtpsMode.IMPLICIT) {
                client = new FTPSClient(true);
            } else {
                client = new FTPSClient();
            }

            final TrustManager trustManager = builder.getTrustManager(fileSystemOptions);
            if (trustManager != null) {
                client.setTrustManager(trustManager);
            }

            final KeyManager keyManager = builder.getKeyManager(fileSystemOptions);
            if (keyManager != null) {
                client.setKeyManager(keyManager);
            }
            return client;
        }

        @Override
        protected void preConfigureClient(FileSystemOptions fileSystemOptions) throws Exception {

            String KEYSTORE = builder.getKeyStore(fileSystemOptions);
            String TRUSTSTORE = builder.getTrustStore(fileSystemOptions);
            String KS_PASSWD = builder.getKeyStorePW(fileSystemOptions);
            String TS_PASSWD = builder.getTrustStorePW(fileSystemOptions);
            String KEY_PASSWD = builder.getKeyPW(fileSystemOptions);

            KeyManagerFactory keyManagerFactory = null;
            TrustManagerFactory trustManagerFactory = null;
            try {
                if (KEYSTORE != null && !KEYSTORE.trim().isEmpty()) {
                    FileInputStream keystorePath = new FileInputStream(new File(KEYSTORE));
                    KeyStore keyStore = KeyStore.getInstance("jks");

                    //load key store.
                    keyStore.load(keystorePath, KS_PASSWD.toCharArray());
                    keystorePath.close();

                    // initialize key manager factory
                    keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    keyManagerFactory.init(keyStore, KEY_PASSWD.toCharArray());
                }
                if (TRUSTSTORE != null && !TRUSTSTORE.trim().isEmpty()) {
                    FileInputStream truststorePath = new FileInputStream(new File(TRUSTSTORE));
                    KeyStore trustStore = KeyStore.getInstance("jks");
                    //load trust store.
                    trustStore.load(truststorePath, TS_PASSWD.toCharArray());
                    truststorePath.close();
                    // initialize trust manager factory
                    trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    trustManagerFactory.init(trustStore);
                }

            } catch (KeyStoreException e) {
                log.error("Error occurred when initializing keystores",e);
                throw e;
            } catch (IOException e) {
                log.error("Error occurred while retrieving the keystore paths",e);
                throw e;
            } catch (NoSuchAlgorithmException e) {
                log.error("Error when getting the default algorithm",e);
                throw e;
            } catch (CertificateException e) {
                log.error("Certificate exception occurred when loading the KeyStores ",e);
                throw e;
            } catch (UnrecoverableKeyException e) {
                log.error("Unrecoverable Key exception occurred",e);
                throw e;
            }
        }

        @Override
        protected void setupOpenConnection(final FTPSClient client, final FileSystemOptions fileSystemOptions)
                throws IOException {
            final FtpsDataChannelProtectionLevel level = builder.getDataChannelProtectionLevel(fileSystemOptions);
            if (level != null) {
                // '0' means streaming, that's what we do!
                try {
                    client.execPBSZ(0);
                    client.execPROT(level.name());
                } catch (final SSLException e) {
                    throw new FileSystemException("vfs.provider.ftps/data-channel.level", e, level.toString());
                }
            }
        }
    }
}
