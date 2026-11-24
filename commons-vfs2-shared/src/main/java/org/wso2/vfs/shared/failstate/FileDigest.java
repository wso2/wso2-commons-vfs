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
package org.wso2.vfs.shared.failstate;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.lock.LockIo;

public final class FileDigest {
    private FileDigest() {
    }

    static StatNow stat(LockIo io, String uri) throws IOException {
        try (FileObject file = io.resolve(uri)) {
            AtomicOps ops = AtomicOps.of(file);
            if (ops == null) {
                // A provider with no atomic calls gets the plain look of the FileObject interface, as existsNow does.
                file.refresh();
                if (!file.exists()) {
                    return StatNow.notThere();
                }
                return StatNow.there(file.getContent().getSize(), file.getContent().getLastModifiedTime(), false);
            }
            return ops.statNow();
        }
    }

    static boolean exists(LockIo io, String uri) throws IOException {
        try (FileObject file = io.resolve(uri)) {
            return AtomicOps.existsNow(file);
        }
    }

    static Classification classify(ErrorClassifier classifier, Throwable failure, long start,
            ErrorClassifier.CallType call) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Classification primary = classifier.classify(failure, elapsed, call);
        if (primary.getKind().isTransport()) {
            return primary;
        }
        // A read can fail before resource close reports that the connection has been lost.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> remaining = new ArrayDeque<>();
        remaining.add(failure);
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            for (Throwable close : current.getSuppressed()) {
                Fault fault = Faults.match(close);
                if (fault == Fault.TRANSPORT) {
                    return classifier.classify(close, elapsed, call);
                }
                remaining.addLast(close);
            }
            if (current.getCause() != null) {
                remaining.addLast(current.getCause());
            }
        }
        return primary;
    }

    private static DigestResult failed(Classification failure) {
        return new DigestResult(failure.getKind().isTransport() ? DigestResult.Kind.TRANSPORT_ERROR
                : DigestResult.Kind.ERROR, null, 0, failure);
    }

    public static DigestResult of(LockIo io, String uri) {
        return of(io, uri, new ErrorClassifier(org.wso2.vfs.shared.log.NamedLog.inbound(
                org.apache.commons.logging.LogFactory.getLog(FileDigest.class), "digest",
                new org.wso2.vfs.shared.log.RepeatLimiter())));
    }

    static DigestResult of(LockIo io, String uri, ErrorClassifier classifier) {
        long start = System.nanoTime();
        try {
            StatNow before = stat(io, uri);
            if (!before.isThere()) {
                return new DigestResult(DigestResult.Kind.GONE, null, 0, null);
            }
            FileIdentity id = FileIdentity.from(uri, before);
            long count;
            try (FileObject file = io.resolve(uri);
                    DigestingInputStream input = new DigestingInputStream(file.getContent().getInputStream(), id, null)) {
                byte[] buffer = new byte[16384];
                while (input.read(buffer) >= 0) {
                    // Drain the stream to EOF.
                }
                count = input.count();
            }
            StatNow after = stat(io, uri);
            if (!after.isThere()) {
                return new DigestResult(DigestResult.Kind.GONE, null, 0, null);
            }
            if (id.digest() == null || id.changed(FileIdentity.from(uri, after))) {
                throw new IOException("The file changed during the digest read.");
            }
            return new DigestResult(DigestResult.Kind.DONE, id.digest(), count, null);
        } catch (IOException ex) {
            Classification failure = classify(classifier, ex, start, ErrorClassifier.CallType.READ);
            if (failure.getKind() == ErrorKind.MISSING) {
                long look = System.nanoTime();
                try {
                    if (!exists(io, uri)) {
                        return new DigestResult(DigestResult.Kind.GONE, null, 0, null);
                    }
                } catch (IOException ex2) {
                    return failed(classify(classifier, ex2, look, ErrorClassifier.CallType.READ));
                }
            }
            return failed(failure);
        }
    }
}
