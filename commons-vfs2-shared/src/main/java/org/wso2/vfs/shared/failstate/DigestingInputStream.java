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
package org.wso2.vfs.shared.failstate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.LongConsumer;

/** Hash bytes supplied to the caller. Only EOF at the expected size completes the identity. */
public final class DigestingInputStream extends FilterInputStream {
    private final MessageDigest hash;
    private final FileIdentity identity;
    private final LongConsumer progress;
    private long count;
    private boolean finished;

    public DigestingInputStream(InputStream input, FileIdentity identity, LongConsumer progress) {
        super(input);
        this.identity = identity;
        this.progress = progress;
        identity.digest(null);
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void eof() {
        if (!finished && identity.size == count) {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash.digest()) {
                hex.append(Character.forDigit((value & 255) >>> 4, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            identity.digest(hex.toString());
        }
        finished = true;
    }

    private void advanced(int bytes) {
        count += bytes;
        if (progress != null) {
            progress.accept(bytes);
        }
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value < 0) {
            eof();
        } else {
            hash.update((byte) value);
            advanced(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int bytes = in.read(buffer, offset, length);
        if (bytes < 0) {
            eof();
        } else if (bytes > 0) {
            hash.update(buffer, offset, bytes);
            advanced(bytes);
        }
        return bytes;
    }

    @Override
    public long skip(long requested) throws IOException {
        long skipped = in.skip(requested);
        if (skipped > 0) {
            finished = true;
            identity.digest(null);
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int limit) {
        // This stream cannot rewind the digest.
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("The digest stream does not support reset.");
    }

    public long count() {
        return count;
    }
}
