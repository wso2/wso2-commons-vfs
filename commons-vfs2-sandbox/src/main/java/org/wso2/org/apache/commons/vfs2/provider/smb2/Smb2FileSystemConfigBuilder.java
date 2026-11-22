/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;

import java.time.Duration;
import java.util.ArrayList;

/**
 * The config builder for SMB2 configuration options.
 */
public class Smb2FileSystemConfigBuilder extends FileSystemConfigBuilder implements EffectiveBounds.Smb2Timeouts {

    private static final Smb2FileSystemConfigBuilder BUILDER = new Smb2FileSystemConfigBuilder();
    private static final String ENCRYPTION_ENABLED = "EncryptionEnabled";
    public static final String DISK_SHARE_ACCESS_MASK = "diskShareAccessMask";
    private static final String READ_TIMEOUT = "readTimeout";
    private static final String WRITE_TIMEOUT = "writeTimeout";
    private static final String TRANSACT_TIMEOUT = "transactTimeout";
    private static final String WRITE_STALL_TIMEOUT = "writeStallTimeout";
    // The default of smbj for each request timeout, and the default of the watchdog.
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public static Smb2FileSystemConfigBuilder getInstance() {
        return BUILDER;
    }

    /**
     * Get the EncryptionEnabled parameter for smb2 servers.
     *
     * @param opts The FileSystem options.
     * @return {@code true} if encryption is enabled, {@code false} otherwise.
     */
    public boolean getEncryptionEnabled(FileSystemOptions opts) {
        Object param = getParam(opts, ENCRYPTION_ENABLED);
        return (param instanceof Boolean) ? (Boolean) param : false;
    }

    /**
     * Get the DiskShareAccessMask parameter for smb2 servers.
     *
     * @param opts The FileSystem options.
     * @return The DiskShareAccessMask parameter.
     */
    public ArrayList<String> getDiskShareAccessMask(FileSystemOptions opts) {
        Object param = getParam(opts, DISK_SHARE_ACCESS_MASK);
        if (param == null || !(param instanceof ArrayList)) {
            return null; // Return null if param is null or not a String
        }
        ArrayList<String> config = (ArrayList<String>) param;
        return config;
    }

    /**
     * Set the EncryptionEnabled parameter for SMB2 servers.
     *
     * @param opts            The FileSystem options.
     * @param encryptionEnabled Whether encryption should be enabled for SMB2.
     */
    public void setEncryptionEnabled(FileSystemOptions opts, boolean encryptionEnabled) {
        setParam(opts, ENCRYPTION_ENABLED, encryptionEnabled);
    }

    /**
     * Set the DiskShareAccessMask parameter for SMB2 servers.
     *
     * @param opts The FileSystem options.
     * @param mask The DiskShareAccessMask parameter.
     */
    public void setDiskShareAccessMask(FileSystemOptions opts, ArrayList<String> mask) {
        setParam(opts, DISK_SHARE_ACCESS_MASK, mask);
    }

    /**
     * Sets the read timeout of each SMB2 read request.
     *
     * @param opts The FileSystem options.
     * @param timeout The timeout.
     */
    public void setReadTimeout(FileSystemOptions opts, Duration timeout) {
        setParam(opts, READ_TIMEOUT, timeout);
    }

    /**
     * Gets the read timeout of each SMB2 read request. Only the options decide. No system property is read.
     *
     * @param opts The FileSystem options.
     * @return The timeout of the options, or 60 seconds when the options hold none.
     */
    public Duration getReadTimeout(FileSystemOptions opts) {
        return valueOrDefault(opts, READ_TIMEOUT);
    }

    /**
     * Sets the write timeout of each SMB2 write request.
     *
     * @param opts The FileSystem options.
     * @param timeout The timeout.
     */
    public void setWriteTimeout(FileSystemOptions opts, Duration timeout) {
        setParam(opts, WRITE_TIMEOUT, timeout);
    }

    /**
     * Gets the write timeout of each SMB2 write request. Only the options decide. No system property is read.
     *
     * @param opts The FileSystem options.
     * @return The timeout of the options, or 60 seconds when the options hold none.
     */
    public Duration getWriteTimeout(FileSystemOptions opts) {
        return valueOrDefault(opts, WRITE_TIMEOUT);
    }

    /**
     * Sets the timeout of each other SMB2 request, for example a create, a query or a close.
     *
     * @param opts The FileSystem options.
     * @param timeout The timeout.
     */
    public void setTransactTimeout(FileSystemOptions opts, Duration timeout) {
        setParam(opts, TRANSACT_TIMEOUT, timeout);
    }

    /**
     * Gets the timeout of each other SMB2 request. Only the options decide. No system property is read.
     *
     * @param opts The FileSystem options.
     * @return The timeout of the options, or 60 seconds when the options hold none.
     */
    public Duration getTransactTimeout(FileSystemOptions opts) {
        return valueOrDefault(opts, TRANSACT_TIMEOUT);
    }

    /**
     * Sets the write stall timeout of the watchdog. A socket write that moves no bytes for this time is ended by a
     * close of the socket.
     *
     * @param opts The FileSystem options.
     * @param timeout The timeout. Zero turns the watchdog off for the file system.
     */
    public void setWriteStallTimeout(FileSystemOptions opts, Duration timeout) {
        setParam(opts, WRITE_STALL_TIMEOUT, timeout);
    }

    /**
     * Gets the write stall timeout of the watchdog. Only the options decide. No system property is read.
     *
     * @param opts The FileSystem options.
     * @return The timeout of the options, or 60 seconds when the options hold none.
     */
    public Duration getWriteStallTimeout(FileSystemOptions opts) {
        return valueOrDefault(opts, WRITE_STALL_TIMEOUT);
    }

    @Override
    public Duration configuredReadTimeout(FileSystemOptions opts) {
        return getParam(opts, READ_TIMEOUT);
    }

    @Override
    public Duration configuredWriteTimeout(FileSystemOptions opts) {
        return getParam(opts, WRITE_TIMEOUT);
    }

    @Override
    public Duration configuredTransactTimeout(FileSystemOptions opts) {
        return getParam(opts, TRANSACT_TIMEOUT);
    }

    @Override
    public Duration configuredWriteStallTimeout(FileSystemOptions opts) {
        return getParam(opts, WRITE_STALL_TIMEOUT);
    }

    private Duration valueOrDefault(FileSystemOptions opts, String name) {
        Duration value = getParam(opts, name);
        return value != null ? value : DEFAULT_TIMEOUT;
    }

    @Override
    protected Class<? extends FileSystem> getConfigClass() {

        return Smb2FileSystem.class;
    }
}
