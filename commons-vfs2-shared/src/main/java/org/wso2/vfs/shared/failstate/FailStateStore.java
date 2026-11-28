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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.lock.FolderListing;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ReservedNames;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.write.TempName;
import org.wso2.vfs.shared.write.TempNames;

/** The endpoint keeps pending records and their held locks across polls and resumes. */
public final class FailStateStore {
    public enum Decision { PROCESS, SKIP, RETRY, FILE_GONE, TRANSPORT_ERROR }
    public enum Write { WRITTEN, NOT_NEEDED, PENDING }
    public static final class WriteResult {
        public final Write disposition;
        public final Classification failure;

        private WriteResult(Write disposition, Classification failure) {
            this.disposition = disposition;
            this.failure = failure;
        }
    }

    public enum Peek { SKIP, LOOK }
    public enum Match { MATCH, MATCH_INFERRED, DIFFERENT, ABSENT, TRANSPORT_ERROR }

    public static final class BeginResult {
        public final Decision decision;
        public final FileIdentity identity;
        public final FailState state;
        public final Classification failure;
        public final boolean handleTransferred;

        BeginResult(Decision decision, FileIdentity identity, FailState state, Classification failure) {
            this(decision, identity, state, failure, false);
        }

        BeginResult(Decision decision, FileIdentity identity, FailState state, Classification failure, boolean transferred) {
            handleTransferred = transferred;
            this.decision = decision;
            this.identity = identity;
            this.state = state;
            this.failure = failure;
        }
    }

    public static final class Comparison {
        public final Match match;
        public final Classification failure;

        Comparison(Match match, Classification failure) {
            this.match = match;
            this.failure = failure;
        }
    }

    public static final class PendingResult {
        public int written;
        public int pending;
        public final List<FailState> dropped = new ArrayList<>();
        public Classification failure;
    }

    public static final class SweepResult {
        public int removed;
        public final List<FailState> records = new ArrayList<>();
        public Classification failure;
    }

    public static final class Parameters {
        public long retryMillis = 30000;
        public int flagAfter = 12;
        /** Returns the relative target selected by the inbound move configuration for this file. */
        public Function<FileIdentity, String> moveAfterFailedMove;
        public File oldList;
        public Set<String> deprecated = Collections.emptySet();
    }

    private static final class Pending {
        final HeldLock handle;
        final FailState record;
        final boolean update;
        final AtomicBoolean running = new AtomicBoolean();
        int tries;
        long due;
        byte[] bytes;
        TempName stage;
        Classification endFailure;
        boolean ended;
        boolean gone;
        boolean provisionalThreshold;

        Pending(HeldLock handle, FailState record, boolean update) {
            this.handle = handle;
            this.record = record;
            this.update = update;
        }

        void endName() {
            if (stage != null) {
                stage.end();
                stage = null;
            }
        }
    }

    private static final class Marker {
        byte[] bytes;
        String listedName;
        FailState record;
        boolean legacy;
        boolean empty;
        FailState.Outcome outcome = FailState.Outcome.FAILURE;
        FileIdentity oldIdentity;

        boolean foreign() {
            return bytes != null && record == null && !legacy && !empty;
        }
    }

    private static final class Reading {
        Marker canonical;
        FailState record;
        String winner;
        final List<String> kept = new ArrayList<>();
    }

    private static final class CacheEntry {
        final FolderListing.Metadata metadata;
        final long due;

        CacheEntry(FolderListing.Metadata metadata, long due) {
            this.metadata = metadata;
            this.due = due;
        }
    }

    private static final class Note {
        final long value;
        final long start;

        Note(long value, long start) {
            this.value = value;
            this.start = start;
        }
    }

    private static final Pattern OLD = Pattern.compile("([0-9]+)(?::(SUCCESS|FAILURE|UNKNOWN)(?::(-?[0-9]+):(-?[0-9]+))?)?");
    private static final Map<String, FailStateStore> STORES = new ConcurrentHashMap<>();
    private final String endpoint;
    private final String owner;
    private final Parameters parameters;
    private final ShareLock locks;
    private final EndpointHealth health;
    private final NamedLog log;
    private final ErrorClassifier classifier;
    private final LongSupplier wall;
    private final LongSupplier nanos;
    private final Set<String> knownStates = new HashSet<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache = bounded();
    private final Map<String, Note> emptySince = bounded();
    private final Map<String, Map<String, Note>> stray = bounded();
    private volatile String lastSweep;
    private final Map<EndpointHealth.Flag, Set<String>> flags = new EnumMap<>(EndpointHealth.Flag.class);

    private static <T> Map<String, T> bounded() {
        return Collections.synchronizedMap(new LinkedHashMap<String, T>(16, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, T> eldest) {
                return size() > 1000;
            }
        });
    }

    public static FailStateStore forEndpoint(String name, Parameters params, ShareLock locks, EndpointHealth health) {
        return STORES.computeIfAbsent(name, key -> new FailStateStore(key, params, locks, health,
                () -> TestPoints.wallMillis("store"), () -> TestPoints.nanoTime("store")));
    }

    // The endpoint of this name is gone. A store with no pending write is dropped, so the next deploy of the name
    // builds a new store with its new values. A store with a pending write stays as it is, because its lock still
    // guards the file and the next deploy of the name in this node, also after a deactivate, retries the write.
    public static boolean release(String name, FailStateStore store) {
        synchronized (store.pending) {
            return store.pending.isEmpty() && STORES.remove(name, store);
        }
    }

    FailStateStore(String endpoint, Parameters parameters, ShareLock locks, EndpointHealth health,
            LongSupplier wall, LongSupplier nanos) {
        this.endpoint = endpoint;
        this.owner = "endpoint=" + endpoint;
        this.parameters = parameters;
        this.locks = locks;
        this.health = health;
        this.wall = wall;
        this.nanos = nanos;
        log = health.namedLog();
        classifier = new ErrorClassifier(log);
        if (parameters.oldList != null && parameters.oldList.isFile() && parameters.oldList.length() > 0) {
            log.warn("The inbound no longer uses the old failed record list.");
        }
        for (String name : parameters.deprecated) {
            log.warn("The parameter " + name + " is deprecated.");
        }
    }

    private synchronized boolean flag(EndpointHealth.Flag flag, String uri, boolean set) {
        Set<String> members = flags.computeIfAbsent(flag, ignored -> new HashSet<>());
        if (set && members.add(uri)) {
            health.flag(flag, uri);
            return true;
        }
        if (!set && members.remove(uri)) {
            health.clearFlag(flag, uri);
            return true;
        }
        return false;
    }

    private synchronized void known(String uri, boolean present) {
        if (present ? knownStates.add(uri) : knownStates.remove(uri)) {
            health.failStatesPending(knownStates.size());
        }
    }

    private void clear(String uri) {
        known(uri, false);
        cache.remove(uri);
        emptySince.remove(uri);
        flag(EndpointHealth.Flag.LEGACY_MARKER, uri, false);
        flag(EndpointHealth.Flag.FAIL_STATE, uri, false);
        flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, uri, false);
    }

    private static String suffix(String uri, String suffix) {
        int query = uri.indexOf('?');
        return query < 0 ? uri + suffix : uri.substring(0, query) + suffix + uri.substring(query);
    }

    private static String parent(String uri) {
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        return path.substring(0, path.lastIndexOf('/') + 1) + (query < 0 ? "" : uri.substring(query));
    }

    private static String sibling(String uri, String name) {
        return FolderListing.of(parent(uri), Collections.emptyMap()).child(name);
    }

    private static String base(String uri) {
        String key = LockIo.pointKey(uri);
        String path = key.startsWith("file://") ? key.substring("file://".length()) : java.net.URI.create(key).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private long due(long written, int attempts) {
        long delay = Math.min(TimeUnit.MINUTES.toMillis(30), Math.max(0, parameters.retryMillis));
        for (int index = 0; delay > 0 && delay < 1800000 && index < attempts; index++) {
            delay = Math.min(1800000, delay * 2);
        }
        return written > Long.MAX_VALUE - delay ? Long.MAX_VALUE : written + delay;
    }

    private interface Remote<T> {
        T call() throws IOException;
    }

    private static final class Failure extends IOException {
        final Classification classification;
        Failure(Classification classification) {
            super(classification.getCause());
            this.classification = classification;
        }
        boolean transport() {
            return classification.getKind().isTransport();
        }
    }

    private <T> T call(ErrorClassifier.CallType type, Remote<T> op) throws Failure {
        long start = System.nanoTime();
        try {
            return op.call();
        } catch (IOException ex) {
            throw new Failure(FileDigest.classify(classifier, ex, start, type));
        }
    }

    private boolean exists(LockIo io, String uri) throws Failure {
        return call(ErrorClassifier.CallType.READ, () -> FileDigest.exists(io, uri));
    }

    private StatNow stat(LockIo io, String uri) throws Failure {
        return call(ErrorClassifier.CallType.READ, () -> FileDigest.stat(io, uri));
    }

    private void delete(LockIo io, String uri) throws Failure {
        boolean removed = call(ErrorClassifier.CallType.CHANGE, () -> {
            try (FileObject file = io.resolve(uri)) {
                return file.delete();
            }
        });
        if (!removed && exists(io, uri)) {
            throw problem("The marker could not be removed.");
        }
    }

    private Failure problem(String message) {
        FileSystemException error = new FileSystemException(message);
        error.setFault(Fault.APPLICATION);
        return new Failure(classifier.classify(error, 0, ErrorClassifier.CallType.READ));
    }

    private byte[] read(LockIo io, String uri) throws Failure {
        try {
            return call(ErrorClassifier.CallType.READ, () -> {
                try (FileObject file = io.resolve(uri); InputStream input = file.getContent().getInputStream()) {
                    byte[] bytes = new byte[FailState.LIMIT + 1];
                    int count = 0;
                    while (count < bytes.length) {
                        int n = input.read(bytes, count, bytes.length - count);
                        if (n < 0) {
                            break;
                        }
                        count += n;
                    }
                    return Arrays.copyOf(bytes, count);
                }
            });
        } catch (Failure failure) {
            // SFTP and SMB2 answer the read of a name that is not there with an error that does not say "missing", so
            // a read that failed with no transport error gets a look, as the lock owner check does.
            if (!failure.classification.getKind().isTransport() && !exists(io, uri)) {
                return null;
            }
            throw failure;
        }
    }

    private Marker canonical(LockIo io, String uri, String name) throws Failure {
        Marker marker = new Marker();
        String canonical = suffix(uri, ".fail");
        boolean present = call(ErrorClassifier.CallType.READ, () -> {
            try (FileObject file = io.resolve(canonical)) {
                marker.listedName = file.getName().getBaseName();
                return AtomicOps.existsNow(file);
            }
        });
        if (present) {
            marker.bytes = read(io, canonical);
        }
        if (marker.bytes != null && marker.bytes.length == 0) {
            StatNow stat = stat(io, canonical);
            if (!stat.isThere() || stat.getSize() != 0) {
                throw problem("The empty marker changed during the read.");
            }
            long now = nanos.getAsLong();
            Note first = emptySince.compute(uri, (key, old) -> old == null || old.value != stat.getLastModifiedMillis()
                    ? new Note(stat.getLastModifiedMillis(), now) : old);
            marker.legacy = now - first.start >= TimeUnit.SECONDS.toNanos(60);
            marker.empty = !marker.legacy;
            return marker;
        }
        emptySince.remove(uri);
        if (marker.bytes == null) {
            return marker;
        }
        marker.record = FailState.parse(marker.bytes, uri, name);
        if (marker.record == null && marker.bytes.length <= FailState.LIMIT) {
            Matcher old = OLD.matcher(new String(marker.bytes, StandardCharsets.US_ASCII));
            if (old.matches() && (old.group(2) == null || old.group(1).length() >= 13)) {
                try {
                    Long.parseLong(old.group(1));
                    if (old.group(3) != null) {
                        long modified = Long.parseLong(old.group(3));
                        long size = Long.parseLong(old.group(4));
                        boolean ftp = uri.regionMatches(true, 0, "ftp:", 0, 4) || uri.regionMatches(true, 0, "ftps:", 0, 5);
                        marker.oldIdentity = new FileIdentity(uri, size, modified,
                                ftp ? FileIdentity.TimeSource.LISTING : FileIdentity.TimeSource.COMMAND, null);
                    }
                    marker.legacy = true;
                    if ("SUCCESS".equals(old.group(2))) {
                        marker.outcome = FailState.Outcome.SUCCESS;
                    }
                } catch (NumberFormatException malformed) {
                    // The marker does not match an old format.
                }
            }
        }
        return marker;
    }


    private void hold(String point, String uri) {
        if (TestPoints.enabled()) {
            TestPoints.hold(point, LockIo.pointKey(uri), owner);
        }
    }

    private void token(String point, String key, String... fields) throws IOException {
        TestPoints.Fault fault = TestPoints.fault(point, key, fields);
        if (fault != null && "connection-lost".equals(fault.token())) {
            TestPoints.event(point, key, owner, "seam=connection-lost");
            throw lost();
        }
    }

    private static IOException lost() {
        FileSystemException lost = new FileSystemException("The connection was lost.");
        lost.setFault(Fault.TRANSPORT);
        return lost;
    }

    private void step(Pending p, String step) throws Failure {
        if (p == null || !TestPoints.enabled()) {
            return;
        }
        String key = LockIo.pointKey(p.record.identity.uri);
        String[] fields = { owner, "step=" + step, "kind=" + (p.update ? "update" : "first"), "try=" + (p.tries + 1) };
        TestPoints.hold("failstate.step", key, fields);
        call(ErrorClassifier.CallType.CHANGE, () -> {
            token("failstate.step", key, fields);
            return null;
        });
    }

    private void writeBytes(LockIo io, Pending p) throws Failure {
        String uri = p.record.identity.uri;
        String stage = sibling(uri, p.stage.name());
        call(ErrorClassifier.CallType.CHANGE, () -> {
            try (FileObject file = io.resolve(stage); OutputStream out = file.getContent().getOutputStream(false)) {
                int count = 0;
                if (TestPoints.enabled()) {
                    String key = LockIo.pointKey(uri);
                    String kind = "kind=" + (p.update ? "update" : "first");
                    String attempt = "try=" + (p.tries + 1);
                    String limit = TestPoints.arguments("failstate.staging.bytes", key, owner, kind, attempt).get("bytes");
                    if (limit != null) {
                        count = Math.min(p.bytes.length, Math.max(0, Integer.parseInt(limit)));
                        out.write(p.bytes, 0, count);
                        out.flush();
                        String[] fields = { owner, kind, attempt, "bytes=" + count };
                        TestPoints.hold("failstate.staging.bytes", key, fields);
                        token("failstate.staging.bytes", key, fields);
                    }
                }
                out.write(p.bytes, count, p.bytes.length - count);
            }
            return null;
        });
    }

    private AtomicOutcome rename(LockIo io, String uri, String stage, boolean replace) throws Failure {
        return call(ErrorClassifier.CallType.CHANGE, () -> {
            try (FileObject source = io.resolve(sibling(uri, stage)); FileObject target = io.resolve(suffix(uri, ".fail"))) {
                AtomicOps ops = AtomicOps.of(source);
                if (ops == null) {
                    // A provider with no atomic calls renames with the plain calls of the FileObject interface. A
                    // replace is not known there, so the caller deletes and then renames.
                    if (replace) {
                        return AtomicOutcome.UNSUPPORTED;
                    }
                    target.refresh();
                    if (target.exists()) {
                        return AtomicOutcome.EXISTS;
                    }
                    source.moveTo(target);
                    return AtomicOutcome.DONE;
                }
                AtomicResult result;
                if (replace) {
                    String probe = "vfsprobe." + UUID.randomUUID().toString().replace("-", "");
                    try (FileObject first = io.resolve(sibling(uri, probe + ".r1.lock"));
                            FileObject second = io.resolve(sibling(uri, probe + ".r2.lock"))) {
                        result = ops.renameReplacing(target, first, second);
                    }
                } else {
                    result = ops.takeAway(target);
                }
                if (result.isConnectionLost()) {
                    throw lost();
                }
                return result.getOutcome();
            }
        });
    }

    private void swap(LockIo io, String uri, String stage, Pending p) throws Failure {
        step(p, "4.before");
        AtomicOutcome result = rename(io, uri, stage, true);
        step(p, "4.after");
        if (result == AtomicOutcome.UNSUPPORTED) {
            step(p, "5d.before");
            delete(io, suffix(uri, ".fail"));
            step(p, "5d.after");
            step(p, "5r.before");
            result = rename(io, uri, stage, false);
            step(p, "5r.after");
        }
        if (result != AtomicOutcome.DONE) {
            throw problem("The marker rename returned " + result + ".");
        }
    }

    /** Returns the connection failure only when the record is already durable at step seven. */
    private Classification writeMarker(LockIo io, Pending p) throws Failure {
        FailState record = p.record;
        String uri = record.identity.uri;
        if (p.bytes == null) {
            p.bytes = call(ErrorClassifier.CallType.READ, record::bytes);
        }
        hold("failstate.write.start", uri);
        step(p, "1.before");
        Marker canonical = canonical(io, uri, record.name);
        step(p, "1.after");
        if (canonical.foreign() || canonical.empty) {
            throw problem("The canonical marker is occupied.");
        }
        if (canonical.record != null && Arrays.equals(canonical.bytes, p.bytes)) {
            if (p.stage != null) {
                String stage = sibling(uri, p.stage.name());
                try {
                    step(p, "7d.before");
                    if (exists(io, stage)) {
                        delete(io, stage);
                    }
                    step(p, "7d.after");
                } catch (Failure failure) {
                    log.warn("The staging marker could not be removed at " + stage);
                    if (failure.transport()) {
                        return failure.classification;
                    }
                } finally {
                    p.endName();
                }
            }
            return null;
        }
        boolean created = false;
        while (true) {
            if (p.stage == null) {
                step(p, "2.before");
                p.stage = TempNames.begin(canonical.listedName, ".next");
                created = true;
                writeBytes(io, p);
                step(p, "2.after");
            }
            String stage = sibling(uri, p.stage.name());
            step(p, "3.before");
            byte[] bytes = read(io, stage);
            step(p, "3.after");
            if (Arrays.equals(bytes, p.bytes) && FailState.parse(bytes, uri, record.name) != null) {
                break;
            }
            if (bytes != null) {
                try {
                    step(p, "3d.before");
                    delete(io, stage);
                    step(p, "3d.after");
                } catch (Failure failure) {
                    log.warn("The damaged staging marker could not be removed at " + stage);
                    if (failure.transport()) {
                        throw failure;
                    }
                } finally {
                    p.endName();
                }
            } else {
                p.endName();
            }
            if (created) {
                throw problem("The staging marker did not read back whole.");
            }
        }
        swap(io, uri, p.stage.name(), p);
        p.endName();
        return null;
    }

    private List<String> stages(LockIo io, HeldLock held, String uri, String name) throws Failure {
        if (!held.tookOver()) {
            return held.listing == null ? Collections.emptyList() : held.listing.stagingNames(held.listing.markedBase(uri));
        }
        try {
            return call(ErrorClassifier.CallType.READ, () -> {
                List<String> names = new ArrayList<>();
                try (FileObject folder = io.resolve(parent(uri))) {
                    AtomicOps ops = AtomicOps.of(folder);
                    final FileObject[] files;
                    if (ops == null) {
                        // A provider with no atomic calls lists with the plain calls of the FileObject interface.
                        folder.refresh();
                        if (!folder.isFolder()) {
                            throw new IOException("The marker folder could not be listed.");
                        }
                        files = folder.getChildren();
                    } else {
                        if (!ops.folderExistsNow()) {
                            throw new IOException("The marker folder could not be listed.");
                        }
                        files = ops.listConfirmedFolder(folder, new HashMap<>());
                    }
                    for (FileObject file : files) {
                        try (FileObject child = file) {
                            String candidate = child.getName().getBaseName();
                            String target = ReservedNames.stagingTarget(candidate);
                            if (target != null && LockIo.pointKey(sibling(uri, target)).equals(LockIo.pointKey(uri))) {
                                names.add(candidate);
                            }
                        }
                    }
                }
                return names;
            });
        } catch (Failure failure) {
            if (failure.transport()) {
                throw failure;
            }
            log.warn("Staging markers could not be listed for " + uri);
            return Collections.emptyList();
        }
    }

    private void gather(LockIo io, String uri, String name, Reading result, List<String> stages) throws Failure {
        long creation = Long.MIN_VALUE;
        for (String stage : stages) {
            byte[] bytes = read(io, sibling(uri, stage));
            FailState candidate = bytes == null ? null : FailState.parse(bytes, uri, name);
            if (candidate != null) {
                result.kept.add(stage);
                long time = TempNames.parse(stage, ".next").createdSeconds();
                if (result.record == null || candidate.written > result.record.written
                        || candidate.written == result.record.written && time >= creation) {
                    result.record = candidate;
                    result.winner = stage;
                    creation = time;
                }
            }
        }
    }

    private Reading markers(LockIo io, HeldLock held, String uri, String name) throws Failure {
        Reading result = new Reading();
        result.canonical = canonical(io, uri, name);
        result.record = result.canonical.record;
        if (result.canonical.foreign() || result.canonical.empty) {
            return result;
        }
        gather(io, uri, name, result, stages(io, held, uri, name));
        if (result.winner != null) {
            try {
                swap(io, uri, result.winner, null);
                result.kept.remove(result.winner);
            } catch (Failure refusal) {
                if (refusal.transport()) {
                    throw refusal;
                }
                log.warn("The recovered staging marker could not be renamed for " + uri);
            }
        }
        Iterator<String> kept = result.kept.iterator();
        while (kept.hasNext()) {
            String stage = kept.next();
            if (!stage.equals(result.winner)) {
                try {
                    delete(io, sibling(uri, stage));
                    kept.remove();
                } catch (Failure refusal) {
                    if (refusal.transport()) {
                        throw refusal;
                    }
                    log.warn("The older staging marker could not be removed for " + uri);
                }
            }
        }
        if (result.record != null) {
            result.record.staging.addAll(result.kept);
        }
        return result;
    }

    private void remove(LockIo io, String uri, List<String> kept, boolean canonical) throws Failure {
        for (String stage : kept) {
            delete(io, sibling(uri, stage));
        }
        if (canonical) {
            delete(io, suffix(uri, ".fail"));
        }
        clear(uri);
    }

    public void retrySucceeded(LockIo io, FailState state) throws IOException {
        hold("failstate.remove.after-retry", state.identity.uri);
        remove(io, state.identity.uri, state.staging, true);
    }

    public Peek peek(String uri, long markerSize, long markerTime, boolean hasStaging) {
        CacheEntry saved = cache.get(uri);
        return !hasStaging && saved != null && saved.metadata.size == markerSize
                && saved.metadata.modified == markerTime && wall.getAsLong() < saved.due ? Peek.SKIP : Peek.LOOK;
    }

    private void cache(HeldLock held, String uri, String name, FailState record) {
        String actual = held.listing == null ? null : held.listing.markedBase(uri);
        FolderListing.Metadata metadata = actual == null ? null : held.listing.names().get(actual + ".fail");
        if (metadata != null) {
            cache.put(uri, new CacheEntry(metadata, record.due));
        }
    }

    public DigestingInputStream digesting(InputStream stream, FileIdentity identity, LongConsumer progress) {
        return new DigestingInputStream(stream, identity, progress);
    }

    private DigestResult digest(LockIo io, String uri) throws Failure {
        DigestResult result = FileDigest.of(io, uri, classifier);
        if (result.failure != null) {
            throw new Failure(result.failure);
        }
        return result;
    }

    private BeginResult decision(Decision result, FileIdentity id, FailState record) {
        return new BeginResult(result, id, record, null);
    }

    public BeginResult beginFile(LockIo io, HeldLock held, String uri) {
        try {
            StatNow current = stat(io, uri);
            if (!current.isThere()) {
                return decision(Decision.FILE_GONE, null, null);
            }
            FileIdentity id = FileIdentity.from(uri, current);
            String name = base(uri);
            Reading found = markers(io, held, uri, name);
            if (found.canonical.foreign()) {
                log.warn("The foreign fail marker blocks " + uri);
                return decision(Decision.SKIP, id, null);
            }
            if (found.canonical.empty) {
                return decision(Decision.SKIP, id, null);
            }
            FailState record = found.record;
            if (record == null && !found.canonical.legacy) {
                clear(uri);
                return decision(Decision.PROCESS, id, null);
            }
            FileIdentity old = record != null ? record.identity : found.canonical.oldIdentity;
            boolean unreadable = !id.readable() || old != null && !old.readable();
            flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, uri, unreadable);
            if (unreadable) {
                return decision(Decision.SKIP, id, record);
            }
            if (old != null && old.changed(id)) {
                remove(io, uri, found.kept, found.canonical.bytes != null || found.winner != null);
                return decision(Decision.PROCESS, id, null);
            }
            if (record == null) {
                long now = wall.getAsLong();
                record = new FailState(old == null ? id : old, name, now, true, found.canonical.outcome,
                        FailState.Action.DELETE, FailState.Action.NONE, null, null, null,
                        new ActionResult(ActionResult.Step.NONE, true, null, false, null), 0, now, false, null);
                Pending conversion = new Pending(held, record, true);
                try {
                    Classification failure = writeMarker(io, conversion);
                    if (failure != null) {
                        durable(record);
                        return new BeginResult(Decision.TRANSPORT_ERROR, id, record, failure);
                    }
                } catch (Failure failure) {
                    retain(conversion);
                    log.warn("The legacy marker conversion is pending for " + uri);
                    return new BeginResult(failure.transport() ? Decision.TRANSPORT_ERROR : Decision.SKIP,
                            id, record, failure.classification, true);
                }
                durable(record);
            }
            known(uri, true);
            flag(EndpointHealth.Flag.LEGACY_MARKER, uri, record.legacy);
            observeThreshold(record, false);
            if (record.legacy && record.identity.mtimeSource != id.mtimeSource) {
                flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, uri, true);
            }
            if (record.legacy || record.due > wall.getAsLong()) {
                cache(held, uri, name, record);
                return decision(Decision.SKIP, id, record);
            }
            if (old == null || old.digest() == null) {
                flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, uri, true);
                return decision(Decision.SKIP, id, record);
            }
            DigestResult bytes = digest(io, uri);
            if (bytes.kind == DigestResult.Kind.GONE) {
                return decision(Decision.FILE_GONE, null, null);
            }
            if (bytes.count != id.size || !bytes.digest.equals(old.digest())) {
                remove(io, uri, found.kept, true);
                return decision(Decision.PROCESS, id, null);
            }
            id.digest(bytes.digest);
            if (record.failed == FailState.Action.MOVE || record.next == FailState.Action.MOVE_AFTER_FAILED_MOVE) {
                hold("failstate.pending-move.before-retry", uri);
            }
            return decision(Decision.RETRY, id, record);
        } catch (Failure failure) {
            if (!failure.transport()) {
                log.warn("The fail state could not be checked for " + uri);
            }
            return new BeginResult(failure.transport() ? Decision.TRANSPORT_ERROR : Decision.SKIP,
                    null, null, failure.classification);
        }
    }

    public Comparison compareTarget(LockIo io, FileIdentity id, String targetUri) throws IOException {
        try {
            StatNow stat = stat(io, targetUri);
            if (!stat.isThere()) {
                return new Comparison(Match.ABSENT, null);
            }
            FileIdentity target = FileIdentity.from(targetUri, stat);
            if (id.size != target.size) {
                return new Comparison(Match.DIFFERENT, null);
            }
            if (id.digest() == null) {
                return new Comparison(id.readable() && id.mtime == target.mtime && id.mtimeSource == target.mtimeSource
                        ? Match.MATCH_INFERRED : Match.DIFFERENT, null);
            }
            DigestResult bytes = digest(io, targetUri);
            if (bytes.kind == DigestResult.Kind.GONE) {
                return new Comparison(Match.ABSENT, null);
            }
            return new Comparison(id.size == bytes.count && id.digest().equals(bytes.digest)
                    ? Match.MATCH : Match.DIFFERENT, null);
        } catch (Failure failure) {
            if (failure.transport()) {
                return new Comparison(Match.TRANSPORT_ERROR, failure.classification);
            }
            throw failure;
        }
    }


    private FailState next(FileIdentity original, FailState old, FailState.Outcome outcome,
            FailState.Action failed, String failedTarget, ActionResult action) {
        FileIdentity id = new FileIdentity(original.uri, original.size, original.mtime, original.mtimeSource,
                original.digest());
        int count = old == null ? 0 : old.attempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : old.attempts + 1;
        long written = wall.getAsLong();
        if (old != null && written <= old.written) {
            written = old.written == Long.MAX_VALUE ? Long.MAX_VALUE : old.written + 1;
        }
        FailState.Action next = failed;
        String target = failedTarget;
        if (parameters.moveAfterFailedMove != null && failed != FailState.Action.MOVE_AFTER_FAILED_MOVE
                && !(failed == FailState.Action.MOVE && action.committed)) {
            next = FailState.Action.MOVE_AFTER_FAILED_MOVE;
            target = parameters.moveAfterFailedMove.apply(id);
        }
        FailState.MoveOrigin origin = failed == FailState.Action.MOVE_AFTER_FAILED_MOVE ? FailState.MoveOrigin.FALLBACK
                : failed == FailState.Action.MOVE ? FailState.MoveOrigin.ORDINARY : null;
        FailState record = new FailState(id, base(id.uri), written, false, outcome,
                failed == FailState.Action.MOVE_AFTER_FAILED_MOVE ? FailState.Action.MOVE : failed,
                next, origin, failedTarget, target, action, count, due(written, count), count >= parameters.flagAfter,
                action.failure == null ? null : action.failure.getStatusText());
        if (old != null) {
            record.staging.addAll(old.staging);
        }
        return record;
    }

    private void durable(FailState record) {
        known(record.identity.uri, true);
        if (record.legacy && flag(EndpointHealth.Flag.LEGACY_MARKER, record.identity.uri, true)) {
            log.warn("The file has a legacy fail marker at " + record.identity.uri);
        }
        observeThreshold(record, true);
    }

    private synchronized void observeThreshold(FailState record, boolean written) {
        Pending current = pending.get(record.identity.uri);
        if (current != null && record.flagged) {
            current.provisionalThreshold = false;
        }
        if (written) {
            reportThreshold(record);
        } else {
            flag(EndpointHealth.Flag.FAIL_STATE, record.identity.uri, record.flagged);
        }
    }

    private synchronized void discardThreshold(Pending write) {
        if (write.provisionalThreshold && pending.get(write.record.identity.uri) == write) {
            flag(EndpointHealth.Flag.FAIL_STATE, write.record.identity.uri, false);
        }
    }

    private synchronized boolean reportThreshold(FailState record) {
        if (flag(EndpointHealth.Flag.FAIL_STATE, record.identity.uri, record.flagged) && record.flagged) {
            log.error("Post action retries reached the flag threshold for " + record.identity.uri + ". The action "
                    + record.failed + " failed at step " + (record.action == null ? null : record.action.step)
                    + (record.error == null ? "." : ": " + record.error));
            return true;
        }
        return false;
    }

    private void event(Pending p) {
        if (TestPoints.enabled()) {
            TestPoints.event("failstate.try", endpoint, owner, "file=" + LockIo.pointKey(p.record.identity.uri),
                    "try=" + (p.tries + 1), "time=" + wall.getAsLong());
        }
    }

    private void retain(Pending p) {
        p.due = due(wall.getAsLong(), 0);
        p.handle.pendingStateWrite();
        synchronized (pending) {
            pending.put(p.record.identity.uri, p);
            health.stateWritesPending(pending.size());
        }
    }

    private void finished(Pending p) {
        synchronized (pending) {
            if (pending.remove(p.record.identity.uri, p)) {
                flag(EndpointHealth.Flag.STATE_WRITE, p.record.identity.uri, false);
                health.stateWritesPending(pending.size());
            }
        }
    }

    private void removeUnused(LockIo io, Pending p) throws Failure {
        String uri = p.record.identity.uri;
        Marker canonical = canonical(io, uri, p.record.name);
        if (p.stage != null) {
            String stage = sibling(uri, p.stage.name());
            if (exists(io, stage)) {
                delete(io, stage);
            }
        }
        remove(io, uri, p.record.staging, canonical.legacy || canonical.record != null);
        p.endName();
    }

    private Write attempt(LockIo io, Pending p) throws Failure {
        FailState record = p.record;
        String uri = record.identity.uri;
        if (record.legacy || p.bytes == null && record.identity.digest() == null) {
            StatNow current = stat(io, uri);
            if (!current.isThere() || record.identity.changed(FileIdentity.from(uri, current))) {
                p.gone = !current.isThere();
                removeUnused(io, p);
                return Write.NOT_NEEDED;
            }
            if (!record.legacy) {
                if (!record.identity.readable() || current.getLastModifiedMillis() == 0
                        || record.identity.mtimeSource != FileIdentity.from(uri, current).mtimeSource) {
                    flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, uri, true);
                    throw problem("The file identity could not be confirmed.");
                }
                DigestResult digest = digest(io, uri);
                if (digest.kind == DigestResult.Kind.GONE) {
                    p.gone = true;
                    removeUnused(io, p);
                    return Write.NOT_NEEDED;
                }
                if (digest.count != record.identity.size) {
                    removeUnused(io, p);
                    return Write.NOT_NEEDED;
                }
                record.identity.digest(digest.digest);
            }
        }
        if (p.bytes == null && TestPoints.enabled()) {
            TestPoints.Fault fault = TestPoints.fault("failstate.identity", LockIo.pointKey(uri), owner);
            if (fault != null && "no-digest".equals(fault.token())) {
                record.identity.digest(null);
            }
        }
        Classification ended = writeMarker(io, p);
        durable(record);
        if (ended != null) {
            p.handle.abandon();
            p.endFailure = ended;
            p.ended = true;
        }
        return Write.WRITTEN;
    }

    private void release(LockIo io, Pending p) {
        if (!p.ended) {
            LockOutcome release = p.handle.releaseChecked(io);
            p.endFailure = release.classification;
            p.ended = true;
        }
    }

    private WriteResult first(LockIo io, Pending p) {
        Classification cause = null;
        event(p);
        if (io != null) {
            try {
                Write written = attempt(io, p);
                release(io, p);
                return new WriteResult(written, p.endFailure);
            } catch (Failure failure) {
                cause = failure.classification;
                log.warn("The fail state write is pending for " + p.record.identity.uri);
            }
        }
        retain(p);
        return new WriteResult(Write.PENDING, cause);
    }

    public Write recordFailure(LockIo io, HeldLock held, FileIdentity id, FailState.Outcome outcome,
            FailState.Action failed, String target, ActionResult action) {
        return recordFailureResult(io, held, id, outcome, failed, target, action).disposition;
    }

    public WriteResult recordFailureResult(LockIo io, HeldLock held, FileIdentity id, FailState.Outcome outcome,
            FailState.Action failed, String target, ActionResult action) {
        return first(io, new Pending(held, next(id, null, outcome, failed, target, action), false));
    }

    public Write retryFailed(LockIo io, HeldLock held, FailState previous, FailState.Action failed,
            String target, ActionResult action) {
        return retryFailureResult(io, held, previous, failed, target, action).disposition;
    }

    public WriteResult retryFailureResult(LockIo io, HeldLock held, FailState previous, FailState.Action failed,
            String target, ActionResult action) {
        FailState record = next(previous.identity, previous, previous.outcome, failed, target, action);
        return retryWrite(io, held, previous, record);
    }

    public WriteResult retryUnchanged(LockIo io, HeldLock held, FailState previous, Classification failure) {
        int count = previous.attempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : previous.attempts + 1;
        long written = Math.max(wall.getAsLong(), previous.written == Long.MAX_VALUE ? Long.MAX_VALUE : previous.written + 1);
        ActionResult old = previous.action;
        ActionResult action = new ActionResult(old.step, old.clear, old.temp, old.committed, old.phase, failure);
        FailState record = new FailState(previous.identity, previous.name, written, false, previous.outcome,
                previous.failed, previous.next, previous.moveOrigin, previous.failedTarget, previous.target, action,
                count, due(written, count), count >= parameters.flagAfter,
                failure == null ? null : failure.getStatusText());
        record.staging.addAll(previous.staging);
        return retryWrite(io, held, previous, record);
    }

    private WriteResult retryWrite(LockIo io, HeldLock held, FailState previous, FailState record) {
        Pending write = new Pending(held, record, true);
        write.provisionalThreshold = reportThreshold(record) && !previous.flagged;
        return first(io, write);
    }

    private void failedTry(Pending p) {
        p.due = due(wall.getAsLong(), p.tries);
        if (p.tries >= parameters.flagAfter
                && flag(EndpointHealth.Flag.STATE_WRITE, p.record.identity.uri, true)) {
            log.error("State write retries reached the flag threshold for " + p.record.identity.uri);
        }
    }

    public PendingResult runPending(LockIo io) {
        PendingResult result = new PendingResult();
        for (Pending p : pending.values()) {
            if (p.due > wall.getAsLong() || !p.running.compareAndSet(false, true)) {
                continue;
            }
            String uri = p.record.identity.uri;
            try {
                if (pending.get(uri) != p || p.due > wall.getAsLong()) {
                    continue;
                }
                if (p.tries < Integer.MAX_VALUE) {
                    p.tries++;
                }
                event(p);
                if (!exists(io, uri)) {
                    removeUnused(io, p);
                    result.dropped.add(p.record);
                    release(io, p);
                } else {
                    LockOutcome owner = p.handle.verifyOwner(io);
                    if (owner.kind == LockOutcome.Kind.TAKEN_OVER) {
                        log.warn("Another owner took the lock for " + uri);
                        discardThreshold(p);
                        p.endName();
                    } else {
                        if (owner.kind != LockOutcome.Kind.OURS) {
                            if (owner.classification != null) {
                                throw new Failure(owner.classification);
                            }
                            throw problem("The pending owner could not be confirmed.");
                        }
                        Write written = attempt(io, p);
                        if (written == Write.WRITTEN) {
                            result.written++;
                        } else if (p.gone) {
                            result.dropped.add(p.record);
                        }
                        release(io, p);
                    }
                }
                finished(p);
                if (p.endFailure != null && p.endFailure.getKind().isTransport()) {
                    result.failure = p.endFailure;
                    break;
                }
            } catch (Failure failure) {
                failedTry(p);
                if (failure.transport()) {
                    result.failure = failure.classification;
                    break;
                }
            } finally {
                p.running.set(false);
            }
        }
        result.pending = pending.size();
        return result;
    }

    private void sweepWhole(LockIo io, String uri, String name, FolderListing listing, SweepResult result)
            throws Failure {
        LockOutcome acquire = locks.acquire(io, uri, ShareLock.Mode.WRITER, listing);
        if (acquire.kind != LockOutcome.Kind.ACQUIRED) {
            if (acquire.classification != null && acquire.classification.getKind().isTransport()) {
                throw new Failure(acquire.classification);
            }
            if (acquire.connectionLost()) {
                throw new Failure(classifier.classify(lost(), 0, ErrorClassifier.CallType.CHANGE));
            }
            return;
        }
        HeldLock held = acquire.handle;
        boolean usable = true;
        try {
            Reading found = new Reading();
            found.canonical = canonical(io, uri, name);
            found.record = found.canonical.record;
            if (listing != null) {
                gather(io, uri, name, found, listing.stagingNames(listing.markedBase(uri)));
            }
            if (exists(io, uri)) {
                return;
            }
            boolean owned = found.canonical.legacy || found.canonical.record != null;
            hold("failstate.sweep.before-remove", uri);
            remove(io, uri, found.kept, owned);
            result.removed += found.kept.size() + (owned ? 1 : 0);
            if (found.record != null && found.record.action.step == ActionResult.Step.RENAME
                    && !found.record.action.clear) {
                result.records.add(found.record);
            }
        } catch (Failure failure) {
            usable = !failure.transport();
            throw failure;
        } finally {
            if (usable) {
                LockOutcome release = held.releaseChecked(io);
                if (release.classification != null && release.classification.getKind().isTransport()) {
                    throw new Failure(release.classification);
                }
            } else {
                held.abandon();
            }
        }
    }

    private boolean strayDue(String folder, String name, long size, long now) {
        Map<String, Note> notes = stray.computeIfAbsent(folder, unused -> bounded());
        Note note = notes.compute(name, (key, old) -> old == null || old.value != size ? new Note(size, now) : old);
        return now - note.start >= TimeUnit.HOURS.toNanos(1);
    }

    private void sweepStage(LockIo io, FolderListing listing, String stage, String name, Set<String> handled,
            SweepResult result) throws Failure {
        if (TempNames.inUse(stage)) {
            return;
        }
        String uri = listing.child(name);
        String stageUri = listing.child(stage);
        byte[] bytes = read(io, stageUri);
        if (bytes == null) {
            return;
        }
        if (FailState.parse(bytes, uri, base(uri)) != null) {
            Map<String, Note> notes = stray.get(listing.folder);
            if (notes != null) {
                notes.remove(stage);
            }
            if (!listing.names().containsKey(name) && handled.add(name)) {
                sweepWhole(io, uri, base(uri), listing, result);
            }
            return;
        }
        long stale = locks.staleAgeMillis();
        long created = TempNames.parse(stage, ".next").createdSeconds() * 1000;
        if (stale < 0 || wall.getAsLong() - created <= stale) {
            return;
        }
        StatNow current = stat(io, stageUri);
        if (current.isThere() && strayDue(listing.folder, stage, current.getSize(), nanos.getAsLong())
                && !TempNames.inUse(stage)) {
            hold("failstate.sweep.before-remove", uri);
            byte[] confirmed = read(io, stageUri);
            if (confirmed == null || !Arrays.equals(bytes, confirmed) || TempNames.inUse(stage)) {
                return;
            }
            delete(io, stageUri);
            Map<String, Note> notes = stray.get(listing.folder);
            if (notes != null) {
                notes.remove(stage);
            }
            result.removed++;
        }
    }

    private void forgetMissing(FolderListing listing) {
        Map<String, Note> notes = stray.get(listing.folder);
        if (notes != null) {
            synchronized (notes) {
                notes.keySet().retainAll(listing.names().keySet());
            }
        }
    }

    public SweepResult sweep(LockIo io, FolderListing listing) {
        return sweep(io, Collections.singletonList(listing));
    }

    /** Examine at most twenty marker candidates in the listings retained for this poll. */
    public SweepResult sweep(LockIo io, Iterable<FolderListing> listings) {
        SweepResult result = new SweepResult();
        String previous = lastSweep;
        Comparator<String> order = previous == null ? Comparator.naturalOrder()
                : Comparator.comparing((String key) -> key.compareTo(previous) <= 0)
                        .thenComparing(Comparator.naturalOrder());
        TreeMap<String, Map.Entry<FolderListing, String>> next = new TreeMap<>(order);
        for (FolderListing listing : listings) {
            forgetMissing(listing);
            for (String candidate : listing.names().keySet()) {
                if (ReservedNames.stagingTarget(candidate) != null || candidate.endsWith(".fail")
                        && candidate.length() > 5
                        && !listing.names().containsKey(candidate.substring(0, candidate.length() - 5))) {
                    String key = LockIo.pointKey(listing.child(candidate));
                    next.put(key, new SimpleImmutableEntry<>(listing, candidate));
                    if (next.size() > 20) {
                        next.pollLastEntry();
                    }
                }
            }
        }
        Map<String, Set<String>> byFolder = new HashMap<>();
        for (Map.Entry<String, Map.Entry<FolderListing, String>> item : next.entrySet()) {
            if (next.size() == 20) {
                lastSweep = item.getKey();
            }
            FolderListing listing = item.getValue().getKey();
            String candidate = item.getValue().getValue();
            Set<String> handled = byFolder.computeIfAbsent(listing.folder, key -> new HashSet<>());
            String name = ReservedNames.stagingTarget(candidate);
            try {
                if (name != null) {
                    sweepStage(io, listing, candidate, name, handled, result);
                } else {
                    name = candidate.substring(0, candidate.length() - 5);
                    if (handled.add(name)) {
                        String uri = listing.child(name);
                        sweepWhole(io, uri, base(uri), listing, result);
                    }
                }
            } catch (Failure failure) {
                if (failure.transport()) {
                    result.failure = failure.classification;
                    break;
                }
                log.warn("The fail marker sweep could not finish for " + listing.child(name));
            }
        }
        return result;
    }

    private synchronized boolean isFlagged(String uri) {
        Set<String> members = flags.get(EndpointHealth.Flag.FAIL_STATE);
        return members != null && members.contains(uri);
    }

    public SweepResult checkAbsentSingleFile(LockIo io, String uri, FolderListing listing) {
        SweepResult result = new SweepResult();
        String name = base(uri);
        Set<String> handled = new HashSet<>();
        try {
            String actual = listing == null ? null : listing.markedBase(uri);
            if (listing != null) {
                forgetMissing(listing);
            }
            if (exists(io, suffix(uri, ".fail"))) {
                handled.add(actual);
                sweepWhole(io, uri, name, listing, result);
            } else if (listing != null && actual == null && !pending.containsKey(uri) && isFlagged(uri)
                    && !exists(io, uri)) {
                clear(uri);
            }
            if (actual != null) {
                for (String stage : listing.stagingNames(actual)) {
                    sweepStage(io, listing, stage, actual, handled, result);
                }
            }
        } catch (Failure failure) {
            result.failure = failure.classification;
            if (!failure.transport()) {
                log.warn("The fail marker sweep could not finish for " + uri);
            }
        }
        return result;
    }
}
