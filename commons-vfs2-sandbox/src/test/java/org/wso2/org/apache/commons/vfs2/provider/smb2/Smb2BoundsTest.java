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
package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.time.Duration;

import com.hierynomus.smbj.SmbConfig;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Bound;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.WatchedSocketFactory;

/**
 * The SMB2 branch of {@link EffectiveBounds}, part 3 sections 3.4 and 3.7, and the config of a client, which applies
 * what that branch gives.
 */
public class Smb2BoundsTest {

    private static final Smb2FileSystemConfigBuilder BUILDER = Smb2FileSystemConfigBuilder.getInstance();

    @Test
    public void optionsOfTheEndpointAreTheBoundsAndTheConfigCarriesThem() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setReadTimeout(options, Duration.ofMillis(20000));
        BUILDER.setWriteTimeout(options, Duration.ofMillis(25000));
        BUILDER.setTransactTimeout(options, Duration.ofMillis(30000));
        BUILDER.setWriteStallTimeout(options, Duration.ofMillis(10000));
        final EffectiveBounds bounds = EffectiveBounds.of("smb2", options);
        assertEquals("smb2", bounds.getScheme());
        assertBound(20000, Source.ENDPOINT, bounds.getReadTimeout());
        assertBound(25000, Source.ENDPOINT, bounds.getWriteTimeout());
        assertBound(30000, Source.ENDPOINT, bounds.getTransactTimeout());
        assertBound(10000, Source.ENDPOINT, bounds.getWriteStallTimeout());
        assertFixed(bounds);
        assertCarries(bounds, options);
    }

    @Test
    public void nothingSetGivesTheDefaultsAndTheConfigCarriesThem() throws Exception {
        final EffectiveBounds bounds = EffectiveBounds.of("smb2", new FileSystemOptions());
        assertBound(60000, Source.DEFAULT, bounds.getReadTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getWriteTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getTransactTimeout());
        assertBound(60000, Source.DEFAULT, bounds.getWriteStallTimeout());
        assertFixed(bounds);
        assertCarries(bounds, new FileSystemOptions());
        assertEquals(bounds, EffectiveBounds.of("smb2", null));
    }

    @Test
    public void explicitZeroIsKeptAndTheConfigCarriesIt() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setReadTimeout(options, Duration.ZERO);
        BUILDER.setWriteTimeout(options, Duration.ZERO);
        BUILDER.setTransactTimeout(options, Duration.ZERO);
        BUILDER.setWriteStallTimeout(options, Duration.ZERO);
        final EffectiveBounds bounds = EffectiveBounds.of("smb2", options);
        assertBound(0, Source.ENDPOINT, bounds.getReadTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getWriteTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getTransactTimeout());
        assertBound(0, Source.ENDPOINT, bounds.getWriteStallTimeout());
        assertCarries(bounds, options);
    }

    @Test
    public void systemPropertyChangesNothing() {
        System.setProperty("vfs.readTimeout", "PT1S");
        System.setProperty("vfs.smb2.readTimeout", "1000");
        try {
            assertBound(60000, Source.DEFAULT, EffectiveBounds.of("smb2", new FileSystemOptions()).getReadTimeout());
        } finally {
            System.clearProperty("vfs.readTimeout");
            System.clearProperty("vfs.smb2.readTimeout");
        }
    }

    @Test
    public void builderGivesOnlyWhatTheOptionsHold() {
        final FileSystemOptions options = new FileSystemOptions();
        assertNull(BUILDER.configuredReadTimeout(options));
        assertNull(BUILDER.configuredWriteStallTimeout(null));
        BUILDER.setTransactTimeout(options, Duration.ofMillis(30000));
        assertEquals(Duration.ofMillis(30000), BUILDER.configuredTransactTimeout(options));
        assertNull(BUILDER.configuredWriteTimeout(options));
    }

    @Test
    public void liveFileSystemReportsTheBoundsOfItsOptions() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setReadTimeout(options, Duration.ofMillis(20000));
        final Smb2Doubles.Client client = new Smb2Doubles.Client();
        final Smb2FileSystem fileSystem = new Smb2FileSystem(Smb2Doubles.rootName(), options,
                new Smb2Doubles.Factory(client.wrapper));
        assertEquals(EffectiveBounds.of("smb2", options), fileSystem.getEffectiveBounds());
    }

    @Test
    public void textOfTheInitLineNamesEachBoundWithItsSource() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setReadTimeout(options, Duration.ofMillis(20000));
        assertEquals("smb2: connect timeout 5000 from FIXED, socket timeout 0 from FIXED, read timeout 20000 from"
                + " ENDPOINT, write timeout 60000 from DEFAULT, transact timeout 60000 from DEFAULT, write stall"
                + " timeout 60000 from DEFAULT", EffectiveBounds.of("smb2", options).toString());
    }

    private static void assertFixed(final EffectiveBounds bounds) {
        assertBound(0, Source.FIXED, bounds.getSocketTimeout());
        assertBound(5000, Source.FIXED, bounds.getConnectTimeout());
        assertNull(bounds.getSessionTimeout());
        assertNull(bounds.getKeepAliveCount());
        assertNull(bounds.getDataTimeout());
    }

    // The config of a client holds what the bounds give: the three request timeouts and the socket timeout, and in
    // its socket factory the connect timeout and the write stall timeout.
    private static void assertCarries(final EffectiveBounds bounds, final FileSystemOptions options) throws Exception {
        final SmbConfig config = Smb2ClientWrapper.buildConfig(options, new SocketGroup(), () -> { });
        assertEquals(bounds.getReadTimeout().getValue(), config.getReadTimeout());
        assertEquals(bounds.getWriteTimeout().getValue(), config.getWriteTimeout());
        assertEquals(bounds.getTransactTimeout().getValue(), config.getTransactTimeout());
        assertEquals(bounds.getSocketTimeout().getValue(), config.getSoTimeout());
        final WatchedSocketFactory factory = (WatchedSocketFactory) config.getSocketFactory();
        assertEquals(bounds.getConnectTimeout().getValue(), ((Number) field(factory, "connectTimeoutMillis")).longValue());
        assertEquals(bounds.getWriteStallTimeout().getValue(), ((Number) field(factory, "stallTimeoutMillis")).longValue());
    }

    private static Object field(final Object target, final String name) throws Exception {
        final Field field = WatchedSocketFactory.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void assertBound(final long value, final Source source, final Bound bound) {
        assertEquals(value, bound.getValue(), String.valueOf(bound));
        assertEquals(source, bound.getSource(), String.valueOf(bound));
    }
}
