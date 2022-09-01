/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.commons.vfs2.provider.sftp;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

public class SftpClient {
    private ChannelSftp channel;
    private Session session;
    private FileSystemOptions fso;
    private GenericFileName rootName;

    public SftpClient(final FileSystemOptions fileSystemOptions, GenericFileName rootName) throws FileSystemException {
        this.fso = fileSystemOptions;
        this.rootName = rootName;
        // create the session first
        ensureSession();
    }

    public Channel getChannel(String type) throws FileSystemException, JSchException {
        ensureSession();
        Channel requestedChannel = session.openChannel(type);
        return requestedChannel;
    }

    public ChannelSftp getChannel() throws FileSystemException {
        // first we make sure there is a corresponding session for the channel we are about to create
        // in case, close has been closed before
        ensureSession();
        // create a new channel using the ensured session
        // There cannot be a scenario where session is not available but channel is available
        if (channel == null || !channel.isConnected()) {
            try {
                channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect();
                final Boolean userDirIsRoot = SftpFileSystemConfigBuilder.getInstance().getUserDirIsRoot(fso);
                final String workingDirectory = rootName.getPath();
                if (workingDirectory != null && (userDirIsRoot == null || !userDirIsRoot.booleanValue())) {
                    try {
                        channel.cd(workingDirectory);
                    } catch (final SftpException e) {
                        throw new FileSystemException("vfs.provider.sftp/change-work-directory.error", workingDirectory, e);
                    }
                }
            } catch (JSchException | FileSystemException e) {
                throw new FileSystemException("vfs.provider.sftp/change-work-directory.error", e);
            }
        }
        return channel;
    }

    public void close() {
        if (null != channel) {
            channel.disconnect();
            channel = null;
        }

        if (null != session) {
            session.disconnect();
            session = null;
        }
    }

    private void ensureSession() throws FileSystemException {
        if (this.session == null || !this.session.isConnected()) {
            // if the session is unavailable, we make sure corresponding channel is also closed
            close();
            // channel closed. e.g. by freeUnusedResources, but now we need it again
            Session session;
            UserAuthenticationData authData = null;
            try {
                authData = UserAuthenticatorUtils.authenticate(fso,
                        SftpFileProvider.AUTHENTICATOR_TYPES);

                session = SftpClientFactory.createConnection(rootName.getHostName(), rootName.getPort(),
                        UserAuthenticatorUtils.getData(authData, UserAuthenticationData.USERNAME,
                                UserAuthenticatorUtils.toChar(rootName.getUserName())),
                        UserAuthenticatorUtils.getData(authData, UserAuthenticationData.PASSWORD,
                                UserAuthenticatorUtils.toChar(rootName.getPassword())), fso);
            } catch (final Exception e) {
                throw new FileSystemException("vfs.provider.sftp/connect.error", fso, e);
            } finally {
                UserAuthenticatorUtils.cleanup(authData);
            }
            this.session = session;
        }
    }
}
