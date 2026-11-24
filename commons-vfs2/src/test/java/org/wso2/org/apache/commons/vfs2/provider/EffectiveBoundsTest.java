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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Bound;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds.Source;
import org.wso2.org.apache.commons.vfs2.provider.sftp.LogCapture;
import org.wso2.org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;

/**
 * The ladder of the SFTP bounds: the endpoint value, then the system property, then the derived value, then the
 * default. One test per step.
 */
public class EffectiveBoundsTest {

    private static final SftpFileSystemConfigBuilder BUILDER = SftpFileSystemConfigBuilder.getInstance();

    @AfterEach
    public void clearProperties() {
        System.clearProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY);
        System.clearProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY);
        System.clearProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY);
    }

    private static EffectiveBounds sftp(final FileSystemOptions options) {
        return EffectiveBounds.of("sftp", options);
    }

    @Test
    public void sessionTimeoutOfTheEndpointWinsOverTheProperty() {
        System.setProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "25000");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(20000));
        assertEquals(new Bound(20000, Source.ENDPOINT), sftp(options).getSessionTimeout());
    }

    @Test
    public void sessionTimeoutFromThePropertyAlone() {
        System.setProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "25000");
        assertEquals(new Bound(25000, Source.SYSTEM_PROPERTY), sftp(new FileSystemOptions()).getSessionTimeout());
    }

    @Test
    public void sessionTimeoutPropertyOfZeroSetsNoTimeout() {
        System.setProperty(EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "0");
        assertEquals(new Bound(0, Source.SYSTEM_PROPERTY), sftp(new FileSystemOptions()).getSessionTimeout());
    }

    @Test
    public void sessionTimeoutDefaultIsThirtySeconds() {
        assertEquals(new Bound(30000, Source.DEFAULT), sftp(new FileSystemOptions()).getSessionTimeout());
        assertEquals(new Bound(30000, Source.DEFAULT), sftp(null).getSessionTimeout());
    }

    @Test
    public void sessionTimeoutOfZeroOnTheEndpointIsKept() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ZERO);
        assertEquals(new Bound(0, Source.ENDPOINT), sftp(options).getSessionTimeout());
    }

    @Test
    public void keepAliveCountOfTheEndpointWinsOverTheProperty() {
        System.setProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "4");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setKeepAliveCountMax(options, 2);
        assertEquals(new Bound(2, Source.ENDPOINT), sftp(options).getKeepAliveCount());
    }

    @Test
    public void keepAliveCountFromThePropertyAlone() {
        System.setProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "4");
        assertEquals(new Bound(4, Source.SYSTEM_PROPERTY), sftp(new FileSystemOptions()).getKeepAliveCount());
    }

    @Test
    public void keepAliveCountIsDerivedAsTheJschDefaultWhenTheEndpointSetsOnlyATimeout() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(150000));
        assertEquals(new Bound(1, Source.DERIVED), sftp(options).getKeepAliveCount());
    }

    @Test
    public void keepAliveCountPropertyComesBeforeTheDerivedValue() {
        System.setProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "4");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(150000));
        assertEquals(new Bound(4, Source.SYSTEM_PROPERTY), sftp(options).getKeepAliveCount());
    }

    @Test
    public void keepAliveCountDefaultIsThree() {
        assertEquals(new Bound(3, Source.DEFAULT), sftp(new FileSystemOptions()).getKeepAliveCount());
    }

    @Test
    public void connectTimeoutOfTheEndpointWinsOverThePropertyAndTheDerivedValue() {
        System.setProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "20000");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(9000));
        BUILDER.setConnectTimeout(options, Duration.ofMillis(15000));
        assertEquals(new Bound(15000, Source.ENDPOINT), sftp(options).getConnectTimeout());
    }

    @Test
    public void connectTimeoutFromThePropertyAlone() {
        System.setProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "20000");
        assertEquals(new Bound(20000, Source.SYSTEM_PROPERTY), sftp(new FileSystemOptions()).getConnectTimeout());
    }

    @Test
    public void connectTimeoutIsDerivedFromTheSessionTimeoutOfTheEndpoint() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(150000));
        assertEquals(new Bound(150000, Source.DERIVED), sftp(options).getConnectTimeout());
    }

    @Test
    public void connectTimeoutPropertyComesBeforeTheDerivedValue() {
        System.setProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "20000");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(150000));
        assertEquals(new Bound(20000, Source.SYSTEM_PROPERTY), sftp(options).getConnectTimeout());
    }

    @Test
    public void connectTimeoutDefaultIsThirtySeconds() {
        assertEquals(new Bound(30000, Source.DEFAULT), sftp(new FileSystemOptions()).getConnectTimeout());
    }

    @Test
    public void connectTimeoutOfZeroOnTheEndpointIsKept() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setConnectTimeout(options, Duration.ZERO);
        assertEquals(new Bound(0, Source.ENDPOINT), sftp(options).getConnectTimeout());
    }

    @Test
    public void optionThatHoldsNoValueCountsAsNotSet() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, null);
        BUILDER.setConnectTimeout(options, null);
        BUILDER.setKeepAliveCountMax(options, null);
        final EffectiveBounds bounds = sftp(options);
        assertEquals(new Bound(30000, Source.DEFAULT), bounds.getSessionTimeout());
        assertEquals(new Bound(3, Source.DEFAULT), bounds.getKeepAliveCount());
        assertEquals(new Bound(30000, Source.DEFAULT), bounds.getConnectTimeout());
    }

    @Test
    public void propertyThatIsNegativeOrNotANumberIsIgnoredWithOneWarnPerValue() {
        final String[][] cases = {{EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "-3001"},
            {EffectiveBounds.SFTP_TIMEOUT_PROPERTY, "abc3001"}, {EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "-3002"},
            {EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "abc3002"},
            {EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "-3003"},
            {EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "abc3003"}};
        try (LogCapture log = new LogCapture(EffectiveBounds.class)) {
            for (final String[] pair : cases) {
                clearProperties();
                System.setProperty(pair[0], pair[1]);
                final int before = log.messages(Level.WARN).size();
                final EffectiveBounds first = sftp(new FileSystemOptions());
                final EffectiveBounds second = sftp(new FileSystemOptions());
                final List<String> warnings = log.messages(Level.WARN);
                assertEquals(before + 1, warnings.size(), "one WARN for " + pair[0] + "=" + pair[1]);
                final String warning = warnings.get(warnings.size() - 1);
                assertTrue(warning.contains(pair[0]) && warning.contains(pair[1]), warning);
                // The ladder goes on to the default.
                assertEquals(new Bound(30000, Source.DEFAULT), first.getSessionTimeout());
                assertEquals(new Bound(3, Source.DEFAULT), first.getKeepAliveCount());
                assertEquals(new Bound(30000, Source.DEFAULT), first.getConnectTimeout());
                assertEquals(first, second);
            }
        }
    }

    @Test
    public void propertyThatIsIgnoredGoesOnToTheDerivedValue() {
        System.setProperty(EffectiveBounds.SFTP_CONNECT_TIMEOUT_PROPERTY, "-3004");
        System.setProperty(EffectiveBounds.SFTP_KEEP_ALIVE_COUNT_PROPERTY, "abc3004");
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(7000));
        final EffectiveBounds bounds = sftp(options);
        assertEquals(new Bound(7000, Source.DERIVED), bounds.getConnectTimeout());
        assertEquals(new Bound(1, Source.DERIVED), bounds.getKeepAliveCount());
    }

    @Test
    public void writeStallTimeoutIsSixtySecondsByDefaultAndTheEndpointValueOtherwise() {
        assertEquals(new Bound(60000, Source.DEFAULT), sftp(new FileSystemOptions()).getWriteStallTimeout());
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setWriteStallTimeout(options, Duration.ofMillis(10000));
        assertEquals(new Bound(10000, Source.ENDPOINT), sftp(options).getWriteStallTimeout());
        BUILDER.setWriteStallTimeout(options, Duration.ZERO);
        assertEquals(new Bound(0, Source.ENDPOINT), sftp(options).getWriteStallTimeout());
    }

    @Test
    public void sameOptionsGiveEqualBounds() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(20000));
        assertEquals(sftp(options), sftp(options));
        assertEquals("sftp", sftp(options).getScheme());
    }

    @Test
    public void schemeWithNoKnownBoundsIsRefused() {
        // The SMB2 provider is in the sandbox module, which this module does not have on its class path.
        assertThrows(IllegalArgumentException.class, () -> EffectiveBounds.of("smb2", new FileSystemOptions()));
        assertThrows(IllegalArgumentException.class, () -> EffectiveBounds.of("ram", new FileSystemOptions()));
    }

    @Test
    public void localPathHasNoBound() {
        final EffectiveBounds local = EffectiveBounds.of("file", new FileSystemOptions());
        assertEquals("file", local.getScheme());
        assertNull(local.getConnectTimeout());
        assertNull(local.getSessionTimeout());
        assertNull(local.getKeepAliveCount());
        assertNull(local.getSocketTimeout());
        assertNull(local.getDataTimeout());
        assertNull(local.getReadTimeout());
        assertNull(local.getWriteTimeout());
        assertNull(local.getTransactTimeout());
        assertNull(local.getWriteStallTimeout());
        assertEquals(local, EffectiveBounds.of("file", null));
        assertEquals("file: the fork enforces no bound. A mount takes its bound from the mount options",
                local.toString());
    }

    @Test
    public void textOfTheInitLineNamesEachBoundWithItsSource() {
        final FileSystemOptions options = new FileSystemOptions();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(20000));
        assertEquals("sftp: connect timeout 20000 from DERIVED, session timeout 20000 from ENDPOINT, keep alive count 1"
                + " from DERIVED, write stall timeout 60000 from DEFAULT", sftp(options).toString());
        assertEquals("ftp: connect timeout 30000 from DEFAULT, socket timeout 60000 from DEFAULT, data timeout 60000"
                + " from DEFAULT, write stall timeout 60000 from DEFAULT",
                EffectiveBounds.of("ftp", new FileSystemOptions()).toString());
    }
}
