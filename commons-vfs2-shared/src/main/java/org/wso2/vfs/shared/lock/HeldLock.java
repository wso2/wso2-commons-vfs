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

import static org.wso2.vfs.shared.lock.LockOutcome.Kind.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.ErrorKind;

/** Ownership can pass between threads. Only the winner of the end may clear the entry. */
public final class HeldLock {
    private final ShareLock owner;
    final PathRegistry.Entry entry;
    final String uri;
    final String lockUri;
    public final FolderListing listing;
    private final AtomicBoolean ended = new AtomicBoolean();
    volatile boolean onShare;
    boolean takenOver;

    HeldLock(final ShareLock owner, final PathRegistry.Entry entry, final String uri, final FolderListing listing) {
        this.owner = owner;
        this.entry = entry;
        this.uri = PathRegistry.dataUri(uri);
        this.lockUri = LockIo.suffix(this.uri, ".lock");
        this.listing = listing;
        if (TestPoints.enabled()) {
            entry.markedHandle = this;
        }
    }

    public boolean tookOver() {
        return takenOver;
    }

    public void pendingStateWrite() {
        entry.pending = true;
    }

    String snapshotLine() {
        return handleId() + " path=" + LockIo.pointKey(uri) + " state=" + (ended.get() ? "ended" : "held")
                + " owner=" + (entry.pending ? "PENDING_STATE_WRITE" : "THREAD");
    }

    private String handleId() {
        return "handle=" + Integer.toUnsignedString(System.identityHashCode(this));
    }

    private void clear(final String way) {
        try {
            if (TestPoints.enabled()) {
                TestPoints.event("lock.handle-end", owner.endpoint, owner.ownerField(), handleId(), "way=" + way);
            }
        } finally {
            entry.clear();
        }
    }

    public LockOutcome abandon() {
        if (!ended.compareAndSet(false, true)) {
            return LockOutcome.of(ENDED);
        }
        clear("abandon");
        return LockOutcome.of(LEFT);
    }

    public LockOutcome lockShare(final LockIo io, final int tries, final long gap) {
        return lockShare(io, tries, gap, Long.MAX_VALUE);
    }

    /** Take the immediate share attempt even when an earlier lock used all the available wait. */
    public LockOutcome lockShare(final LockIo io, final int tries, final long gap, final long remainingWaitMillis) {
        long remaining = remainingWaitMillis;
        if (ended.get()) {
            return LockOutcome.of(ENDED);
        }
        if (onShare) {
            return new LockOutcome(ACQUIRED, this, null);
        }
        entry.renew();
        for (int attempt = 0; attempt < tries; attempt++) {
            if (ended.get()) {
                return LockOutcome.of(ENDED);
            }
            final LockOutcome result = owner.publish(io, this, ShareLock.Mode.WRITER);
            if (result.kind == BUSY_SHARE && result.connectionLost()) {
                return result.as(LOCK_BUSY);
            }
            if (result.kind != BUSY_SHARE) {
                return result;
            }
            if (attempt + 1 < tries) {
                try {
                    if (gap > 0 && remaining <= 0) {
                        break;
                    }
                    final long delay = Math.min(gap, remaining);
                    final long start = System.nanoTime();
                    owner.pause(delay);
                    final long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    remaining = Math.max(0, remaining - Math.max(delay, waited));
                } catch (final ShareLock.Failure error) {
                    return error.outcome(ERROR);
                }
            }
        }
        return LockOutcome.of(LOCK_BUSY);
    }

    private LockOutcome check(final LockIo io) {
        if (!onShare) {
            return LockOutcome.of(OURS);
        }
        try {
            return LockOutcome.of(owner.read(io, lockUri).same(entry.value) ? OURS : TAKEN_OVER);
        } catch (final ShareLock.Failure error) {
            // A read that failed with no transport error gets a fresh look. SFTP and SMB2 answer the read of a lock
            // that is gone with an error that does not say "missing", so only the look tells that the lock was
            // taken over. A lock that is there and can not be read stays a failed check.
            if (!error.cause.getKind().isTransport()) {
                try {
                    if (!owner.exists(io, lockUri)) {
                        return LockOutcome.of(TAKEN_OVER);
                    }
                } catch (final ShareLock.Failure look) {
                    return new LockOutcome(CHECK_FAILED, null, look.cause);
                }
            }
            return new LockOutcome(CHECK_FAILED, null, error.cause);
        }
    }

    public LockOutcome verifyOwner(final LockIo io) {
        LockOutcome result = ended.get() ? LockOutcome.of(ENDED) : check(io);
        if (result.kind == TAKEN_OVER) {
            if (ended.compareAndSet(false, true)) {
                try {
                    owner.lost(this);
                } finally {
                    clear("takenOver");
                }
            } else {
                result = LockOutcome.of(ENDED);
            }
        } else if (ended.get()) {
            result = LockOutcome.of(ENDED);
        }
        if (TestPoints.enabled()) {
            TestPoints.event("lock.verify-owner", owner.endpoint, owner.ownerField(), handleId(), "answer=" + result.kind);
        }
        return result;
    }

    private LockOutcome checkedDelete(final LockIo io) {
        if (!onShare) {
            return LockOutcome.of(RELEASED);
        }
        try {
            final LockValue found;
            try {
                found = owner.read(io, lockUri);
            } catch (final ShareLock.Failure read) {
                if (read.cause.getKind() == ErrorKind.MISSING && !owner.exists(io, lockUri)) {
                    return LockOutcome.of(RELEASED);
                }
                throw read;
            }
            if (!found.same(entry.value)) {
                owner.log.warn("A foreign lock was left in place at " + entry.key);
                return LockOutcome.of(NOT_OURS);
            }
            owner.delete(io, lockUri);
            return LockOutcome.of(RELEASED);
        } catch (final ShareLock.Failure error) {
            owner.log.warn("The lock was left at " + entry.key);
            return new LockOutcome(LEFT, null, error.cause);
        }
    }

    public LockOutcome unlockShare(final LockIo io) {
        if (ended.get()) {
            return LockOutcome.of(ENDED);
        }
        owner.checkHeldAges();
        try {
            return checkedDelete(io);
        } finally {
            onShare = false;
        }
    }

    public LockOutcome releaseChecked(final LockIo io) {
        if (!ended.compareAndSet(false, true)) {
            return LockOutcome.of(ENDED);
        }
        try {
            owner.checkHeldAges();
            return checkedDelete(io);
        } finally {
            clear("releaseChecked");
        }
    }

    public LockOutcome releaseAfterAction(final LockIo io, final Supplier<LockIo> fresh) {
        if (!ended.compareAndSet(false, true)) {
            return LockOutcome.of(ENDED);
        }
        try {
            owner.checkHeldAges();
            if (onShare) {
                try {
                    owner.delete(io, lockUri);
                } catch (final ShareLock.Failure error) {
                    if (error.cause.getKind().isTransport() && error.cause.isFast() && fresh != null) {
                        final LockIo next = fresh.get();
                        if (next != null) {
                            return checkedDelete(next);
                        }
                    }
                    owner.log.warn("The lock was left at " + entry.key);
                    return new LockOutcome(LEFT, null, error.cause);
                }
            }
            return LockOutcome.of(RELEASED);
        } finally {
            clear("releaseAfterAction");
        }
    }
}
