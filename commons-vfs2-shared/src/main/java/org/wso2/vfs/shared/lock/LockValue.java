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
package org.wso2.vfs.shared.lock;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

import org.wso2.org.apache.commons.vfs2.FileObject;

/** Bytes observed at a lock name, with its time and instance when they can be parsed. */
final class LockValue {
    static final int LIMIT = 1024;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    final String host;
    final String address;
    final byte[] bytes;
    final long time;
    final String instance;

    private LockValue(final byte[] bytes, final String host, final String address, final long time,
            final String instance) {
        this.host = host;
        this.address = address;
        this.bytes = bytes;
        this.time = time;
        this.instance = instance;
    }

    static LockValue create(final long time, final String instance) {
        return create(time, instance, InetAddress::getLocalHost);
    }

    interface HostLookup {
        InetAddress get() throws UnknownHostException;
    }

    static LockValue create(final long time, final String instance, final HostLookup lookup) {
        String host = "unknown";
        String address = "0.0.0.0";
        try {
            final InetAddress local = lookup.get();
            host = local.getHostName();
            address = local.getHostAddress();
        } catch (final UnknownHostException unavailable) {
            // The placeholders keep both fields in the value.
        }
        final String text = ThreadLocalRandom.current().nextLong() + ":" + host + ":" + address + ":" + time
                + ":v2:" + instance;
        return new LockValue(text.getBytes(StandardCharsets.UTF_8), host, address, time, instance);
    }

    static LockValue read(final FileObject resolved) throws IOException {
        try (FileObject file = resolved; InputStream input = file.getContent().getInputStream()) {
            return read(input);
        }
    }

    static LockValue read(final InputStream input) throws IOException {
        final byte[] buffer = new byte[LIMIT + 1];
        int size = 0;
        while (size < buffer.length) {
            final int count = input.read(buffer, size, buffer.length - size);
            if (count < 0) {
                break;
            }
            size += count;
        }
        return parse(Arrays.copyOf(buffer, size));
    }

    static LockValue parse(final byte[] bytes) {
        if (bytes.length <= LIMIT) {
            final String[] fields = new String(bytes, StandardCharsets.UTF_8).split(":", -1);
            final int last = fields.length - 1;
            if (last >= 1) {
                final boolean modern = fields[last - 1].equals("v2");
                try {
                    Long.parseLong(fields[0]);
                    if (modern ? last >= 5 && UUID_TEXT.matcher(fields[last]).matches() : !fields[last - 1].matches("v[0-9]+")) {
                        final long time = Long.parseLong(fields[modern ? last - 2 : last]);
                        if (time > 0) {
                            final int timeIndex = modern ? last - 2 : last;
                            final String host = timeIndex > 1 ? fields[1] : null;
                            final String address = timeIndex > 2
                                    ? String.join(":", Arrays.copyOfRange(fields, 2, timeIndex)) : null;
                            return new LockValue(bytes, host, address, time, modern ? fields[last] : null);
                        }
                    }
                } catch (final NumberFormatException foreign) {
                    // A partial or foreign lock ages from its first sight.
                }
            }
        }
        return new LockValue(bytes, null, null, 0, null);
    }

    boolean same(final LockValue other) {
        return Arrays.equals(bytes, other.bytes);
    }

    String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
