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

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.SystemProperties;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

import com.jcraft.jsch.ConfigRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Logger;
import com.jcraft.jsch.OpenSSHConfig;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;
import org.wso2.org.apache.commons.vfs2.UserAuthenticationData;
import org.wso2.org.apache.commons.vfs2.UserAuthenticator;

import org.wso2.org.apache.commons.vfs2.util.UserAuthenticatorUtils;

/**
 * Create a JSch Session instance.
 */
public final class SftpClientFactory {

    /** Interface JSchLogger with JCL. */
    private static final class JSchLogger implements Logger {
        @Override
        public boolean isEnabled(final int level) {
            switch (level) {
            case FATAL:
                return LOG.isFatalEnabled();
            case ERROR:
                return LOG.isErrorEnabled();
            case WARN:
                return LOG.isDebugEnabled();
            case DEBUG:
                return LOG.isDebugEnabled();
            case INFO:
                return LOG.isInfoEnabled();
            default:
                return LOG.isDebugEnabled();
            }
        }

        @Override
        public void log(final int level, final String msg) {
            switch (level) {
            case FATAL:
                LOG.fatal(msg);
                break;
            case ERROR:
                LOG.error(msg);
                break;
            case WARN:
                LOG.warn(msg);
                break;
            case DEBUG:
                LOG.debug(msg);
                break;
            case INFO:
                LOG.info(msg);
                break;
            default:
                LOG.debug(msg);
            }
        }
    }
    private static final String KEY_COMPRESSION_C2S = "compression.c2s";
    private static final String KEY_COMPRESSION_S2C = "compression.s2c";
    private static final String KEY_PREFERRED_AUTHENTICATIONS = "PreferredAuthentications";

    private static final String KEY_STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";

    private static final String SSH_DIR_NAME = ".ssh";
    private static final String OPENSSH_CONFIG_NAME = "config";
    private static final String SCHEME = "sftp";
    private static final Log LOG = LogFactory.getLog(SftpClientFactory.class);

    // The warning of a timeout with no keep alive is given once per copy of the fork.
    private static final AtomicBoolean BARE_TIMEOUT_WARNED = new AtomicBoolean();

    static {
        JSch.setLogger(new JSchLogger());
    }

    private static void addIdentities(final JSch jsch, final File sshDir, final IdentityProvider[] identities,
            final String passPhrase) throws FileSystemException {
        if (identities != null) {
            for (final IdentityProvider info : identities) {
                if (passPhrase != null && info instanceof IdentityInfo) {
                    addIdentity(jsch, (IdentityInfo) info, passPhrase);
                } else {
                    addIdentity(jsch, info);
                }
            }
        } else {
            // Load the private key (rsa-key only)
            final File privateKeyFile = new File(sshDir, "id_rsa");
            if (privateKeyFile.isFile() && privateKeyFile.canRead()) {
                addIdentity(jsch, new IdentityInfo(privateKeyFile), passPhrase);
            }
        }
    }

    private static void addIdentity(final JSch jsch, final IdentityProvider identity) throws FileSystemException {
        try {
            identity.addIdentity(jsch);
        } catch (final JSchException e) {
            throw new FileSystemException("vfs.provider.sftp/load-private-key.error", identity, e);
        }
    }

    /**
     * Creates a new connection to the server, with a socket group of its own.
     * <p>
     * No file system manager knows that group, so an abort of a manager does not reach this session. The provider
     * uses {@link #createConnection(String, int, char[], char[], FileSystemOptions, SocketGroup)}.
     * </p>
     *
     * @param hostname The name of the host to connect to.
     * @param port The port to use.
     * @param username The user's id.
     * @param password The user's password.
     * @param fileSystemOptions The FileSystem options.
     * @return A Session, never null.
     * @throws FileSystemException if an error occurs.
     */
    public static Session createConnection(final String hostname, final int port, final char[] username,
        final char[] password, final FileSystemOptions fileSystemOptions) throws FileSystemException {
        return createConnection(hostname, port, username, password, fileSystemOptions, new SocketGroup());
    }

    /**
     * Creates a new connection to the server.
     *
     * @param hostname The name of the host to connect to.
     * @param port The port to use.
     * @param username The user's id.
     * @param password The user's password.
     * @param fileSystemOptions The FileSystem options.
     * @param group The group of the sockets of the session. The session connects only through sockets of this group.
     * @return A Session, never null.
     * @throws FileSystemException if an error occurs.
     */
    public static Session createConnection(final String hostname, final int port, final char[] username,
        final char[] password, final FileSystemOptions fileSystemOptions, final SocketGroup group)
        throws FileSystemException {
        return createConnection(hostname, port, username, password, fileSystemOptions, group, 0);
    }

    /**
     * Creates a new connection to the server, with an optional deadline for its whole setup. With a deadline, the
     * connect, a proxy and every read of the handshake and of the login on the connecting thread end at it. The
     * bounds of the endpoint stay as they are for the session after its setup.
     *
     * @param setupDeadline the deadline on the clock of {@link System#nanoTime()}, or 0 for none.
     */
    static Session createConnection(final String hostname, final int port, final char[] username,
        final char[] password, final FileSystemOptions fileSystemOptions, final SocketGroup group,
        final long setupDeadline) throws FileSystemException {
        Objects.requireNonNull(username, "username");
        final JSch jsch = new JSch();

        // new style - user passed
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        final File knownHostsFile = builder.getKnownHosts(fileSystemOptions);
        final IdentityProvider[] identities = builder.getIdentityProvider(fileSystemOptions);
        final IdentityRepositoryFactory repositoryFactory = builder.getIdentityRepositoryFactory(fileSystemOptions);
        final ConfigRepository configRepository = builder.getConfigRepository(fileSystemOptions);
        final boolean loadOpenSSHConfig = builder.isLoadOpenSSHConfig(fileSystemOptions);

        final File sshDir = findSshDir();

        setKnownHosts(jsch, sshDir, knownHostsFile);

        if (repositoryFactory != null) {
            jsch.setIdentityRepository(repositoryFactory.create(jsch));
        }

        final String passPhrase = builder.getIdentityPassPhrase(fileSystemOptions);
        addIdentities(jsch, sshDir, identities, passPhrase);
        setConfigRepository(jsch, sshDir, configRepository, loadOpenSSHConfig);

        UserAuthenticationData proxyAuthData = null;

        Session session;
        SftpStreamProxy streamProxy = null;
        try {
            session = jsch.getSession(new String(username), hostname, port);
            if (password != null) {
                session.setPassword(new String(password));
            }

            final EffectiveBounds bounds = EffectiveBounds.of(SCHEME, fileSystemOptions);
            applyBounds(session, bounds);

            final UserInfo userInfo = builder.getUserInfo(fileSystemOptions);
            if (userInfo != null) {
                session.setUserInfo(userInfo);
            }

            final Properties config = new Properties();

            // set StrictHostKeyChecking property
            final String strictHostKeyChecking = builder.getStrictHostKeyChecking(fileSystemOptions);
            if (strictHostKeyChecking != null) {
                config.setProperty(KEY_STRICT_HOST_KEY_CHECKING, strictHostKeyChecking);
            }
            // set PreferredAuthentications property
            final String preferredAuthentications = builder.getPreferredAuthentications(fileSystemOptions);
            if (preferredAuthentications != null) {
                config.setProperty(KEY_PREFERRED_AUTHENTICATIONS, preferredAuthentications);
            }

            // set compression property
            final String compression = builder.getCompression(fileSystemOptions);
            if (compression != null) {
                config.setProperty(KEY_COMPRESSION_S2C, compression);
                config.setProperty(KEY_COMPRESSION_C2S, compression);
            }

            final String keyExchangeAlgorithm = builder.getKeyExchangeAlgorithm(fileSystemOptions);
            if (keyExchangeAlgorithm != null) {
                config.setProperty("kex", keyExchangeAlgorithm);
            }
            // The algorithms of this one session. A value that is not set leaves the default of JSch, which follows
            // the JVM wide jsch properties.
            final String hostKeyAlgorithms = builder.getHostKeyAlgorithms(fileSystemOptions);
            if (hostKeyAlgorithms != null) {
                config.setProperty("server_host_key", hostKeyAlgorithms);
            }
            final String ciphers = builder.getCiphers(fileSystemOptions);
            if (ciphers != null) {
                config.setProperty("cipher.s2c", ciphers);
                config.setProperty("cipher.c2s", ciphers);
            }
            final String macs = builder.getMacs(fileSystemOptions);
            if (macs != null) {
                config.setProperty("mac.s2c", macs);
                config.setProperty("mac.c2s", macs);
            }
            final String pubkeyAcceptedAlgorithms = builder.getPubkeyAcceptedAlgorithms(fileSystemOptions);
            if (pubkeyAcceptedAlgorithms != null) {
                config.setProperty("PubkeyAcceptedAlgorithms", pubkeyAcceptedAlgorithms);
            }

            final String proxyHost = builder.getProxyHost(fileSystemOptions);
            if (proxyHost != null) {
                final int proxyPort = builder.getProxyPort(fileSystemOptions);
                final SftpFileSystemConfigBuilder.ProxyType proxyType = builder.getProxyType(fileSystemOptions);
                final String proxyUser = builder.getProxyUser(fileSystemOptions);
                String proxyPasswordStr = builder.getProxyPassword(fileSystemOptions);
                char[] proxyPassword = proxyPasswordStr != null ? proxyPasswordStr.toCharArray() : null;
                Proxy proxy = null;
                UserAuthenticator proxyAuth = SftpFileSystemConfigBuilder.getInstance().getProxyUserAuthenticator
                        (fileSystemOptions);
                char[] proxyUsername = null;
                if (proxyAuth != null) {
                    proxyAuthData = UserAuthenticatorUtils.authenticate(proxyAuth, SftpFileProvider
                            .AUTHENTICATOR_TYPES);
                    proxyUsername = UserAuthenticatorUtils.getData(proxyAuthData, UserAuthenticationData.USERNAME,
                            null);
                    proxyPassword = UserAuthenticatorUtils.getData(proxyAuthData, UserAuthenticationData.PASSWORD,
                            null);
                }

                if (SftpFileSystemConfigBuilder.PROXY_HTTP.equals(proxyType)) {
                    proxy = createProxyHTTP(proxyHost, proxyPort, proxyUsername, proxyPassword);
                } else if (SftpFileSystemConfigBuilder.PROXY_SOCKS5.equals(proxyType)) {
                    proxy = createProxySOCKS5(proxyHost, proxyPort, proxyUsername, proxyPassword);
                } else if (SftpFileSystemConfigBuilder.PROXY_STREAM.equals(proxyType)) {
                    streamProxy = createStreamProxy(proxyHost, proxyPort, fileSystemOptions, builder, group, bounds,
                            setupDeadline);
                    proxy = streamProxy;
                }

                if (proxy != null) {
                    session.setProxy(proxy);
                }
            }

            // set properties for the session
            if (!config.isEmpty()) {
                session.setConfig(config);
            }
            session.setDaemonThread(true);
            if (LOG.isDebugEnabled()) {
                LOG.debug("SFTP bounds for " + hostname + ": session timeout "
                        + bounds.getSessionTimeout().getValue() + " ms from " + bounds.getSessionTimeout().getSource()
                        + ", keep alive count " + bounds.getKeepAliveCount().getValue() + " from "
                        + bounds.getKeepAliveCount().getSource() + ", connect timeout "
                        + bounds.getConnectTimeout().getValue() + " ms from "
                        + bounds.getConnectTimeout().getSource());
            }
            final int connectTimeoutMillis = setupBound((int) bounds.getConnectTimeout().getValue(), setupDeadline);
            final WatchedJschSocketFactory socketFactory = new WatchedJschSocketFactory(group, connectTimeoutMillis,
                    bounds.getWriteStallTimeout().getValue(), setupDeadline);
            session.setSocketFactory(socketFactory);
            session.connect(connectTimeoutMillis);
            socketFactory.endSetup();
            if (streamProxy != null) {
                streamProxy.sessionConnected();
            }
        } catch (final Exception exc) {
            throw new FileSystemException("vfs.provider.sftp/connect.error", exc, hostname);
        } finally {
            UserAuthenticatorUtils.cleanup(proxyAuthData);
        }

        return session;
    }

    private static ProxyHTTP createProxyHTTP(final String proxyHost, final int proxyPort) {
        return proxyPort == 0 ? new ProxyHTTP(proxyHost) : new ProxyHTTP(proxyHost, proxyPort);
    }

    private static ProxySOCKS5 createProxySOCKS5(final String proxyHost, final int proxyPort) {
        return proxyPort == 0 ? new ProxySOCKS5(proxyHost) : new ProxySOCKS5(proxyHost, proxyPort);
    }

    // A timeout of the endpoint, zero for no bound, cut to the time left before a setup deadline when there is one.
    static int setupBound(final int endpointMillis, final long setupDeadline) throws SocketTimeoutException {
        if (setupDeadline == 0) {
            return endpointMillis;
        }
        final long left = TimeUnit.NANOSECONDS.toMillis(setupDeadline - System.nanoTime());
        if (left <= 0) {
            throw new SocketTimeoutException("The setup of the SFTP session passed its deadline.");
        }
        final int bounded = (int) Math.min(left, Integer.MAX_VALUE);
        return endpointMillis == 0 ? bounded : Math.min(endpointMillis, bounded);
    }

    private static SftpStreamProxy createStreamProxy(final String proxyHost, final int proxyPort,
            final FileSystemOptions fileSystemOptions, final SftpFileSystemConfigBuilder builder,
            final SocketGroup group, final EffectiveBounds bounds, final long setupDeadline) {
        // Use a stream proxy, i.e. it will use a remote host as a proxy
        // and run a command (e.g. netcat) that forwards input/output
        // to the target host.

        // Here we get the settings for connecting to the proxy:
        // user, password, options and a command
        final String proxyUser = builder.getProxyUser(fileSystemOptions);
        final String proxyPassword = builder.getProxyPassword(fileSystemOptions);
        final FileSystemOptions proxyOptions = builder.getProxyOptions(fileSystemOptions);

        final String proxyCommand = builder.getProxyCommand(fileSystemOptions);

        // Create the stream proxy. It reads the channel to the target with the read timeout of the session to the
        // target, and its proxy session is a child of the group of that session.
        return new SftpStreamProxy(proxyCommand, proxyUser, proxyHost, proxyPort, proxyPassword, proxyOptions, group,
                (int) bounds.getSessionTimeout().getValue(), bounds.getWriteStallTimeout().getValue(), setupDeadline);
    }

    private static ProxySOCKS5 createProxySOCKS5(final String proxyHost, final int proxyPort, char[] proxyUsername,
                                                 char[] proxyPassword) {
        ProxySOCKS5 proxy = (proxyPort == 0) ? new ProxySOCKS5(proxyHost) : new ProxySOCKS5(proxyHost, proxyPort);
        if (proxyUsername != null && proxyPassword != null) {
            proxy.setUserPasswd(new String(proxyUsername),
                    new String(proxyPassword));
        }
        return proxy;
    }

    private static ProxyHTTP createProxyHTTP(final String proxyHost, final int proxyPort, char[] proxyUsername,
                                             char[] proxyPassword) {
        ProxyHTTP proxy = (proxyPort == 0) ? new ProxyHTTP(proxyHost) : new ProxyHTTP(proxyHost, proxyPort);

        if (proxyUsername != null && proxyPassword != null) {
            proxy.setUserPasswd(new String(proxyUsername),
                    new String(proxyPassword));
        }
        return proxy;
    }

    /**
     * Finds the {@code .ssh} directory.
     * <p>
     * The lookup order is:
     * </p>
     * <ol>
     * <li>The system property {@code vfs.sftp.sshdir} (the override mechanism)</li>
     * <li>{@code user.home}/.ssh</li>
     * <li>On Windows only: {@code C:\cygwin\home[user.name]\.ssh}</li>
     * <li>The current directory, as a last resort.</li>
     * </ol>
     *
     * <h2>Windows Notes</h2>
     * <p>
     * The default installation directory for Cygwin is {@code C:\cygwin}. On my set up (Gary here), I have Cygwin in
     * {@code C:\bin\cygwin}, not the default. Also, my .ssh directory was created in the {@code user.home} directory.
     * </p>
     *
     * @return The {@code .ssh} directory
     */
    private static File findSshDir() {
        final String sshDirPath;
        sshDirPath = System.getProperty("vfs.sftp.sshdir");
        if (sshDirPath != null) {
            final File sshDir = new File(sshDirPath);
            if (sshDir.exists()) {
                return sshDir;
            }
        }

        File sshDir = new File(SystemProperties.getUserHome(), SSH_DIR_NAME);
        if (sshDir.exists()) {
            return sshDir;
        }

        if (SystemUtils.IS_OS_WINDOWS) {
            // TODO - this may not be true
            final String userName = SystemProperties.getUserName();
            sshDir = new File("C:\\cygwin\\home\\" + userName + "\\" + SSH_DIR_NAME);
            if (sshDir.exists()) {
                return sshDir;
            }
        }
        return new File("");
    }

    private static void setConfigRepository(final JSch jsch, final File sshDir, final ConfigRepository configRepository, final boolean loadOpenSSHConfig)
        throws FileSystemException {
        if (configRepository != null) {
            jsch.setConfigRepository(configRepository);
        } else if (loadOpenSSHConfig) {
            try {
                // loading openssh config (~/.ssh/config)
                final ConfigRepository openSSHConfig = OpenSSHConfig.parseFile(new File(sshDir, OPENSSH_CONFIG_NAME).getAbsolutePath());
                jsch.setConfigRepository(openSSHConfig);
            } catch (final IOException e) {
                throw new FileSystemException("vfs.provider.sftp/load-openssh-config.error", e);
            }
        }
    }

    private static void setKnownHosts(final JSch jsch, final File sshDir, File knownHostsFile)
            throws FileSystemException {
        try {
            if (knownHostsFile != null) {
                jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
            } else {
                // Load the known hosts file
                knownHostsFile = new File(sshDir, "known_hosts");
                if (knownHostsFile.isFile() && knownHostsFile.canRead()) {
                    jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
                }
            }
        } catch (final JSchException e) {
            throw new FileSystemException("vfs.provider.sftp/known-hosts.error", knownHostsFile.getAbsolutePath(), e);
        }

    }

    private SftpClientFactory() {
    }

    /**
     * Puts the session timeout and the keep alive count of the bounds on a session that is not connected yet.
     * <p>
     * A system property of zero sets no timeout, and a derived keep alive count keeps the count of JSch.
     * </p>
     *
     * @param session the session.
     * @param bounds the bounds in effect.
     * @throws JSchException if JSch refuses the timeout.
     */
    static void applyBounds(final Session session, final EffectiveBounds bounds) throws JSchException {
        final EffectiveBounds.Bound timeout = bounds.getSessionTimeout();
        if (timeout.getSource() != EffectiveBounds.Source.SYSTEM_PROPERTY || timeout.getValue() > 0) {
            session.setTimeout((int) timeout.getValue());
        }
        final EffectiveBounds.Bound count = bounds.getKeepAliveCount();
        if (count.getSource() != EffectiveBounds.Source.DERIVED) {
            session.setServerAliveCountMax((int) count.getValue());
        }
        if (timeout.getValue() > 0 && count.getValue() == 0) {
            final String warning = "The SFTP session timeout is " + timeout.getValue()
                    + " ms and the keep alive count is 0. A slow server that still answers can lose its session.";
            if (BARE_TIMEOUT_WARNED.compareAndSet(false, true)) {
                LOG.warn(warning);
            } else if (LOG.isDebugEnabled()) {
                LOG.debug(warning);
            }
        }
    }

    private static void addIdentity(final JSch jsch, final IdentityInfo info, String passPhrase) throws
            FileSystemException {
        try {
            final String privateKeyFile = info.getPrivateKey() != null ? info.getPrivateKey().getAbsolutePath() : null;
            final String publicKeyFile = info.getPublicKey() != null ? info.getPublicKey().getAbsolutePath() : null;

            if (passPhrase != null) {
                jsch.addIdentity(privateKeyFile, passPhrase);
            } else {
                jsch.addIdentity(privateKeyFile, publicKeyFile, info.getPassPhrase());
            }

        } catch (final JSchException e) {
            throw new FileSystemException("vfs.provider.sftp/load-private-key.error", info, e);
        }
    }
}
