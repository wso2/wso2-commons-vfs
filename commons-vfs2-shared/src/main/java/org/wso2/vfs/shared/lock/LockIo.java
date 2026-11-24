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

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemManager;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;

/** Resolves each name on the caller's connection. */
public class LockIo {
    private final FileSystemManager manager;
    private final FileSystemOptions options;

    public LockIo(final FileSystemManager manager, final FileSystemOptions options) {
        this.manager = manager;
        this.options = options;
    }

    public FileObject resolve(final String uri) throws FileSystemException {
        return manager.resolveFile(uri, options);
    }

    static String suffix(final String uri, final String suffix) {
        final int query = uri.indexOf('?');
        return query < 0 ? uri + suffix : uri.substring(0, query) + suffix + uri.substring(query);
    }

    static String token() {
        return PathRegistry.INSTANCE.substring(0, 8) + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    boolean exists(final String uri) throws IOException {
        try (FileObject file = resolve(uri)) {
            return AtomicOps.existsNow(file);
        }
    }

    LockValue read(final String uri) throws IOException {
        return LockValue.read(resolve(uri));
    }

    void write(final String uri, final byte[] bytes) throws IOException {
        try (FileObject file = resolve(uri); OutputStream output = file.getContent().getOutputStream(false)) {
            output.write(bytes);
        }
    }

    void delete(final String uri) throws IOException {
        try (FileObject file = resolve(uri)) {
            if (!file.delete() && AtomicOps.existsNow(file)) {
                throw new IOException("The lock could not be removed.");
            }
        }
    }

    Answer exclusive(final String lock) throws IOException {
        try (FileObject file = resolve(lock)) {
            final AtomicOps ops = AtomicOps.of(file);
            return ops == null ? Answer.NO : ops.atomicCapabilities().getExclusiveCreate();
        }
    }

    AtomicResult create(final String data, final LockValue value, final Runnable beforeLink) throws IOException {
        try (FileObject file = resolve(suffix(data, ".lock"))) {
            final AtomicOps ops = AtomicOps.of(file);
            if (ops == null) {
                return new AtomicResult(AtomicOutcome.UNSUPPORTED, null);
            }
            final String base = file.getName().getBaseName();
            final String unique = base.substring(0, base.length() - 5) + "." + token();
            return beforeLink != null
                    ? ops.createExclusive(value.bytes, unique + ".u.lock", unique + ".p.lock", beforeLink)
                    : ops.createExclusive(value.bytes, unique + ".u.lock", unique + ".p.lock");
        }
    }

    public static String pointKey(final String uri) {
        final String key = PathRegistry.key(uri);
        return key.startsWith("/") ? "file://" + key : key;
    }

    AtomicOutcome take(final String from, final String to) throws IOException {
        try (FileObject source = resolve(from); FileObject target = resolve(to)) {
            final AtomicOps ops = AtomicOps.of(source);
            return ops == null ? AtomicOutcome.UNSUPPORTED : outcome(ops.takeAway(target));
        }
    }

    private static AtomicOutcome outcome(final AtomicResult result) throws FileSystemException {
        if (result.isConnectionLost()) {
            final FileSystemException failure = new FileSystemException("Connection lost after " + result.getOutcome());
            failure.setFault(Fault.TRANSPORT);
            throw failure;
        }
        return result.getOutcome();
    }
}
