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
package org.wso2.vfs.shared.log;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.wso2.org.apache.commons.vfs2.util.TestPoints;

public final class RepeatLimiter {
    private static final long INTERVAL = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_TEXTS = 200;

    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final Set<String> missing = new HashSet<>();

    synchronized String admit(final String text) {
        final long now = TestPoints.nanoTime("repeat");
        final Window window = windows.get(text);
        if (window != null) {
            if (now - window.start < INTERVAL) {
                window.skipped++;
                return null;
            }
            windows.remove(text);
        } else if (windows.size() == MAX_TEXTS) {
            final Iterator<Window> oldest = windows.values().iterator();
            oldest.next();
            oldest.remove();
        }
        windows.put(text, new Window(now));
        return window == null ? text : text + " (skipped " + window.skipped + ")";
    }

    public synchronized boolean once(final String key) {
        return missing.add(key);
    }

    public synchronized void clear(final String key) {
        missing.remove(key);
    }

    private static final class Window {
        private final long start;
        private long skipped;

        private Window(final long start) {
            this.start = start;
        }
    }
}
