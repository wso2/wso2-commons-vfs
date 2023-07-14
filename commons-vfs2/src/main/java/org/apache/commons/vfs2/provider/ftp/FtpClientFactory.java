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
package org.apache.commons.vfs2.provider.ftp;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPHTTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.parser.FTPFileEntryParserFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.Proxy;
import java.net.SocketTimeoutException;

/**
 * Create a FtpClient instance.
 */
public final class FtpClientFactory {
    private FtpClientFactory() {
    }

    /**
     * Creates a new connection to the server.
     *
     * @param hostname The host name of the server.
     * @param port The port to connect to.
     * @param username The name of the user for authentication.
     * @param password The user's password.
     * @param workingDirectory The base directory.
     * @param fileSystemOptions The FileSystemOptions.
     * @return An FTPClient.
     * @throws FileSystemException if an error occurs while connecting.
     */
    public static FTPClient createConnection(final String hostname, final int port, final char[] username,
            final char[] password, final String workingDirectory, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        final FtpConnectionFactory factory = new FtpConnectionFactory(FtpFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions);
    }

    /**
     * Creates a new connection to the server.
     *
     * @param hostname          The host name of the server.
     * @param port              The port to connect to.
     * @param username          The name of the user for authentication.
     * @param password          The user's password.
     * @param workingDirectory  The base directory.
     * @param fileSystemOptions The FileSystemOptions.
     * @param proxyServer       Proxy server address
     * @param proxyPort         Proxy server port
     * @param proxyUser         Proxy server username
     * @param proxyPassword     Proxy server password
     * @return An FTPClient.
     * @throws FileSystemException if an error occurs while connecting.
     */
    public static FTPClient createConnection(String hostname, int port, char[] username, char[] password, String
            workingDirectory, FileSystemOptions fileSystemOptions, String proxyServer, String proxyPort, String
            proxyUser, String proxyPassword, String timeout, String retryCount) throws FileSystemException {
        final FtpConnectionFactory factory = new FtpConnectionFactory(FtpFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions,
                proxyServer, proxyPort, proxyUser, proxyPassword, timeout, retryCount);
    }

    /** Connection Factory, used to configure the FTPClient. */
    public static final class FtpConnectionFactory extends ConnectionFactory<FTPClient, FtpFileSystemConfigBuilder> {
        private FtpConnectionFactory(final FtpFileSystemConfigBuilder builder) {
            super(builder);
        }

        @Override
        protected FTPClient createClient(final FileSystemOptions fileSystemOptions) {
            return new FTPClient();
        }

        @Override
        protected void setupOpenConnection(final FTPClient client, final FileSystemOptions fileSystemOptions) {
            // nothing to do for FTP
        }
    }

    /** Abstract Factory, used to configure different FTPClients. */
    public abstract static class ConnectionFactory<C extends FTPClient, B extends FtpFileSystemConfigBuilder> {
        private static final char[] ANON_CHAR_ARRAY = "anonymous".toCharArray();
        private static final int BUFSZ = 40;
        private final Log log = LogFactory.getLog(getClass());

        protected B builder;

        protected ConnectionFactory(final B builder) {
            this.builder = builder;
        }

        public C createConnection(final String hostname, final int port, char[] username, char[] password, final
        String workingDirectory, final FileSystemOptions fileSystemOptions) throws FileSystemException {
            return createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions, null,
                    null, null, null, null, null);
        }

        public C createConnection(String hostname, int port, char[] username, char[] password, String
                workingDirectory, FileSystemOptions fileSystemOptions, String proxyServer, String proxyPort, String
                proxyUser, String proxyPassword, String timeout, String retryCount) throws FileSystemException {

            // Determine the username and password to use
            if (username == null) {
                username = ANON_CHAR_ARRAY;
            }

            if (password == null) {
                password = ANON_CHAR_ARRAY;
            }

            Integer connectionTimeout;

            if (timeout == null) {
                connectionTimeout = builder.getConnectTimeout(fileSystemOptions);
            } else {
                try {
                    connectionTimeout = Integer.parseInt(timeout);
                } catch (NumberFormatException nfe) {
                    log.warn("Invalid connection timeout " + timeout + ". Set the connectionTimeout as 5000. (default)");
                    connectionTimeout = 5000;
                }
            }

            Integer connectionRetryCount;
            if (retryCount == null) {
                connectionRetryCount = builder.getRetryCount(fileSystemOptions);
            } else {
                try {
                    connectionRetryCount = Integer.parseInt(retryCount);
                } catch (NumberFormatException nfe) {
                    log.warn("Invalid connection retry count " + retryCount + ". Set the connectionRetryCount as 5. "
                            + "(default)");
                    connectionRetryCount = 5;
                }
            }

            boolean proxyMode = false;

            try {

                final C client;

                //Implement the logic to support FTP over HTTP Proxy
                if (proxyServer != null && proxyPort != null) {
                    int parsedProxyPort;
                    try {
                        parsedProxyPort = Integer.parseInt(proxyPort);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid proxy port " + proxyPort + ". Set the port as 8080. (default)");
                        parsedProxyPort = 8080;
                    }
                    if (proxyUser != null && proxyPassword != null) {
                        client = (C) new FTPHTTPClient(proxyServer, parsedProxyPort, proxyUser, proxyPassword);
                        proxyMode = true;
                    } else {
                        client = (C) new FTPHTTPClient(proxyServer, parsedProxyPort);
                        proxyMode = true;
                    }
                } else {
                    client = createClient(fileSystemOptions);
                }

                if (log.isDebugEnabled()) {
                    final Writer writer = new StringWriter(1024) {
                        @Override
                        public void flush() {
                            final StringBuffer buffer = getBuffer();
                            String message = buffer.toString();
                            if (message.toUpperCase().startsWith("PASS ") && message.length() > 5) {
                                message = "PASS ***";
                            }
                            log.debug(message);
                            buffer.setLength(0);
                        }
                    };
                    client.addProtocolCommandListener(new PrintCommandListener(new PrintWriter(writer)));
                }

                configureClient(fileSystemOptions, client);

                final FTPFileEntryParserFactory myFactory = builder.getEntryParserFactory(fileSystemOptions);
                if (myFactory != null) {
                    client.setParserFactory(myFactory);
                }

                final Boolean remoteVerification = builder.getRemoteVerification(fileSystemOptions);
                if (remoteVerification != null) {
                    client.setRemoteVerificationEnabled(remoteVerification.booleanValue());
                }

                try {
                    preConfigureClient(fileSystemOptions);
                    // Set connect timeout
                    final Integer connectTimeout = builder.getConnectTimeout(fileSystemOptions);
                    if (connectTimeout != null) {
                        client.setDefaultTimeout(connectTimeout.intValue());
                    }

                    final String controlEncoding = builder.getControlEncoding(fileSystemOptions);
                    if (controlEncoding != null) {
                        client.setControlEncoding(controlEncoding);
                    }

                    final Proxy proxy = builder.getProxy(fileSystemOptions);
                    if (proxy != null) {
                        client.setProxy(proxy);
                    }

                    //Need to enforce this since some times if we don't do this thread will hang forever
                    if (proxyMode) {
                        client.setConnectTimeout(connectionTimeout);
                        boolean connect = false;
                        while (!connect && (connectionRetryCount > 0)) {
                            try {
                                client.connect(hostname, port);
                                connect = true;
                            } catch (SocketTimeoutException e) {
                                connectionRetryCount--;
                            } catch (IOException e) {
                                connectionRetryCount--;
                            }
                        }
                    } else {
                        client.connect(hostname, port);
                    }

                    final int reply = client.getReplyCode();
                    if (!FTPReply.isPositiveCompletion(reply)) {
                        throw new FileSystemException("vfs.provider.ftp/connect-rejected.error", hostname);
                    }

                    // Login
                    if (!client.login(UserAuthenticatorUtils.toString(username), UserAuthenticatorUtils.toString
                            (password))) {
                        throw new FileSystemException("vfs.provider.ftp/login.error", hostname,
                                UserAuthenticatorUtils.toString(username));
                    }

                    FtpFileType fileType = builder.getFileType(fileSystemOptions);
                    if (fileType == null) {
                        fileType = FtpFileType.BINARY;
                    }
                    // Set binary mode
                    if (!client.setFileType(fileType.getValue())) {
                        throw new FileSystemException("vfs.provider.ftp/set-file-type.error", fileType);
                    }

                    // Set dataTimeout value
                    final Integer dataTimeout = builder.getDataTimeout(fileSystemOptions);
                    if (dataTimeout != null) {
                        client.setDataTimeout(dataTimeout.intValue());
                    }

                    final Integer socketTimeout = builder.getSoTimeout(fileSystemOptions);
                    if (socketTimeout != null) {
                        client.setSoTimeout(socketTimeout.intValue());
                    }

                    final Boolean userDirIsRoot = builder.getUserDirIsRoot(fileSystemOptions);
                    if (workingDirectory != null && (userDirIsRoot == null || !userDirIsRoot.booleanValue())) {
                        if (!client.changeWorkingDirectory(workingDirectory)) {
                            throw new FileSystemException("vfs.provider.ftp/change-work-directory.error",
                                    workingDirectory);
                        }
                    }

                    final Boolean passiveMode = builder.getPassiveMode(fileSystemOptions);
                    if (proxyMode || (passiveMode != null && passiveMode.booleanValue())) {
                        client.enterLocalPassiveMode();
                    }

                    setupOpenConnection(client, fileSystemOptions);
                } catch (final IOException e) {
                    if (client.isConnected()) {
                        client.disconnect();
                    }
                    throw e;
                }

                return client;
            } catch (final Exception exc) {
                throw new FileSystemException("vfs.provider.ftp/connect.error", exc, hostname);
            }
        }

        protected void preConfigureClient(FileSystemOptions fileSystemOptions) throws
                Exception {
            // nothing to do for FTP
        }

        protected abstract C createClient(FileSystemOptions fileSystemOptions) throws FileSystemException;

        protected abstract void setupOpenConnection(C client, FileSystemOptions fileSystemOptions) throws IOException;

        protected void configureClient(final FileSystemOptions fileSystemOptions, final C client) throws Exception {
            final String key = builder.getEntryParser(fileSystemOptions);
            if (key != null) {
                final FTPClientConfig config = new FTPClientConfig(key);

                final String serverLanguageCode = builder.getServerLanguageCode(fileSystemOptions);
                if (serverLanguageCode != null) {
                    config.setServerLanguageCode(serverLanguageCode);
                }
                final String defaultDateFormat = builder.getDefaultDateFormat(fileSystemOptions);
                if (defaultDateFormat != null) {
                    config.setDefaultDateFormatStr(defaultDateFormat);
                }
                final String recentDateFormat = builder.getRecentDateFormat(fileSystemOptions);
                if (recentDateFormat != null) {
                    config.setRecentDateFormatStr(recentDateFormat);
                }
                final String serverTimeZoneId = builder.getServerTimeZoneId(fileSystemOptions);
                if (serverTimeZoneId != null) {
                    config.setServerTimeZoneId(serverTimeZoneId);
                }
                final String[] shortMonthNames = builder.getShortMonthNames(fileSystemOptions);
                if (shortMonthNames != null) {
                    final StringBuilder shortMonthNamesStr = new StringBuilder(BUFSZ);
                    for (final String shortMonthName : shortMonthNames) {
                        if (shortMonthNamesStr.length() > 0) {
                            shortMonthNamesStr.append("|");
                        }
                        shortMonthNamesStr.append(shortMonthName);
                    }
                    config.setShortMonthNames(shortMonthNamesStr.toString());
                }

                client.configure(config);
            }
        }
    }
}
