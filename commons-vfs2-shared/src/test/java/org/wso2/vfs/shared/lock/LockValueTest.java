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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LockValueTest {
    private static final String INSTANCE = "44903fc1-aa01-425a-93a9-efabcdef0123";

    private LockValue parse(final String text) {
        return LockValue.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTimesFromEitherFormatAndBothEndsOfAnAddress() {
        for (String address : Arrays.asList("10.0.0.9", "fe80::1:2:3", "2001:db8::7", "0.0.0.0")) {
            String legacy = "-23:host:" + address + ":34567";
            assertEquals(34567, parse(legacy).time);
            assertNull(parse(legacy).instance);
            assertEquals("host", parse(legacy).host);
            assertEquals(address, parse(legacy).address);
            assertEquals("host", parse(legacy + ":v2:" + INSTANCE).host);
            assertEquals(address, parse(legacy + ":v2:" + INSTANCE).address);
            assertEquals(34567, parse(legacy + ":v2:" + INSTANCE).time);
            assertEquals(INSTANCE, parse(legacy + ":v2:" + INSTANCE).instance);
        }
        assertEquals(9876, parse("123:9876").time);
        for (String bad : Arrays.asList("", "123:0", "garbage", "host\naddr\npid", "1:h:a:123:v3:4", "1:4:v2:9")) {
            assertEquals(0, parse(bad).time);
        }
    }

    @Test
    void cutInstanceAgesAsForeignAndFailedLookupWritesBothPlaceholders() {
        LockValue made = LockValue.create(1, INSTANCE, () -> { throw new java.net.UnknownHostException(); });
        assertEquals(6, made.text().split(":", -1).length);
        assertEquals("unknown", parse(made.text()).host);
        assertEquals("0.0.0.0", parse(made.text()).address);
        for (long time : new long[]{1, System.currentTimeMillis()}) {
            String prefix = "42:h:127.0.0.1:" + time + ":v2:";
            assertEquals(time, parse(prefix + INSTANCE).time);
            assertEquals(0, parse(prefix).time);
            assertEquals(0, parse(prefix + INSTANCE.substring(0, INSTANCE.length() - 1)).time);
        }
    }

    @Test
    void readsShortChunksAndStopsAtTheSizeLimit() throws Exception {
        final LockValue made = LockValue.create(1234567, INSTANCE);
        final ByteArrayInputStream input = new ByteArrayInputStream(made.bytes) {
            @Override
            public int read(byte[] bytes, int start, int size) {
                return super.read(bytes, start, Math.min(1, size));
            }
        };
        assertTrue(made.same(LockValue.read(input)));
        final ByteArrayInputStream large = new ByteArrayInputStream(new byte[4096]);
        assertEquals(1025, LockValue.read(large).bytes.length);
        assertEquals(4096 - 1025, large.available());
    }
}
