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

import java.io.IOException;

/**
 * The error of a writer whose socket was closed under it, by the watchdog after a stalled write or by an abort.
 */
public class StalledTransferException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long stallMillis;

    private final long bytesWritten;

    /**
     * Constructs a new instance.
     *
     * @param reason the reason of the abort, as the socket group has it. After an abort of the watchdog it names
     *        the write that the watchdog saw, which can be an earlier one than the call that failed.
     * @param stallMillis how long the call that failed was blocked, in milliseconds.
     * @param bytesWritten the bytes that the stream wrote before that call.
     * @param cause the error of that call.
     */
    public StalledTransferException(final String reason, final long stallMillis, final long bytesWritten,
            final Throwable cause) {
        super(reason + ". The call that failed was blocked for " + stallMillis + " ms after " + bytesWritten
                + " bytes.", cause);
        this.stallMillis = stallMillis;
        this.bytesWritten = bytesWritten;
    }

    /**
     * Gets the time that the call that failed was blocked.
     *
     * @return the time in milliseconds.
     */
    public long getStallMillis() {
        return stallMillis;
    }

    /**
     * Gets the bytes that the stream wrote before the call that failed.
     *
     * @return the byte count.
     */
    public long getBytesWritten() {
        return bytesWritten;
    }
}
