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
package org.wso2.org.apache.commons.vfs2.provider.ftps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.SSLContextUtils;
import org.apache.commons.net.util.TrustManagerUtils;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpClientFactory;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * Create FTPSClient instances.
 *
 * @since 2.0
 */
public final class FtpsClientFactory {


    /** Connection Factory for FTPS case. */
    private static final class FtpsConnectionFactory
            extends FtpClientFactory.ConnectionFactory<FTPSClient, FtpsFileSystemConfigBuilder> {

        private final Log log = LogFactory.getLog(getClass());
        private static final String PKIX = "PKIX";
        private static final String JCE_PROVIDER = "security.jce.provider";
        private static final String FTPS_SCHEME = "ftps";
        private static final String PROTOCOL = "TLS";
        private FtpsConnectionFactory(final FtpsFileSystemConfigBuilder builder) {
            super(builder);
        }

        @Override
        protected String getScheme() {
            return FTPS_SCHEME;
        }

        @Override
        protected FTPSClient createClient(final FileSystemOptions fileSystemOptions) throws Exception {
            // One context per client serves the control connection and the data connections, so they share one
            // session cache.
            final SSLContext context = SSLContextUtils.createSSLContext(PROTOCOL, keyManagers(fileSystemOptions),
                    trustManagers(fileSystemOptions));
            return new FTPSClientWithSessionResumption(builder.getFtpsMode(fileSystemOptions) == FtpsMode.IMPLICIT,
                    context);
        }

        @Override
        protected void setSocketFactories(final FTPSClient client, final SocketFactory socketFactory,
                final ServerSocketFactory serverSocketFactory) {
            if (client instanceof FTPSClientWithSessionResumption) {
                ((FTPSClientWithSessionResumption) client).setWatchedFactories(socketFactory, serverSocketFactory);
            } else {
                super.setSocketFactories(client, socketFactory, serverSocketFactory);
            }
        }

        // A key manager set in the options in code, else the managers of the configured key store, else none.
        private KeyManager[] keyManagers(final FileSystemOptions fileSystemOptions) throws Exception {
            if (builder.isKeyManagerSet(fileSystemOptions)) {
                final KeyManager keyManager = builder.getKeyManager(fileSystemOptions);
                return keyManager == null ? null : new KeyManager[] {keyManager};
            }
            final String keyStorePath = builder.getKeyStore(fileSystemOptions);
            if (keyStorePath == null || keyStorePath.trim().isEmpty()) {
                return null;
            }
            final KeyStore keyStore = loadStore(keyStorePath, builder.getKeyStoreType(fileSystemOptions),
                    builder.getKeyStorePW(fileSystemOptions));
            final KeyManagerFactory keyManagerFactory;
            try {
                keyManagerFactory = KeyManagerFactory.getInstance(getKeyManagerType());
                keyManagerFactory.init(keyStore, builder.getKeyPW(fileSystemOptions).toCharArray());
            } catch (KeyStoreException e) {
                log.error("Error occurred when initializing keystores",e);
                throw e;
            } catch (NoSuchAlgorithmException e) {
                log.error("Error when getting the default algorithm",e);
                throw e;
            } catch (UnrecoverableKeyException e) {
                log.error("Unrecoverable Key exception occurred",e);
                throw e;
            }
            return keyManagerFactory.getKeyManagers();
        }

        // A trust manager set in the options in code, else the managers of the configured trust store, else the
        // trust manager of commons net that checks the dates only, as before.
        private TrustManager[] trustManagers(final FileSystemOptions fileSystemOptions) throws Exception {
            if (builder.isTrustManagerSet(fileSystemOptions)) {
                final TrustManager trustManager = builder.getTrustManager(fileSystemOptions);
                return trustManager == null ? null : new TrustManager[] {trustManager};
            }
            final String trustStorePath = builder.getTrustStore(fileSystemOptions);
            if (trustStorePath == null || trustStorePath.trim().isEmpty()) {
                return new TrustManager[] {TrustManagerUtils.getValidateServerCertificateTrustManager()};
            }
            final KeyStore trustStore = loadStore(trustStorePath, builder.getTrustStoreType(fileSystemOptions),
                    builder.getTrustStorePW(fileSystemOptions));
            final TrustManagerFactory trustManagerFactory;
            try {
                trustManagerFactory = TrustManagerFactory.getInstance(getTrustManagerType());
                trustManagerFactory.init(trustStore);
            } catch (KeyStoreException e) {
                log.error("Error occurred when initializing keystores",e);
                throw e;
            } catch (NoSuchAlgorithmException e) {
                log.error("Error when getting the default algorithm",e);
                throw e;
            }
            return trustManagerFactory.getTrustManagers();
        }

        // The stream of the store is closed on every path.
        private KeyStore loadStore(final String path, final String type, final String password) throws Exception {
            try (FileInputStream storeStream = new FileInputStream(new File(path))) {
                final KeyStore store = KeyStore.getInstance(type);
                store.load(storeStream, password.toCharArray());
                return store;
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
                    // A refused level changes nothing. The level that the server accepted last stays.
                    if (!isRefusal(client, e)) {
                        throw new FileSystemException("vfs.provider.ftps/data-channel.level", e, level.toString());
                    }
                }
            }
        }

        // commons net reports a reply other than 200 to PBSZ or PROT as an SSLException with the text of that reply.
        private static boolean isRefusal(final FTPSClient client, final SSLException e) {
            return e.getClass() == SSLException.class && client.getReplyCode() != FTPReply.COMMAND_OK
                    && String.valueOf(e.getMessage()).equals(client.getReplyString());
        }

        private static String getKeyManagerType() {
            if (System.getProperty(JCE_PROVIDER) != null) {
                return PKIX;
            } else {
                return KeyManagerFactory.getDefaultAlgorithm();
            }
        }

        private static String getTrustManagerType() {
            if (System.getProperty(JCE_PROVIDER) != null) {
                return PKIX;
            } else {
                return TrustManagerFactory.getDefaultAlgorithm();
            }
        }
    }

    /**
     * Creates a new connection to the server. Its sockets are in a group of their own that no manager knows.
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

    /**
     * Creates a new connection to the server whose sockets are in the given group.
     *
     * @param hostname The host name.
     * @param port The port.
     * @param username The user name for authentication.
     * @param password The user's password.
     * @param workingDirectory The directory to use.
     * @param fileSystemOptions The FileSystemOptions.
     * @param group The group of the new client. It is aborted when the connection fails.
     * @param windowStart The start of the fast window of the call, on the clock of {@link System#nanoTime()}.
     * @return The FTPSClient.
     * @throws FileSystemException if an error occurs.
     */
    public static FTPSClient createConnection(final String hostname, final int port, final char[] username,
            final char[] password, final String workingDirectory, final FileSystemOptions fileSystemOptions,
            final SocketGroup group, final long windowStart) throws FileSystemException {
        final FtpsConnectionFactory factory = new FtpsConnectionFactory(FtpsFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions,
                null, null, null, null, null, null, group, windowStart);
    }

    private FtpsClientFactory() {
        // empty
    }
}
