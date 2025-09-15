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

package org.apache.commons.vfs2.provider.smb2;

import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemConfigBuilder;
import org.apache.commons.vfs2.FileSystemOptions;

import java.util.ArrayList;

/**
 * The config builder for SMB2 configuration options.
 */
public class Smb2FileSystemConfigBuilder extends FileSystemConfigBuilder {

    private static final Smb2FileSystemConfigBuilder BUILDER = new Smb2FileSystemConfigBuilder();
    private static final String ENCRYPTION_ENABLED = "EncryptionEnabled";
    public static final String DISK_SHARE_ACCESS_MASK = "diskShareAccessMask";

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

    @Override
    protected Class<? extends FileSystem> getConfigClass() {

        return Smb2FileSystem.class;
    }
}
