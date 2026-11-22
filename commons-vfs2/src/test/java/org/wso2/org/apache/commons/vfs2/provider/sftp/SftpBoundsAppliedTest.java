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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Bound;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

/**
 * An explicit zero on the endpoint is the value that the session carries.
 */
public class SftpBoundsAppliedTest {

    private static final SftpFileSystemConfigBuilder BUILDER = SftpFileSystemConfigBuilder.getInstance();

    @Test
    public void sessionCarriesAnExplicitZeroOfTheEndpoint() throws Exception {
        final FileSystemOptions zero = new FileSystemOptions();
        BUILDER.setSessionTimeout(zero, Duration.ZERO);
        BUILDER.setKeepAliveCountMax(zero, 0);
        BUILDER.setConnectTimeout(zero, Duration.ZERO);
        final EffectiveBounds bounds = EffectiveBounds.of("sftp", zero);
        assertBound(0, Source.ENDPOINT, bounds.getSessionTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getKeepAliveCount());
        assertBound(0, Source.ENDPOINT, bounds.getConnectTimeout());
        final Session session = new JSch().getSession("user", "localhost", 22);
        SftpClientFactory.applyBounds(session, bounds);
        assertEquals(0, session.getTimeout());
        assertEquals(0, session.getServerAliveCountMax());
    }

    private static void assertBound(final long value, final Source source, final Bound bound) {
        assertEquals(value, bound.getValue(), String.valueOf(bound));
        assertEquals(source, bound.getSource(), String.valueOf(bound));
    }
}
