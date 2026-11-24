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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The setup connect of a test with short bounds. On a loaded machine the handshake of that connect can pass such a
 * bound before the test injects its fault. Only that setup is tried again, at most 3 times, and only when it ended with
 * a read timeout. Each retry prints one line, so a run shows how often it was needed. The bounds and the assertions of
 * the test stay as they are.
 */
final class SetupConnect {

    static final int RETRIES = 3;

    /**
     * A setup step that connects.
     *
     * @param <T> what the step gives back.
     */
    interface Step<T> {
        T run() throws Exception;
    }

    private SetupConnect() {
    }

    static <T> T withRetry(final String test, final Step<T> step) throws Exception {
        for (int retry = 0;; retry++) {
            try {
                return step.run();
            } catch (final IOException e) {
                final SocketTimeoutException timeout = timeoutIn(e);
                if (timeout == null || retry == RETRIES) {
                    throw e;
                }
                System.out.println("Setup connect of " + test + " timed out before the fault, retry " + (retry + 1)
                        + " of " + RETRIES + ": " + timeout.getMessage());
            }
        }
    }

    /**
     * Closes a stream of the setup whose first read failed, so that a retry starts with no open channel.
     *
     * @param in the stream.
     * @param error the error of the read.
     * @return the error, with a failed close added to it.
     */
    static IOException closed(final InputStream in, final IOException error) {
        try {
            in.close();
        } catch (final IOException e) {
            error.addSuppressed(e);
        }
        return error;
    }

    private static SocketTimeoutException timeoutIn(final Throwable error) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = error; link != null && seen.add(link); link = link.getCause()) {
            if (link instanceof SocketTimeoutException) {
                return (SocketTimeoutException) link;
            }
        }
        return null;
    }
}
