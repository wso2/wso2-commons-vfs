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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.TreeMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorClassifier.CallType;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.log.NamedLog;

/** Share lock parameters and foreign lock observations for one endpoint or connection. */
public final class ShareLock {
    public enum Mode { LISTENER, WRITER }

    final String endpoint;
    private volatile String lastOrphan;
    private final boolean autoRelease;
    private final long staleMillis;
    final NamedLog log;
    private final EndpointHealth health;
    private final ErrorClassifier classifier;
    private final LongSupplier wallMillis;
    private final LongSupplier nanos;
    private final Map<String, Sight> sights = new LinkedHashMap<>();
    private final AtomicBoolean capabilityLogged = new AtomicBoolean();

    private static final class Sight {
        final byte[] digest;
        final long start;

        Sight(final byte[] digest, final long start) {
            this.digest = digest;
            this.start = start;
        }
    }

    ShareLock(final String endpoint, final boolean autoRelease, final long staleMillis,
            final EndpointHealth health, final NamedLog log, final LongSupplier wallMillis, final LongSupplier nanos) {
        this.wallMillis = wallMillis;
        this.nanos = nanos;
        this.endpoint = endpoint;
        this.autoRelease = autoRelease;
        this.log = log;
        this.health = health;
        this.classifier = new ErrorClassifier(log);
        if (staleMillis < -1) {
            throw new IllegalArgumentException("Invalid stale age.");
        }
        this.staleMillis = staleMillis == -1 ? -1 : Math.max(60000, staleMillis);
        if (TestPoints.enabled()) {
            health.registerFailStateSnapshot(() -> registry(health));
        }
        if (this.staleMillis != staleMillis) {
            log.warn("The lock stale age was raised to 60000 ms.");
        }
    }

    public static ShareLock create(final String endpoint, final boolean autoRelease, final long staleMillis,
            final EndpointHealth health) {
        return new ShareLock(endpoint, autoRelease, staleMillis, health, health.namedLog(), System::currentTimeMillis, System::nanoTime);
    }

    public long staleAgeMillis() {
        return autoRelease ? staleMillis : -1;
    }

    interface Remote<T> {
        T run() throws IOException;
    }

    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final Classification cause;
        Failure(final Classification cause) {
            super(cause.getStatusText(), cause.getCause());
            this.cause = cause;
        }

        LockOutcome outcome(final LockOutcome.Kind otherwise) {
            return new LockOutcome(cause.getKind().isTransport() ? TRANSPORT_ERROR : otherwise, null, cause);
        }
    }

    <T> T call(final CallType type, final Remote<T> remote) throws Failure {
        final long start = System.nanoTime();
        try {
            return remote.run();
        } catch (final IOException | RuntimeException error) {
            throw new Failure(classifier.classify(error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), type));
        }
    }

    LockValue read(final LockIo io, final String uri) throws Failure {
        return call(CallType.READ, () -> io.read(uri));
    }

    boolean exists(final LockIo io, final String uri) throws Failure {
        return call(CallType.READ, () -> io.exists(uri));
    }

    void delete(final LockIo io, final String uri) throws Failure {
        call(CallType.CHANGE, () -> { io.delete(uri); return null; });
    }

    private void forget(final String key) {
        synchronized (sights) {
            sights.remove(key);
        }
    }

    boolean releasable(final String key, final LockValue found) {
        if (found.time > 0) {
            forget(key);
            if (PathRegistry.held(found)) {
                return false;
            }
            return PathRegistry.INSTANCE.equals(found.instance) || autoRelease && staleMillis != -1
                    && wallMillis.getAsLong() - found.time > staleMillis;
        }
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(found.bytes);
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
        final long now = nanos.getAsLong();
        boolean first = false;
        boolean stale;
        synchronized (sights) {
            Sight seen = sights.get(key);
            if (seen == null || !Arrays.equals(seen.digest, digest)) {
                if (seen == null && sights.size() == 10000) {
                    final Iterator<String> oldest = sights.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                first = seen == null;
                seen = new Sight(digest, now);
                sights.put(key, seen);
            }
            stale = autoRelease && staleMillis != -1
                    && TimeUnit.NANOSECONDS.toMillis(now - seen.start) >= staleMillis;
        }
        if (first) {
            log.warn("Foreign lock first seen at " + key);
        }
        return stale;
    }

    private AtomicOutcome take(final LockIo io, final String from, final String to) throws Failure {
        return call(CallType.CHANGE, () -> io.take(from, to));
    }

    private static String registry(final EndpointHealth health) {
        final java.util.StringJoiner rows = new java.util.StringJoiner("\n");
        for (final PathRegistry.Entry entry : PathRegistry.entries()) {
            final HeldLock handle = entry.markedHandle;
            if (handle != null && entry.owner.health == health) {
                rows.add(handle.snapshotLine());
            }
        }
        return rows.toString();
    }

    String ownerField() {
        return (health.kind() == HealthRegistry.Kind.INBOUND ? "endpoint=" : "connection=") + endpoint;
    }

    private void hold(final String point, final String key) {
        if (TestPoints.enabled()) {
            TestPoints.hold(point, key, ownerField());
        }
    }

    private AtomicResult create(final LockIo io, final String data, final LockValue value) throws Failure {
        final Runnable beforeLink = TestPoints.enabled()
                ? () -> hold("sharelock.before-exclusive-link", LockIo.pointKey(data)) : null;
        return call(CallType.CHANGE, () -> io.create(data, value, beforeLink));
    }

    private LockOutcome takeover(final LockIo io, final String data, final LockValue judged) throws Failure {
        final String canonical = LockIo.suffix(data, ".lock");
        final String away = LockIo.suffix(data, "." + LockIo.token() + ".t.lock");
        final String key = LockIo.pointKey(data);
        hold("takeover.before-take-away", key);
        final AtomicOutcome renamed = take(io, canonical, away);
        if (renamed != AtomicOutcome.DONE) {
            if (renamed == AtomicOutcome.REFUSED) {
                log.warn("The server refused to take away the lock for " + key);
            }
            return LockOutcome.of(LOCKED);
        }
        final LockValue actual;
        try {
            actual = read(io, away);
        } catch (final Failure error) {
            if (error.cause.getKind().isTransport()) {
                throw error;
            }
            take(io, away, canonical);
            return LockOutcome.of(LOCKED);
        }
        hold("takeover.after-read", key);
        if (actual.same(judged)) {
            delete(io, away);
            forget(key);
            return LockOutcome.of(FREE);
        }
        if (actual.bytes.length > LockValue.LIMIT) {
            take(io, away, canonical);
            return LockOutcome.of(LOCKED);
        }
        final long start = System.nanoTime();
        final AtomicResult restore = create(io, data, actual);
        if (restore.isConnectionLost()) {
            return lostReply(restore, start, LOCKED);
        }
        final AtomicOutcome restored = restore.getOutcome();
        if (restored == AtomicOutcome.UNSUPPORTED) {
            take(io, away, canonical);
        } else if (restored == AtomicOutcome.DONE || restored == AtomicOutcome.EXISTS) {
            delete(io, away);
        }
        return LockOutcome.of(LOCKED);
    }

    private LockOutcome check(final LockIo io, final String data, final HeldLock holder) throws Failure {
        final String lock = LockIo.suffix(data, ".lock");
        final String key = LockIo.pointKey(data);
        if (!exists(io, lock)) {
            forget(key);
            return LockOutcome.of(FREE);
        }
        final LockValue found;
        try {
            found = read(io, lock);
        } catch (final Failure error) {
            if (error.cause.getKind() == ErrorKind.MISSING) {
                forget(key);
                return LockOutcome.of(FREE);
            }
            return error.outcome(LOCKED);
        }
        if (!releasable(key, found)) {
            return LockOutcome.of(LOCKED);
        }
        final LockOutcome result = takeover(io, data, found);
        if (result.kind != FREE) {
            return result;
        }
        if (holder != null) {
            holder.takenOver = true;
        }
        return LockOutcome.of(FREE);
    }

    public LockOutcome isLockedByOther(final LockIo io, final String uri) {
        try {
            return check(io, PathRegistry.dataUri(uri), null);
        } catch (final Failure error) {
            return error.outcome(ERROR);
        }
    }

    private LockOutcome readBack(final LockIo io, final HeldLock handle) throws Failure {
        try {
            if (read(io, handle.lockUri).same(handle.entry.value)) {
                return LockOutcome.of(OURS);
            }
            log.warn("Another party wrote the lock for " + handle.entry.key);
            return contention();
        } catch (final Failure error) {
            if (error.cause.getKind().isTransport()) {
                throw error;
            }
            try {
                if (read(io, handle.lockUri).same(handle.entry.value)) {
                    delete(io, handle.lockUri);
                }
            } catch (final Failure cleanup) {
                if (cleanup.cause.getKind().isTransport()) {
                    throw cleanup;
                }
            }
            return error.outcome(ERROR);
        }
    }

    private LockOutcome contention() {
        health.lockContention();
        log.debug("The share lock is busy.");
        return LockOutcome.of(BUSY_SHARE);
    }

    LockOutcome publish(final LockIo io, final HeldLock handle, final Mode mode) {
        try {
            final Answer exclusive = call(CallType.READ, () -> io.exclusive(handle.lockUri));
            if (capabilityLogged.compareAndSet(false, true) && exclusive == Answer.NO) {
                log.warn("The server has no exclusive lock create.");
            }
            final LockOutcome guard = check(io, handle.uri, handle);
            if (guard.kind != FREE) {
                if (guard.kind == LOCKED) {
                    contention();
                    return guard.as(BUSY_SHARE);
                }
                return guard;
            }
            final boolean listed = exclusive == Answer.NO && !handle.takenOver && handle.listing != null
                    && handle.listing.lists(handle.uri);
            if (mode == Mode.LISTENER && !listed && !exists(io, handle.uri)) {
                return LockOutcome.of(FILE_GONE);
            }
            hold("sharelock.after-look", LockIo.pointKey(handle.uri));
            final long start = System.nanoTime();
            final AtomicResult reply = create(io, handle.uri, handle.entry.value);
            final AtomicOutcome created = reply.getOutcome();
            if (reply.isConnectionLost()) {
                if (created == AtomicOutcome.EXISTS) {
                    contention();
                    return lostReply(reply, start, BUSY_SHARE);
                }
                return lostReply(reply, start, created == AtomicOutcome.REFUSED || created == AtomicOutcome.GONE
                        ? ERROR : TRANSPORT_ERROR);
            }
            if (created == AtomicOutcome.EXISTS) {
                return contention();
            }
            if (created == AtomicOutcome.UNSUPPORTED) {
                call(CallType.CHANGE, () -> { io.write(handle.lockUri, handle.entry.value.bytes); return null; });
                final LockOutcome first = readBack(io, handle);
                if (first.kind != OURS) {
                    return first;
                }
                pause(50);
            } else if (created != AtomicOutcome.DONE) {
                return LockOutcome.of(ERROR);
            }
            final LockOutcome verified = readBack(io, handle);
            if (verified.kind != OURS) {
                return verified;
            }
            handle.onShare = true;
            return new LockOutcome(ACQUIRED, handle, null);
        } catch (final Failure error) {
            return error.outcome(ERROR);
        }
    }

    private LockOutcome lostReply(final AtomicResult reply, final long start, final LockOutcome.Kind kind) {
        final FileSystemException failure = new FileSystemException("The lock connection was lost after "
                + reply.getOutcome() + ".");
        failure.setFault(Fault.TRANSPORT);
        return new LockOutcome(kind, null, classifier.classify(failure,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), CallType.READ), reply);
    }

    void pause(final long millis) throws Failure {
        call(CallType.READ, () -> {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("The lock wait was interrupted.");
            }
            return null;
        });
    }

    public LockOutcome enterLocalOnly(final String uri, final int tries, final long gap) {
        return enter(uri, tries, gap, null);
    }

    private LockOutcome enter(final String uri, final int tries, final long gap, final FolderListing listing) {
        final String key = PathRegistry.key(uri);
        for (int attempt = 0; attempt < tries; attempt++) {
            final PathRegistry.Entry entry = PathRegistry.enter(this, key);
            if (entry != null) {
                return new LockOutcome(ACQUIRED, new HeldLock(this, entry, uri, listing), null);
            }
            if (attempt + 1 < tries) {
                try {
                    PathRegistry.await(key, gap);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new LockOutcome(ERROR, null, classifier.classify(interrupted, 0, CallType.READ));
                }
            }
        }
        return LockOutcome.of(LOCK_BUSY);
    }

    public LockOutcome acquire(final LockIo io, final String uri, final Mode mode, final FolderListing listing) {
        return acquire(io, uri, mode, listing, 1, 0, true);
    }

    public LockOutcome acquireWithWait(final LockIo io, final String uri, final Mode mode, final int tries,
            final long gap) {
        return acquire(io, uri, mode, null, tries, gap, false);
    }

    private LockOutcome acquire(final LockIo io, final String uri, final Mode mode, final FolderListing listing,
            final int tries, final long gap, final boolean distinguishBusy) {
        HeldLock held = null;
        boolean acquired = false;
        try {
            for (int attempt = 0; attempt < tries; attempt++) {
                if (held == null) {
                    held = enter(uri, 1, 0, listing).handle;
                }
                if (held != null) {
                    final LockOutcome result = publish(io, held, mode);
                    if (result.kind == ACQUIRED) {
                        acquired = true;
                    }
                    if (result.kind == BUSY_SHARE && result.connectionLost() && !distinguishBusy) {
                        return result.as(LOCK_BUSY);
                    }
                    if (result.kind != BUSY_SHARE || distinguishBusy) {
                        return result;
                    }
                } else if (distinguishBusy) {
                    return LockOutcome.of(BUSY_LOCAL);
                }
                if (attempt + 1 < tries) {
                    if (held == null) {
                        PathRegistry.await(PathRegistry.key(uri), gap);
                    } else {
                        pause(gap);
                    }
                }
            }
            return LockOutcome.of(LOCK_BUSY);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new LockOutcome(ERROR, null, classifier.classify(interrupted, 0, CallType.READ));
        } catch (final Failure error) {
            return error.outcome(ERROR);
        } finally {
            if (held != null && !acquired) {
                held.abandon();
            }
        }
    }

    boolean orphan(final LockIo io, final String data) throws Failure {
        final String lock = LockIo.suffix(data, ".lock");
        final String key = LockIo.pointKey(data);
        final LockValue found;
        try {
            found = read(io, lock);
        } catch (final Failure error) {
            if (error.cause.getKind() == ErrorKind.MISSING) {
                forget(key);
                return false;
            }
            if (error.cause.getKind().isTransport()) {
                throw error;
            }
            return false;
        }
        if (!releasable(key, found) || exists(io, data)) {
            return false;
        }
        final LockOutcome result = takeover(io, data, found);
        if (result.connectionLost()) {
            throw new Failure(result.classification);
        }
        return result.kind == FREE;
    }

    public boolean checkAbsentSingleFile(final LockIo io, final String uri) throws IOException {
        final String data = PathRegistry.dataUri(uri);
        if (!exists(io, LockIo.suffix(data, ".lock"))) {
            forget(LockIo.pointKey(uri));
            return false;
        }
        return orphan(io, data);
    }

    public int sweepOrphans(final LockIo io, final FolderListing listing) throws IOException {
        return sweepOrphans(io, Collections.singletonList(listing));
    }

    /** Examine at most twenty candidates across all listings retained by one poll. */
    public int sweepOrphans(final LockIo io, final Iterable<FolderListing> listings) throws IOException {
        final String previous = lastOrphan;
        final Comparator<String> order = previous == null ? Comparator.naturalOrder()
                : Comparator.comparing((String key) -> key.compareTo(previous) <= 0)
                        .thenComparing(Comparator.naturalOrder());
        // Keep only the next twenty distinct keys, starting after the last examined name and wrapping.
        final TreeMap<String, String> next = new TreeMap<>(order);
        for (final FolderListing listing : listings) {
            for (final String name : listing.names().keySet()) {
                if (name.endsWith(".lock")) {
                    final String base = name.substring(0, name.length() - 5);
                    if (!listing.names().containsKey(base)) {
                        final String uri = listing.child(base);
                        next.put(PathRegistry.key(uri), uri);
                        if (next.size() > 20) {
                            next.pollLastEntry();
                        }
                    }
                }
            }
        }
        int removed = 0;
        for (final Map.Entry<String, String> item : next.entrySet()) {
            // A small unrelated listing needs no continuation and cannot reset another folder's turn.
            if (next.size() == 20) {
                lastOrphan = item.getKey();
            }
            final String uri = item.getValue();
            hold("lock.orphan-sweep", LockIo.pointKey(uri));
            if (orphan(io, uri)) {
                removed++;
            }
        }
        return removed;
    }

    public int checkHeldAges() {
        int count = 0;
        if (staleMillis != -1) {
            for (final PathRegistry.Entry entry : PathRegistry.entries()) {
                if (this == entry.owner
                        && TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - entry.started) > staleMillis / 2
                        && entry.warned.compareAndSet(false, true)) {
                    health.lockHeldLong();
                    log.warn("Lock held for more than half the stale age at " + entry.key);
                    count++;
                }
            }
        }
        return count;
    }

    void lost(final HeldLock handle) {
        health.lockTakenOver();
        log.warn("The lock was taken over at " + handle.entry.key + ". The file was probably processed twice. "
                + "Check the stale age and whether another poller uses a weak lock server.");
    }
}
