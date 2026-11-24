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
package org.wso2.vfs.shared.write;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

public final class TempNames {
    private static final int WIDTH = 7;
    private static final long SPACE = 78364164096L; // 36 to the power of 7.
    // 1 January 2026, before the first release of this code. A name whose creation second is older is not ours, so a
    // foreign file whose characters read as an old time is never taken for a temp file.
    static final long FLOOR_SECONDS = 1767225600L;
    private static final ConcurrentMap<String, TempName> ACTIVE = new ConcurrentHashMap<>();

    private TempNames() {
    }

    public static TempName begin(final String targetName, final String suffix) {
        if (targetName.isEmpty() || !suffix.startsWith(".")) {
            throw new IllegalArgumentException("A target name and a suffix with a dot are required.");
        }
        final long created = Instant.now().getEpochSecond();
        final String prefix = targetName + "." + digits(created);
        TempName attempt;
        do {
            final String name = prefix + digits(ThreadLocalRandom.current().nextLong(SPACE)) + suffix;
            attempt = new TempName(name, targetName, created);
        } while (ACTIVE.putIfAbsent(attempt.name(), attempt) != null);
        return attempt;
    }

    public static TempName parse(final String name, final String suffix) {
        final int end = name.length() - suffix.length();
        final int start = end - 2 * WIDTH;
        if (start < 2 || !suffix.startsWith(".") || !name.endsWith(suffix) || name.charAt(start - 1) != '.') {
            return null;
        }
        for (int index = start; index < end; index++) {
            final char digit = name.charAt(index);
            if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'z')) {
                return null;
            }
        }
        final long created = Long.parseLong(name.substring(start, start + WIDTH), 36);
        return created < FLOOR_SECONDS ? null : new TempName(name, name.substring(0, start - 1), created);
    }

    public static boolean inUse(final String name) {
        return ACTIVE.containsKey(name);
    }

    static void end(final TempName attempt) {
        ACTIVE.remove(attempt.name(), attempt);
    }

    private static String digits(final long value) {
        final String digits = Long.toString(value, 36);
        return "0000000".substring(digits.length()) + digits;
    }
}
