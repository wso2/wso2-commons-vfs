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

import java.net.URI;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class PathRegistry {
    static final String INSTANCE = UUID.randomUUID().toString();
    private static final ConcurrentMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Entry> VALUES = new ConcurrentHashMap<>();

    private PathRegistry() {
    }

    // VFS may leave URI metacharacters literal in its path. Preserve existing percent escapes.
    static String pathUri(final String path) {
        try {
            return new URI(null, null, "/x" + path, null).getRawPath().substring(2)
                    .replaceAll("%25([0-9a-fA-F]{2})", "%$1");
        } catch (final java.net.URISyntaxException ex) {
            throw new IllegalArgumentException("Invalid VFS path", ex);
        }
    }

    static String dataUri(final String input) {
        final int scheme = input.indexOf("://");
        final int separator = input.indexOf('?');
        final int limit = separator < 0 ? input.length() : separator;
        final int first = scheme < 0 ? 0 : input.indexOf('/', scheme + 3);
        final String uri = first < 0 || first >= limit ? input : input.substring(0, first)
                + pathUri(input.substring(first, limit)) + input.substring(limit);
        final URI name = URI.create(withoutUserInfo(uri));
        final String path = name.getRawPath();
        if (path == null || path.length() <= 1 || !path.endsWith("/")) {
            return uri;
        }
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        if ("smb2".equalsIgnoreCase(name.getScheme()) && path.substring(1, end).indexOf('/') < 0) {
            return uri;
        }
        final String raw = name.getRawQuery();
        final int query = raw == null ? uri.length() : uri.indexOf('?');
        return uri.substring(0, query - (path.length() - end)) + uri.substring(query);
    }

    // VFS takes the user info up to the first @ before a slash or a question mark, as typed, so it may hold a raw #
    // or a character that a URI does not allow. The key holds no user info, so it is left out before the parse.
    private static String withoutUserInfo(final String uri) {
        final int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return uri;
        }
        final int start = scheme + 3;
        for (int pos = start; pos < uri.length(); pos++) {
            final char ch = uri.charAt(pos);
            if (ch == '@') {
                return uri.substring(0, start) + uri.substring(pos + 1);
            }
            if (ch == '/' || ch == '?') {
                break;
            }
        }
        return uri;
    }

    static String key(final String uri) {
        final URI name = URI.create(withoutUserInfo(dataUri(uri)));
        if (name.getScheme() == null) {
            return Paths.get(uri).toAbsolutePath().normalize().toString();
        }
        if ("file".equalsIgnoreCase(name.getScheme()) && name.getRawAuthority() == null) {
            return Paths.get(name.getPath()).toAbsolutePath().normalize().toString();
        }
        final String authority = name.getRawAuthority();
        final String server = authority == null ? "" : authority.substring(authority.lastIndexOf('@') + 1);
        return name.getScheme().toLowerCase(Locale.ROOT) + "://" + server.toLowerCase(Locale.ROOT) + escapedKeyPath(name.getRawPath());
    }

    private static String escapedKeyPath(final String path) {
        int escape = path.indexOf('%');
        if (escape < 0) {
            return path;
        }
        final char[] chars = path.toCharArray();
        // The URI parser has validated these escapes. Keep the path and its separators as received.
        while (escape >= 0) {
            chars[escape + 1] = Character.toUpperCase(chars[escape + 1]);
            chars[escape + 2] = Character.toUpperCase(chars[escape + 2]);
            escape = path.indexOf('%', escape + 3);
        }
        return new String(chars);
    }

    static Entry enter(final ShareLock owner, final String key) {
        final Entry entry = new Entry(owner, key);
        if (ENTRIES.putIfAbsent(key, entry) != null) {
            return null;
        }
        VALUES.put(entry.value.text(), entry);
        return entry;
    }

    static boolean held(final LockValue value) {
        return value.instance != null && VALUES.containsKey(value.text());
    }

    static Collection<Entry> entries() {
        return ENTRIES.values();
    }

    static void await(final String key, final long millis) throws InterruptedException {
        final Entry current = ENTRIES.get(key);
        if (current != null) {
            current.released.await(millis, TimeUnit.MILLISECONDS);
        }
    }

    static final class Entry {
        final String key;
        final ShareLock owner;
        final long started = System.nanoTime();
        final AtomicBoolean warned = new AtomicBoolean();
        final CountDownLatch released = new CountDownLatch(1);
        volatile LockValue value = LockValue.create(System.currentTimeMillis(), INSTANCE);
        volatile boolean pending;
        volatile HeldLock markedHandle;

        private Entry(final ShareLock owner, final String key) {
            this.key = key;
            this.owner = owner;
        }

        void renew() {
            final LockValue previous = value;
            final LockValue fresh = LockValue.create(System.currentTimeMillis(), INSTANCE);
            VALUES.put(fresh.text(), this);
            value = fresh;
            VALUES.remove(previous.text(), this);
            if (ENTRIES.get(key) != this) {
                VALUES.remove(fresh.text(), this);
            }
        }

        void clear() {
            ENTRIES.remove(key, this);
            VALUES.remove(value.text(), this);
            released.countDown();
        }
    }
}
