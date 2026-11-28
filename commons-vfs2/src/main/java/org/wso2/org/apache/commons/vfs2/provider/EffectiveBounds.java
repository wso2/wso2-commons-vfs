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
package org.wso2.org.apache.commons.vfs2.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.time.DurationUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;

/**
 * The timeouts that the fork enforces for a set of options, each with where its value came from.
 * <p>
 * The factories do not resolve their values themselves. They apply what this class gives, so a value that a caller
 * logs is the value that the code enforces. It makes no remote call and opens no connection.
 * </p>
 * <p>
 * A local path has no bound in the JVM. For the scheme {@code file} the object holds no bound, and a mount takes its
 * bound from the mount options.
 * </p>
 */
public final class EffectiveBounds {

    /**
     * Where the value of a bound came from.
     */
    public enum Source {

        /** The options of the endpoint set it. */
        ENDPOINT,

        /** A system property of the JVM set it. */
        SYSTEM_PROPERTY,

        /** It follows from another value of the endpoint. */
        DERIVED,

        /** The default of the fork. */
        DEFAULT,

        /** A fixed value of the fork that no option changes. */
        FIXED
    }

    /**
     * The value of one bound and where it came from.
     */
    public static final class Bound {

        private final long value;

        private final Source source;

        Bound(final long value, final Source source) {
            this.value = value;
            this.source = source;
        }

        /**
         * Gets the value. A time is in milliseconds, and zero means no bound. A count is a plain number.
         *
         * @return the value.
         */
        public long getValue() {
            return value;
        }

        /**
         * Gets where the value came from.
         *
         * @return the source.
         */
        public Source getSource() {
            return source;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Bound)) {
                return false;
            }
            final Bound bound = (Bound) other;
            return value == bound.value && source == bound.source;
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, source);
        }

        @Override
        public String toString() {
            return value + " from " + source;
        }
    }

    /**
     * The timeouts that a set of options holds for SMB2. The provider of SMB2 is in another module, which this class
     * can not name, so its config builder gives them.
     */
    public interface Smb2Timeouts {

        /**
         * Gets the read timeout that the options hold.
         *
         * @param options the options, or null for none.
         * @return the timeout, or null when the options hold none.
         */
        Duration configuredReadTimeout(FileSystemOptions options);

        /**
         * Gets the write timeout that the options hold.
         *
         * @param options the options, or null for none.
         * @return the timeout, or null when the options hold none.
         */
        Duration configuredWriteTimeout(FileSystemOptions options);

        /**
         * Gets the transact timeout that the options hold.
         *
         * @param options the options, or null for none.
         * @return the timeout, or null when the options hold none.
         */
        Duration configuredTransactTimeout(FileSystemOptions options);

        /**
         * Gets the write stall timeout that the options hold.
         *
         * @param options the options, or null for none.
         * @return the timeout, or null when the options hold none.
         */
        Duration configuredWriteStallTimeout(FileSystemOptions options);
    }

    /**
     * The system property of the second step of the SFTP session timeout, in milliseconds.
     */
    public static final String SFTP_TIMEOUT_PROPERTY = "vfs.sftp.timeout.default";

    /**
     * The system property of the second step of the SFTP keep alive count.
     */
    public static final String SFTP_KEEP_ALIVE_COUNT_PROPERTY = "vfs.sftp.keepAliveCount.default";

    /**
     * The system property of the second step of the SFTP connect timeout, in milliseconds.
     */
    public static final String SFTP_CONNECT_TIMEOUT_PROPERTY = "vfs.sftp.connectTimeout.default";

    private static final String SFTP = "sftp";

    private static final String FTP = "ftp";

    private static final String FTPS = "ftps";

    private static final String SMB2 = "smb2";

    private static final String LOCAL = "file";

    // The config builder of the SMB2 provider, in the sandbox module. It is loaded by name, as the manager loads a
    // provider.
    private static final String SMB2_BUILDER =
            "org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileSystemConfigBuilder";

    private static final long SMB2_REQUEST_TIMEOUT_MILLIS = 60000;

    // smbj sends no keep alive and takes a socket read timeout as fatal, so the socket timeout stays 0.
    private static final long SMB2_SOCKET_TIMEOUT_MILLIS = 0;

    private static final long SMB2_CONNECT_TIMEOUT_MILLIS = 5000;

    private static final long FTP_CONNECT_TIMEOUT_MILLIS = 30000;

    private static final long FTP_SOCKET_TIMEOUT_MILLIS = 60000;

    private static final long FTP_DATA_TIMEOUT_MILLIS = 60000;

    private static final long SFTP_TIMEOUT_MILLIS = 30000;

    private static final long SFTP_KEEP_ALIVE_COUNT = 3;

    private static final long SFTP_CONNECT_TIMEOUT_MILLIS = 30000;

    // The count that JSch keeps when nobody sets one.
    private static final long JSCH_KEEP_ALIVE_COUNT = 1;

    private static final long WRITE_STALL_TIMEOUT_MILLIS = 60000;

    private static final Log LOG = LogFactory.getLog(EffectiveBounds.class);

    // A wrong value of a property is told once per value in a copy of the fork.
    private static final Set<String> WARNED_PROPERTIES = ConcurrentHashMap.newKeySet();

    private final String scheme;

    private final Bound connectTimeout;

    private final Bound sessionTimeout;

    private final Bound keepAliveCount;

    private final Bound writeStallTimeout;

    private final Bound socketTimeout;

    private final Bound dataTimeout;

    private final Bound readTimeout;

    private final Bound writeTimeout;

    private final Bound transactTimeout;

    private EffectiveBounds(final String scheme, final Bound connectTimeout, final Bound sessionTimeout,
            final Bound keepAliveCount, final Bound writeStallTimeout, final Bound socketTimeout,
            final Bound dataTimeout, final Bound readTimeout, final Bound writeTimeout, final Bound transactTimeout) {
        this.scheme = scheme;
        this.connectTimeout = connectTimeout;
        this.sessionTimeout = sessionTimeout;
        this.keepAliveCount = keepAliveCount;
        this.writeStallTimeout = writeStallTimeout;
        this.socketTimeout = socketTimeout;
        this.dataTimeout = dataTimeout;
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
        this.transactTimeout = transactTimeout;
    }

    /**
     * Gets the bounds that the fork enforces for a scheme and a set of options.
     *
     * @param scheme the scheme of the file system.
     * @param options the options of the file system, or null for none.
     * @return the bounds.
     * @throws IllegalArgumentException for a scheme whose bounds this class does not know.
     */
    public static EffectiveBounds of(final String scheme, final FileSystemOptions options) {
        if (SFTP.equals(scheme)) {
            return sftp(options);
        }
        if (FTP.equals(scheme)) {
            return ftp(FTP, FtpFileSystemConfigBuilder.getInstance(), options);
        }
        if (FTPS.equals(scheme)) {
            return ftp(FTPS, FtpsFileSystemConfigBuilder.getInstance(), options);
        }
        if (SMB2.equals(scheme) && Smb2Lookup.TIMEOUTS != null) {
            return smb2(Smb2Lookup.TIMEOUTS, options);
        }
        if (LOCAL.equals(scheme)) {
            return new EffectiveBounds(LOCAL, null, null, null, null, null, null, null, null, null);
        }
        throw new IllegalArgumentException("The bounds of the scheme " + scheme + " are not known.");
    }

    // The ladder of each value: the endpoint, then the system property, then the derived value, then the default.
    private static EffectiveBounds sftp(final FileSystemOptions options) {
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();

        final Bound endpointTimeout = builder.isSessionTimeoutSet(options)
                ? new Bound(millis(builder.getSessionTimeout(options)), Source.ENDPOINT) : null;
        final Bound session = first(endpointTimeout, endpointTimeout == null ? property(SFTP_TIMEOUT_PROPERTY) : null,
                new Bound(SFTP_TIMEOUT_MILLIS, Source.DEFAULT));

        final Bound endpointCount = builder.isKeepAliveCountMaxSet(options)
                ? new Bound(builder.getKeepAliveCountMax(options), Source.ENDPOINT) : null;
        final Bound derivedCount = endpointTimeout != null ? new Bound(JSCH_KEEP_ALIVE_COUNT, Source.DERIVED) : null;
        final Bound count = first(endpointCount, endpointCount == null ? property(SFTP_KEEP_ALIVE_COUNT_PROPERTY) : null,
                derivedCount, new Bound(SFTP_KEEP_ALIVE_COUNT, Source.DEFAULT));

        final Bound endpointConnect = builder.isConnectTimeoutSet(options)
                ? new Bound(millis(builder.getConnectTimeout(options)), Source.ENDPOINT) : null;
        final Bound derivedConnect = endpointTimeout != null
                ? new Bound(endpointTimeout.getValue(), Source.DERIVED) : null;
        final Bound connect = first(endpointConnect,
                endpointConnect == null ? property(SFTP_CONNECT_TIMEOUT_PROPERTY) : null, derivedConnect,
                new Bound(SFTP_CONNECT_TIMEOUT_MILLIS, Source.DEFAULT));

        final Duration stall = builder.getWriteStallTimeout(options);
        final Bound writeStall = stall != null ? new Bound(millis(stall), Source.ENDPOINT)
                : new Bound(WRITE_STALL_TIMEOUT_MILLIS, Source.DEFAULT);

        return new EffectiveBounds(SFTP, connect, session, count, writeStall, null, null, null, null, null);
    }

    // Each value is the endpoint option, else the default. FTP has no system property and no derived value.
    private static EffectiveBounds ftp(final String scheme, final FtpFileSystemConfigBuilder builder,
            final FileSystemOptions options) {
        final Bound connect = endpointOr(builder.getConnectTimeoutDuration(options), FTP_CONNECT_TIMEOUT_MILLIS);
        final Bound socket = endpointOr(builder.getSoTimeoutDuration(options), FTP_SOCKET_TIMEOUT_MILLIS);
        final Bound data = endpointOr(builder.getDataTimeoutDuration(options), FTP_DATA_TIMEOUT_MILLIS);
        final Bound stall = endpointOr(builder.getWriteStallTimeout(options), WRITE_STALL_TIMEOUT_MILLIS);
        return new EffectiveBounds(scheme, connect, null, null, stall, socket, data, null, null, null);
    }

    // Each request timeout is the endpoint option, else the default of smbj. SMB2 has no system property. The socket
    // timeout and the connect timeout are fixed.
    private static EffectiveBounds smb2(final Smb2Timeouts timeouts, final FileSystemOptions options) {
        final Bound read = endpointOr(timeouts.configuredReadTimeout(options), SMB2_REQUEST_TIMEOUT_MILLIS);
        final Bound write = endpointOr(timeouts.configuredWriteTimeout(options), SMB2_REQUEST_TIMEOUT_MILLIS);
        final Bound transact = endpointOr(timeouts.configuredTransactTimeout(options), SMB2_REQUEST_TIMEOUT_MILLIS);
        final Bound stall = endpointOr(timeouts.configuredWriteStallTimeout(options), WRITE_STALL_TIMEOUT_MILLIS);
        return new EffectiveBounds(SMB2, new Bound(SMB2_CONNECT_TIMEOUT_MILLIS, Source.FIXED), null, null, stall,
                new Bound(SMB2_SOCKET_TIMEOUT_MILLIS, Source.FIXED), null, read, write, transact);
    }

    // Loaded the first time a caller asks for smb2. Null when the SMB2 provider is not on the class path.
    private static final class Smb2Lookup {

        static final Smb2Timeouts TIMEOUTS = load();

        private static Smb2Timeouts load() {
            try {
                final Class<?> builder = Class.forName(SMB2_BUILDER, true, EffectiveBounds.class.getClassLoader());
                return (Smb2Timeouts) builder.getConstructor().newInstance();
            } catch (final ReflectiveOperationException e) {
                return null;
            }
        }
    }

    private static Bound endpointOr(final Duration endpoint, final long defaultMillis) {
        return endpoint != null ? new Bound(millis(endpoint), Source.ENDPOINT) : new Bound(defaultMillis, Source.DEFAULT);
    }

    private static Bound first(final Bound... steps) {
        for (final Bound step : steps) {
            if (step != null) {
                return step;
            }
        }
        throw new IllegalStateException("A ladder has no default.");
    }

    private static long millis(final Duration duration) {
        return DurationUtils.toMillisInt(duration);
    }

    // A value that is negative or not a number is told once and skipped, and the ladder goes on.
    private static Bound property(final String name) {
        final String text = System.getProperty(name);
        if (text == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(text.trim());
            if (value >= 0) {
                return new Bound(value, Source.SYSTEM_PROPERTY);
            }
            warnOnce(name, text, "is negative");
        } catch (final NumberFormatException e) {
            warnOnce(name, text, "is not a number");
        }
        return null;
    }

    private static void warnOnce(final String name, final String text, final String reason) {
        if (WARNED_PROPERTIES.add(name + "=" + text)) {
            LOG.warn("The system property " + name + " has the value '" + text + "', which " + reason
                    + ". The fork ignores it and takes the next step of the ladder.");
        }
    }

    /**
     * Gets the scheme.
     *
     * @return the scheme.
     */
    public String getScheme() {
        return scheme;
    }

    /**
     * Gets the connect timeout.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Gets the session timeout, which is the read timeout of the SFTP session.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getSessionTimeout() {
        return sessionTimeout;
    }

    /**
     * Gets the keep alive count of the SFTP session.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getKeepAliveCount() {
        return keepAliveCount;
    }

    /**
     * Gets the write stall timeout of the watchdog. Zero turns the watchdog off for the file system.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getWriteStallTimeout() {
        return writeStallTimeout;
    }

    /**
     * Gets the socket timeout, which bounds each control reply on FTP and FTPS.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getSocketTimeout() {
        return socketTimeout;
    }

    /**
     * Gets the data timeout, which bounds each data read and the wait for an active data connection on FTP and FTPS.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getDataTimeout() {
        return dataTimeout;
    }

    /**
     * Gets the timeout of each SMB2 read request.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getReadTimeout() {
        return readTimeout;
    }

    /**
     * Gets the timeout of each SMB2 write request.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getWriteTimeout() {
        return writeTimeout;
    }

    /**
     * Gets the timeout of each other SMB2 request, for example a create, a query or a close.
     *
     * @return the bound, or null for a scheme that has none.
     */
    public Bound getTransactTimeout() {
        return transactTimeout;
    }

    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof EffectiveBounds)) {
            return false;
        }
        final EffectiveBounds bounds = (EffectiveBounds) other;
        return scheme.equals(bounds.scheme) && Objects.equals(connectTimeout, bounds.connectTimeout)
                && Objects.equals(sessionTimeout, bounds.sessionTimeout)
                && Objects.equals(keepAliveCount, bounds.keepAliveCount)
                && Objects.equals(writeStallTimeout, bounds.writeStallTimeout)
                && Objects.equals(socketTimeout, bounds.socketTimeout) && Objects.equals(dataTimeout, bounds.dataTimeout)
                && Objects.equals(readTimeout, bounds.readTimeout) && Objects.equals(writeTimeout, bounds.writeTimeout)
                && Objects.equals(transactTimeout, bounds.transactTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, connectTimeout, sessionTimeout, keepAliveCount, writeStallTimeout, socketTimeout,
                dataTimeout, readTimeout, writeTimeout, transactTimeout);
    }

    /**
     * Gives the bounds as one line of text, each with its value and its source, for the init log of a component.
     *
     * @return the text.
     */
    @Override
    public String toString() {
        if (connectTimeout == null) {
            return scheme + ": the fork enforces no bound. A mount takes its bound from the mount options";
        }
        if (readTimeout != null) {
            return scheme + ": connect timeout " + connectTimeout + ", socket timeout " + socketTimeout
                    + ", read timeout " + readTimeout + ", write timeout " + writeTimeout + ", transact timeout "
                    + transactTimeout + ", write stall timeout " + writeStallTimeout;
        }
        if (socketTimeout != null) {
            return scheme + ": connect timeout " + connectTimeout + ", socket timeout " + socketTimeout
                    + ", data timeout " + dataTimeout + ", write stall timeout " + writeStallTimeout;
        }
        return scheme + ": connect timeout " + connectTimeout + ", session timeout " + sessionTimeout
                + ", keep alive count " + keepAliveCount + ", write stall timeout " + writeStallTimeout;
    }
}
