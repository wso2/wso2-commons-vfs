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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

/** Compares the host names already known to the provider without resolving host names or interface names. */
final class NumericHost {
    private NumericHost() {
    }

    static boolean same(final String first, final String second) {
        if (first.equals(second) || (first.indexOf(':') < 0 && first.equalsIgnoreCase(second))) {
            return true;
        }
        final String value = numeric(first);
        return value != null && value.equals(numeric(second));
    }

    private static String numeric(final String host) {
        try {
            if (host.indexOf(':') >= 0) {
                String literal = host;
                if (host.startsWith("[") && host.endsWith("]")) {
                    literal = host.substring(1, host.length() - 1);
                }
                final int zone = literal.indexOf('%');
                final String address = zone < 0 ? literal : literal.substring(0, zone);
                // A bracketed IPv6 literal is parsed or rejected locally by the JDK. Keep its zone separate.
                final String parsed = InetAddress.getByName("[" + address + "]").getHostAddress();
                return zone < 0 ? parsed : parsed + literal.substring(zone);
            }
            if (host.isEmpty() || host.length() > 15 || !host.matches("[0-9.]+")) {
                return null;
            }
            final String[] parts = host.split("[.]", -1);
            if (parts.length > 4) {
                return null;
            }
            long value = 0;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    return null;
                }
                final long number = Long.parseLong(parts[i]);
                final boolean last = i == parts.length - 1;
                // In a compact IPv4 literal the last part fills every remaining byte.
                final int bits = last ? 8 * (4 - i) : 8;
                if (number >= (1L << bits)) {
                    return null;
                }
                value |= last ? number : number << (8 * (3 - i));
            }
            return InetAddress.getByAddress(ByteBuffer.allocate(4).putInt((int) value).array()).getHostAddress();
        } catch (final UnknownHostException invalidLiteral) {
            return null;
        }
    }
}
