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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.wso2.org.apache.commons.vfs2.FileSystemOptions;

import com.jcraft.jsch.JSch;

/**
 * The options that a session to the embedded test server needs, set on the options of the fixture only.
 * <p>
 * The test server, Apache SSHD 0.8.0, offers only old key exchanges and host key types, and JSch 2.27.7 proposes none
 * of them by default. So the options of a fixture add one of each to the defaults of JSch, for that fixture alone. The
 * defaults of the JVM do not change. JSch also adds each new host key to its known hosts file, so a fixture uses a file
 * of its own and never the one of the user.
 * </p>
 */
final class TestServerOptions {

    private static final File KNOWN_HOSTS = knownHostsFile();

    private TestServerOptions() {
    }

    static void apply(final FileSystemOptions options) {
        final SftpFileSystemConfigBuilder builder = SftpFileSystemConfigBuilder.getInstance();
        builder.setKeyExchangeAlgorithm(options, JSch.getConfig("kex") + ",diffie-hellman-group14-sha1");
        builder.setHostKeyAlgorithms(options, JSch.getConfig("server_host_key") + ",ssh-rsa,ssh-dss");
        builder.setKnownHosts(options, KNOWN_HOSTS);
    }

    static File knownHosts() {
        return KNOWN_HOSTS;
    }

    // The folder lives as long as the test JVM. The folder is registered first, so the file goes before it.
    private static File knownHostsFile() {
        try {
            final File folder = Files.createTempDirectory("sftp-test-hosts").toFile();
            folder.deleteOnExit();
            final File file = new File(folder, "known_hosts");
            file.deleteOnExit();
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
