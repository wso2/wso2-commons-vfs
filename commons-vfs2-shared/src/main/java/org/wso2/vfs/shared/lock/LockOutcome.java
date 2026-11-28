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

import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.vfs.shared.error.Classification;

public final class LockOutcome {
    public enum Kind {
        ACQUIRED, BUSY_LOCAL, BUSY_SHARE, LOCK_BUSY, FILE_GONE, ERROR, TRANSPORT_ERROR, FREE, LOCKED,
        OURS, TAKEN_OVER, CHECK_FAILED, RELEASED, NOT_OURS, LEFT, ENDED
    }

    public final Kind kind;
    public final HeldLock handle;
    public final Classification classification;
    public final AtomicResult atomic;

    public boolean connectionLost() {
        return atomic != null && atomic.isConnectionLost();
    }

    LockOutcome as(final Kind kind) {
        return new LockOutcome(kind, handle, classification, atomic);
    }

    LockOutcome(final Kind kind, final HeldLock handle, final Classification classification) {
        this(kind, handle, classification, null);
    }

    LockOutcome(final Kind kind, final HeldLock handle, final Classification classification, final AtomicResult atomic) {
        this.atomic = atomic;
        this.kind = kind;
        this.handle = handle;
        this.classification = classification;
    }

    static LockOutcome of(final Kind kind) {
        return new LockOutcome(kind, null, null);
    }
}
