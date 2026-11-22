/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPHTTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.parser.FTPFileEntryParserFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.UserAuthenticatorUtils;
import org.wso2.org.apache.commons.vfs2.util.WatchedServerSocketFactory;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocketFactory;

/**
 * Creates {@link FtpClient} instances.
 */
public final class FtpClientFactory {

    private static final String FTP_SCHEME = "ftp";

    /**
     * The socket factory of a client that goes through an HTTP proxy. The tunnel of commons net reads the reply of
     * the proxy with no timeout of its own. So each socket keeps one budget, the connect timeout, from the start of its
     * TCP connect to the end of that reply, also when the reply comes in small parts. After the reply a data socket
     * gets the data timeout, and the control socket gets the timeouts that the client sets. A connect timeout of 0 has
     * no bound, as everywhere else.
     */
    private static final class TunnelSocketFactory extends SocketFactory {

        private final SocketFactory sockets;

        private final int connectMillis;

        private final int dataMillis;

        // False while the client connects its control socket. Every socket after that is a data socket.
        private volatile boolean controlConnected;

        TunnelSocketFactory(final SocketFactory sockets, final int connectMillis, final int dataMillis) {
            this.sockets = sockets;
            this.connectMillis = connectMillis;
            this.dataMillis = dataMillis;
        }

        void controlConnected() {
            controlConnected = true;
        }

        private Socket tunnel(final Socket plain, final long start) throws IOException {
            return new TunnelSocket(plain, start, connectMillis, controlConnected ? dataMillis : -1);
        }

        @Override
        public Socket createSocket() throws IOException {
            return sockets.createSocket();
        }

        @Override
        public Socket createSocket(final String host, final int port) throws IOException {
            final long start = System.nanoTime();
            return tunnel(sockets.createSocket(host, port), start);
        }

        @Override
        public Socket createSocket(final InetAddress host, final int port) throws IOException {
            final long start = System.nanoTime();
            return tunnel(sockets.createSocket(host, port), start);
        }

        @Override
        public Socket createSocket(final String host, final int port, final InetAddress localHost,
                final int localPort) throws IOException {
            final long start = System.nanoTime();
            return tunnel(sockets.createSocket(host, port, localHost, localPort), start);
        }

        @Override
        public Socket createSocket(final InetAddress address, final int port, final InetAddress localAddress,
                final int localPort) throws IOException {
            final long start = System.nanoTime();
            return tunnel(sockets.createSocket(address, port, localAddress, localPort), start);
        }
    }

    /**
     * A socket to an HTTP proxy. It passes every call to the watched socket under it, so an abort or the watchdog
     * closes that socket as before. Its input stream bounds the reads of the proxy reply by the time left of the
     * connect budget. The reply ends at its first empty line, as the tunnel of commons net reads it.
     */
    private static final class TunnelSocket extends Socket {

        private final Socket plain;

        private final long deadline;

        private final int connectMillis;

        // The read timeout after the reply: the data timeout, or -1 for the control socket, which gets back the
        // timeout it had and then the timeouts of the client.
        private final int afterMillis;

        private final int ownMillis;

        private InputStream input;

        TunnelSocket(final Socket plain, final long start, final int connectMillis, final int afterMillis)
                throws IOException {
            this.plain = plain;
            this.deadline = start + TimeUnit.MILLISECONDS.toNanos(connectMillis);
            this.connectMillis = connectMillis;
            this.afterMillis = afterMillis;
            this.ownMillis = plain.getSoTimeout();
        }

        @Override
        public synchronized InputStream getInputStream() throws IOException {
            if (input == null) {
                input = new ReplyInputStream(plain.getInputStream());
            }
            return input;
        }

        @Override
        public void connect(final SocketAddress endpoint) throws IOException {
            plain.connect(endpoint);
        }

        @Override
        public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
            plain.connect(endpoint, timeout);
        }

        @Override
        public void bind(final SocketAddress bindpoint) throws IOException {
            plain.bind(bindpoint);
        }

        @Override
        public InetAddress getInetAddress() {
            return plain.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return plain.getLocalAddress();
        }

        @Override
        public int getPort() {
            return plain.getPort();
        }

        @Override
        public int getLocalPort() {
            return plain.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return plain.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return plain.getLocalSocketAddress();
        }

        @Override
        public SocketChannel getChannel() {
            return plain.getChannel();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return plain.getOutputStream();
        }

        @Override
        public void setTcpNoDelay(final boolean on) throws SocketException {
            plain.setTcpNoDelay(on);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return plain.getTcpNoDelay();
        }

        @Override
        public void setSoLinger(final boolean on, final int linger) throws SocketException {
            plain.setSoLinger(on, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return plain.getSoLinger();
        }

        @Override
        public void sendUrgentData(final int data) throws IOException {
            plain.sendUrgentData(data);
        }

        @Override
        public void setOOBInline(final boolean on) throws SocketException {
            plain.setOOBInline(on);
        }

        @Override
        public boolean getOOBInline() throws SocketException {
            return plain.getOOBInline();
        }

        @Override
        public void setSoTimeout(final int timeout) throws SocketException {
            plain.setSoTimeout(timeout);
        }

        @Override
        public int getSoTimeout() throws SocketException {
            return plain.getSoTimeout();
        }

        @Override
        public void setSendBufferSize(final int size) throws SocketException {
            plain.setSendBufferSize(size);
        }

        @Override
        public int getSendBufferSize() throws SocketException {
            return plain.getSendBufferSize();
        }

        @Override
        public void setReceiveBufferSize(final int size) throws SocketException {
            plain.setReceiveBufferSize(size);
        }

        @Override
        public int getReceiveBufferSize() throws SocketException {
            return plain.getReceiveBufferSize();
        }

        @Override
        public void setKeepAlive(final boolean on) throws SocketException {
            plain.setKeepAlive(on);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return plain.getKeepAlive();
        }

        @Override
        public void setTrafficClass(final int tc) throws SocketException {
            plain.setTrafficClass(tc);
        }

        @Override
        public int getTrafficClass() throws SocketException {
            return plain.getTrafficClass();
        }

        @Override
        public void setReuseAddress(final boolean on) throws SocketException {
            plain.setReuseAddress(on);
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return plain.getReuseAddress();
        }

        @Override
        public void close() throws IOException {
            plain.close();
        }

        @Override
        public void shutdownInput() throws IOException {
            plain.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            plain.shutdownOutput();
        }

        @Override
        public boolean isConnected() {
            return plain.isConnected();
        }

        @Override
        public boolean isBound() {
            return plain.isBound();
        }

        @Override
        public boolean isClosed() {
            return plain.isClosed();
        }

        @Override
        public boolean isInputShutdown() {
            return plain.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return plain.isOutputShutdown();
        }

        @Override
        public void setPerformancePreferences(final int connectionTime, final int latency, final int bandwidth) {
            plain.setPerformancePreferences(connectionTime, latency, bandwidth);
        }

        @Override
        public String toString() {
            return "TunnelSocket[" + plain + "]";
        }

        private final class ReplyInputStream extends FilterInputStream {

            private boolean inReply = true;

            private boolean lineEmpty = true;

            private boolean afterCr;

            ReplyInputStream(final InputStream in) {
                super(in);
            }

            @Override
            public int read() throws IOException {
                final byte[] one = new byte[1];
                final int n = read(one, 0, 1);
                return n == -1 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                if (!inReply) {
                    return in.read(b, off, len);
                }
                limit();
                final int n = in.read(b, off, len);
                if (n == -1) {
                    endReply();
                } else {
                    scan(b, off, n);
                }
                return n;
            }

            // The read waits only for the time that is left of the connect budget.
            private void limit() throws IOException {
                if (connectMillis <= 0) {
                    return;
                }
                final long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new SocketTimeoutException("The HTTP proxy " + plain.getRemoteSocketAddress()
                            + " gave no full reply within the connect timeout of " + connectMillis + " ms");
                }
                final long millis = (left + 999_999) / 1_000_000;
                plain.setSoTimeout((int) Math.min(Integer.MAX_VALUE, millis));
            }

            // A line ends at CR, LF or CR LF, as BufferedReader.readLine reads it. The first empty line ends the reply.
            private void scan(final byte[] b, final int off, final int n) throws IOException {
                for (int i = off; i < off + n && inReply; i++) {
                    final byte c = b[i];
                    if (afterCr && c == '\n') {
                        afterCr = false;
                        continue;
                    }
                    afterCr = false;
                    if (c == '\r' || c == '\n') {
                        if (lineEmpty) {
                            endReply();
                        }
                        lineEmpty = true;
                        afterCr = c == '\r';
                    } else {
                        lineEmpty = false;
                    }
                }
            }

            private void endReply() throws IOException {
                inReply = false;
                plain.setSoTimeout(afterMillis >= 0 ? afterMillis : ownMillis);
            }
        }
    }

    /**
     * Abstract Factory, used to configure different FTPClients.
     *
     * @param <C> The type of FTPClient.
     * @param <B> The type of FtpFileSystemConfigBuilder
     */
    public abstract static class ConnectionFactory<C extends FTPClient, B extends FtpFileSystemConfigBuilder> {

        private static final char[] ANON_CHAR_ARRAY = "anonymous".toCharArray();
        private static final int BUFSZ = 40;
        // The 2.2 line takes these values when the URI gives a value that is not a number, or no retry count.
        private static final int DEFAULT_PROXY_TIMEOUT = 5000;
        private static final int DEFAULT_RETRY_COUNT = 5;
        private static final int DEFAULT_PROXY_PORT = 8080;
        private static final String CONNECT_FAILED = "The connect of the FTP client failed";

        /**
         * My builder.
         */
        protected B builder;

        private final Log log = LogFactory.getLog(getClass());

        /**
         * Constructs a new instance.
         *
         * @param builder How to build.
         */
        protected ConnectionFactory(final B builder) {
            this.builder = builder;
        }

        private void configureClient(final FileSystemOptions fileSystemOptions, final C client) {
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
                        if (!StringUtils.isEmpty(shortMonthNamesStr)) {
                            shortMonthNamesStr.append("|");
                        }
                        shortMonthNamesStr.append(shortMonthName);
                    }
                    config.setShortMonthNames(shortMonthNamesStr.toString());
                }
                client.configure(config);
            }
        }

        /**
         * Creates a new client.
         *
         * @param fileSystemOptions the file system options.
         * @return a new client.
         * @throws Exception if the client can not be made.
         */
        protected abstract C createClient(FileSystemOptions fileSystemOptions) throws Exception;

        /**
         * Gets the scheme whose bounds this factory applies.
         *
         * @return the scheme.
         */
        protected String getScheme() {
            return FTP_SCHEME;
        }

        /**
         * Gives the client the socket factories of its group. Every socket of the client comes from them.
         *
         * @param client the client.
         * @param socketFactory the factory of the control socket and the passive data sockets.
         * @param serverSocketFactory the factory of the server socket of an active data connection.
         */
        protected void setSocketFactories(final C client, final SocketFactory socketFactory,
                final ServerSocketFactory serverSocketFactory) {
            client.setSocketFactory(socketFactory);
            client.setServerSocketFactory(serverSocketFactory);
        }

        /**
         * Creates a connection. Its sockets are in a group of their own that no manager knows.
         *
         * @param hostname The host name or IP address.
         * @param port The host port.
         * @param username The user name.
         * @param password The user password.
         * @param workingDirectory The working directory.
         * @param fileSystemOptions Options to create the connection.
         * @return A new connection.
         * @throws FileSystemException if an error occurs while establishing a connection.
         */
        public C createConnection(final String hostname, final int port, char[] username, char[] password, final
        String workingDirectory, final FileSystemOptions fileSystemOptions) throws FileSystemException {
            return createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions, null,
                    null, null, null, null, null);
        }

        /**
         * Creates a connection, through an HTTP proxy when a proxy server and a proxy port are given. Its sockets are
         * in a group of their own that no manager knows.
         *
         * @param hostname          The host name or IP address.
         * @param port              The host port.
         * @param username          The user name.
         * @param password          The user password.
         * @param workingDirectory  The working directory.
         * @param fileSystemOptions Options to create the connection.
         * @param proxyServer       The HTTP proxy server, or null.
         * @param proxyPort         The HTTP proxy port, or null.
         * @param proxyUser         The HTTP proxy user, or null.
         * @param proxyPassword     The HTTP proxy password, or null.
         * @param timeout           The connect timeout through the proxy in milliseconds, or null.
         * @param retryCount        The number of connects through the proxy, or null.
         * @return A new connection.
         * @throws FileSystemException if an error occurs while establishing a connection.
         */
        public C createConnection(final String hostname, final int port, char[] username, char[] password,
                                  final String workingDirectory, final FileSystemOptions fileSystemOptions,
                                  String proxyServer, String proxyPort, String proxyUser, String proxyPassword,
                                  String timeout, String retryCount) throws FileSystemException {
            return createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions,
                    proxyServer, proxyPort, proxyUser, proxyPassword, timeout, retryCount, new SocketGroup(),
                    System.nanoTime());
        }

        /**
         * Creates a connection whose sockets are in the given group.
         *
         * @param hostname          The host name or IP address.
         * @param port              The host port.
         * @param username          The user name.
         * @param password          The user password.
         * @param workingDirectory  The working directory.
         * @param fileSystemOptions Options to create the connection.
         * @param proxyServer       The HTTP proxy server, or null.
         * @param proxyPort         The HTTP proxy port, or null.
         * @param proxyUser         The HTTP proxy user, or null.
         * @param proxyPassword     The HTTP proxy password, or null.
         * @param timeout           The connect timeout through the proxy in milliseconds, or null.
         * @param retryCount        The number of connects through the proxy, or null.
         * @param group             The group of the new client. It is aborted when the connection fails.
         * @param windowStart       The start of the fast window of the call, on the clock of {@link System#nanoTime()}.
         * @return A new connection.
         * @throws FileSystemException if an error occurs while establishing a connection.
         */
        public C createConnection(final String hostname, final int port, char[] username, char[] password,
                                  final String workingDirectory, final FileSystemOptions fileSystemOptions,
                                  final String proxyServer, final String proxyPort, final String proxyUser,
                                  final String proxyPassword, final String timeout, final String retryCount,
                                  final SocketGroup group, final long windowStart) throws FileSystemException {
            // Determine the username and password to use
            if (username == null) {
                username = ANON_CHAR_ARRAY;
            }

            if (password == null) {
                password = ANON_CHAR_ARRAY;
            }

            try {
                final EffectiveBounds bounds = EffectiveBounds.of(getScheme(), fileSystemOptions);
                final boolean proxyMode = proxyServer != null && proxyPort != null;
                final int connectMillis = proxyMode && timeout != null ? proxyTimeout(timeout)
                        : (int) bounds.getConnectTimeout().getValue();

                final C client;
                //Implement the logic to support FTP over HTTP Proxy
                if (proxyMode) {
                    int parsedProxyPort;
                    try {
                        parsedProxyPort = Integer.parseInt(proxyPort);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid proxy port " + proxyPort + ". Set the port as 8080. (default)");
                        parsedProxyPort = DEFAULT_PROXY_PORT;
                    }
                    if (proxyUser != null && proxyPassword != null) {
                        client = (C) new FTPHTTPClient(proxyServer, parsedProxyPort, proxyUser, proxyPassword);
                    } else {
                        client = (C) new FTPHTTPClient(proxyServer, parsedProxyPort);
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
                            final String prefix = "PASS ";
                            if (message.toUpperCase().startsWith(prefix) && message.length() > prefix.length()) {
                                message = prefix + "***";
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

                preConfigureClient(fileSystemOptions);

                // The Proxy option goes to the factory. A call of setProxy would replace the factory.
                final long stallMillis = bounds.getWriteStallTimeout().getValue();
                final SocketFactory sockets = new WatchedSocketFactory(group, connectMillis, stallMillis,
                        builder.getProxy(fileSystemOptions));
                final TunnelSocketFactory tunnels = proxyMode ? new TunnelSocketFactory(sockets, connectMillis,
                        (int) bounds.getDataTimeout().getValue()) : null;
                setSocketFactories(client, tunnels != null ? tunnels : sockets,
                        new WatchedServerSocketFactory(group, stallMillis));
                client.setConnectTimeout(connectMillis);
                client.setDefaultTimeout(connectMillis);

                final String controlEncoding = builder.getControlEncoding(fileSystemOptions);
                if (controlEncoding != null) {
                    client.setControlEncoding(controlEncoding);
                }

                final Boolean autodetectUTF8 = builder.getAutodetectUtf8(fileSystemOptions);
                if (autodetectUTF8 != null) {
                    client.setAutodetectUTF8(autodetectUTF8);
                }

                if (proxyMode) {
                    connectThroughProxy(client, hostname, port, retryCount(retryCount, fileSystemOptions),
                            windowStart);
                    tunnels.controlConnected();
                } else {
                    client.connect(hostname, port);
                }

                final int reply = client.getReplyCode();
                if (!FTPReply.isPositiveCompletion(reply)) {
                    throw new FileSystemException("vfs.provider.ftp/connect-rejected.error", hostname);
                }

                // Login
                if (!client.login(UserAuthenticatorUtils.toString(username),
                        UserAuthenticatorUtils.toString(password))) {
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

                client.setDataTimeout(Duration.ofMillis(bounds.getDataTimeout().getValue()));
                client.setSoTimeout((int) bounds.getSocketTimeout().getValue());

                final Duration controlKeepAliveTimeout = builder.getControlKeepAliveTimeout(fileSystemOptions);
                if (controlKeepAliveTimeout != null) {
                    client.setControlKeepAliveTimeout(controlKeepAliveTimeout);
                }

                final Duration controlKeepAliveReplyTimeout = builder
                        .getControlKeepAliveReplyTimeout(fileSystemOptions);
                if (controlKeepAliveReplyTimeout != null) {
                    client.setControlKeepAliveReplyTimeout(controlKeepAliveReplyTimeout);
                }

                final Boolean userDirIsRoot = builder.getUserDirIsRoot(fileSystemOptions);
                if (workingDirectory != null && (userDirIsRoot == null || !userDirIsRoot.booleanValue())
                        && !client.changeWorkingDirectory(workingDirectory)) {
                    throw new FileSystemException("vfs.provider.ftp/change-work-directory.error", workingDirectory);
                }

                final Boolean passiveMode = builder.getPassiveMode(fileSystemOptions);
                if (proxyMode || (passiveMode != null && passiveMode.booleanValue())) {
                    client.enterLocalPassiveMode();
                }

                final Range<Integer> activePortRange = builder.getActivePortRange(fileSystemOptions);
                if (activePortRange != null) {
                    client.setActivePortRange(activePortRange.getMinimum(), activePortRange.getMaximum());
                }

                setupOpenConnection(client, fileSystemOptions);
                return client;
            } catch (final Exception exc) {
                // The client is not handed out. Its sockets are closed with no command.
                group.abort(CONNECT_FAILED);
                throw new FileSystemException("vfs.provider.ftp/connect.error", exc, hostname);
            }
        }

        private int proxyTimeout(final String timeout) {
            try {
                return Integer.parseInt(timeout);
            } catch (NumberFormatException nfe) {
                log.warn("Invalid connection timeout " + timeout + ". Set the connectionTimeout as 5000. (default)");
                return DEFAULT_PROXY_TIMEOUT;
            }
        }

        private int retryCount(final String retryCount, final FileSystemOptions fileSystemOptions) {
            if (retryCount == null) {
                final Integer configured = builder.getRetryCount(fileSystemOptions);
                return configured != null ? configured : DEFAULT_RETRY_COUNT;
            }
            try {
                return Integer.parseInt(retryCount);
            } catch (NumberFormatException nfe) {
                log.warn("Invalid connection retry count " + retryCount + ". Set the connectionRetryCount as 5. "
                        + "(default)");
                return DEFAULT_RETRY_COUNT;
            }
        }

        // A next connect starts only after an IOException, while the count is not used up and the fast window of the
        // call is open. The loop ends with the exception of the last connect.
        private void connectThroughProxy(final C client, final String hostname, final int port, final int tries,
                final long windowStart) throws IOException {
            for (int tried = 1;; tried++) {
                try {
                    client.connect(hostname, port);
                    return;
                } catch (final IOException e) {
                    if (tried >= tries || !Faults.isFast(windowStart)) {
                        throw e;
                    }
                    // The socket of the failed connect is closed before the next one. Nothing is sent on it.
                    client.disconnect();
                }
            }
        }

        protected void preConfigureClient(FileSystemOptions fileSystemOptions) throws
                Exception {
            // nothing to do for FTP
        }

        /**
         * Sets up a new client.
         *
         * @param client            the client.
         * @param fileSystemOptions the file system options.
         * @throws IOException if an IO error occurs.
         */
        protected abstract void setupOpenConnection(C client, FileSystemOptions fileSystemOptions) throws IOException;
    }

    /**
     * Connection Factory, used to configure the FTPClient.
     */
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
     * @throws FileSystemException if an error occurs while establishing a connection.
     */
    public static FTPClient createConnection(final String hostname, final int port, final char[] username,
                                             final char[] password, final String workingDirectory,
                                             final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        final FtpConnectionFactory factory = new FtpConnectionFactory(FtpFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions);
    }

    /**
     * Creates a new connection to the server, through an HTTP proxy when a proxy server and a proxy port are given.
     *
     * @param hostname The host name of the server.
     * @param port The port to connect to.
     * @param username The name of the user for authentication.
     * @param password The user's password.
     * @param workingDirectory The base directory.
     * @param fileSystemOptions The FileSystemOptions.
     * @param proxyServer The HTTP proxy server, or null.
     * @param proxyPort The HTTP proxy port, or null.
     * @param proxyUser The HTTP proxy user, or null.
     * @param proxyPassword The HTTP proxy password, or null.
     * @param timeout The connect timeout through the proxy in milliseconds, or null.
     * @param retryCount The number of connects through the proxy, or null.
     * @param group The group of the new client. It is aborted when the connection fails.
     * @param windowStart The start of the fast window of the call, on the clock of {@link System#nanoTime()}.
     * @return An FTPClient.
     * @throws FileSystemException if an error occurs while establishing a connection.
     */
    public static FTPClient createConnection(final String hostname, final int port, final char[] username,
            final char[] password, final String workingDirectory, final FileSystemOptions fileSystemOptions,
            final String proxyServer, final String proxyPort, final String proxyUser, final String proxyPassword,
            final String timeout, final String retryCount, final SocketGroup group, final long windowStart)
            throws FileSystemException {
        final FtpConnectionFactory factory = new FtpConnectionFactory(FtpFileSystemConfigBuilder.getInstance());
        return factory.createConnection(hostname, port, username, password, workingDirectory, fileSystemOptions,
                proxyServer, proxyPort, proxyUser, proxyPassword, timeout, retryCount, group, windowStart);
    }
    private FtpClientFactory() {
    }
}
