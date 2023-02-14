/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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


/**
 * Constants related to SFTP package.
 */
public interface SftpConstants {

    /** In the case of SFTP set the path from root if the param is presented in URL. */
    String SFTP_PATH_FROM_ROOT = "sftpPathFromRoot";

    /** Denote HTTP proxy type. */
    String HTTP = "http";

    /** Denote type of proxy whether SOCKS5 or HTTP. */
    String PROXY_TYPE = "proxyType";

    /** Proxy server address. */
    String PROXY_SERVER = "proxyServer";

    /** Proxy server port. */
    String PROXY_PORT = "proxyPort";

    /** Proxy server username. */
    String PROXY_USERNAME = "proxyUsername";

    /** Proxy server password. */
    String PROXY_PASSWORD = "proxyPassword";

    /** Default Timeout for the connection. */
    String TIMEOUT = "timeout";

    /** Number of retries allow to connect to server */
    String RETRY_COUNT = "retryCount";

    /** Denote SOCKS proxy type. */
    String SOCKS = "socks";

    /**
     * Specifies whether the Host key should be checked. If set to 'yes', the connector (JSch) will always verify
     * the public key (fingerprint) of the SSH/SFTP server.
     **/
    String STRICT_HOST_KEY_CHECKING = "transport.vfs.StrictHostKeyChecking";
}
