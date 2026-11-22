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
package org.wso2.org.apache.commons.vfs2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class MonitorInputStreamTest {
    @Test
    public void bothReadMethodsCloseOnceAndKeepTheirCount() throws IOException {
        for (final boolean bulk : new boolean[] { false, true }) {
            final AtomicInteger closes = new AtomicInteger();
            final MonitorInputStream stream = new MonitorInputStream(new ByteArrayInputStream(new byte[] { 1 })) {
                @Override
                protected void onClose() {
                    closes.incrementAndGet();
                }
            };
            final byte[] buffer = new byte[8];
            assertEquals(0, stream.read(buffer, 0, 0));
            assertEquals(1, bulk ? stream.read(buffer) : stream.read());
            assertEquals(0, closes.get());
            assertEquals(-1, bulk ? stream.read(buffer) : stream.read());
            assertEquals(1, closes.get());
            assertEquals(1, stream.getCount());
            assertEquals(0, stream.available());
            assertEquals(-1, stream.read());
            stream.close();
            assertEquals(1, closes.get());
        }
    }

    @Test
    public void endOfStreamReportsTheCloseFailureOnce() throws IOException {
        final IOException failure = new IOException("close failed");
        final MonitorInputStream stream = new MonitorInputStream(new ByteArrayInputStream(new byte[0])) {
            @Override
            protected void onClose() throws IOException {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IOException.class, stream::read));
        assertEquals(-1, stream.read());
        stream.close();
    }
}
