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
package org.wso2.vfs.shared.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;

public final class EndpointHealth {
    public enum State {
        OK("active"), DEGRADED("degraded"), DOWN("failed"), BREAKER_OPEN("breaker_open"), PAUSED("paused");

        private final String status;

        State(final String status) {
            this.status = status;
        }

        public String status() {
            return status;
        }
    }

    public enum Flag {
        FAIL_STATE, STATE_WRITE, IDENTITY_UNREADABLE, LEGACY_MARKER, READ_CAP
    }

    public enum Breaker { CLOSED, OPEN, HALF_OPEN }

    private static final class Flagged {
        final long count;
        final List<String> paths;

        Flagged(final long count, final List<String> paths) {
            this.count = count;
            this.paths = paths;
        }
    }

    private static final class Poll {
        final boolean paused;
        final boolean armed;
        final boolean running;
        final long progress;
        final State outcome;

        Poll(final boolean paused, final boolean armed, final boolean running, final long progress,
                final State outcome) {
            this.paused = paused;
            this.armed = armed;
            this.running = running;
            this.progress = progress;
            this.outcome = outcome;
        }
    }

    static final class Publication {
        private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean writing = new AtomicBoolean();

        void submit(final Runnable action) {
            pending.add(action);
            do {
                if (!writing.compareAndSet(false, true)) {
                    return;
                }
                try {
                    Runnable next;
                    while ((next = pending.poll()) != null) {
                        next.run();
                    }
                } finally {
                    writing.set(false);
                }
            } while (!pending.isEmpty());
        }
    }

    final HealthRegistry.Kind kind;
    final String name;
    final NamedLog log;
    private volatile MetricsExporter[] exporters = {};
    private final LongSupplier elapsedMillis;
    private final LongSupplier wallMillis;
    private final Runnable lifecycleChanged;
    private volatile long scanInterval;
    private final AtomicReference<Poll> poll;
    private final AtomicReference<Breaker> breaker = new AtomicReference<>(Breaker.CLOSED);
    private final Map<Flag, Flagged> flags = new ConcurrentHashMap<>();

    final Publication publication;
    private final AtomicReference<State> observed = new AtomicReference<>();
    private final long[] sent = new long[MetricsExporter.Metric.values().length];
    private volatile boolean retired;
    private TestPoints.Handle markedRegistry;
    private State exported;
    private volatile Poll publishedPause;
    private final AtomicLong lastSuccess = new AtomicLong();
    private final AtomicReference<Map<String, Object>> lastError = new AtomicReference<>(Collections.emptyMap());
    private final AtomicLong filesDone = new AtomicLong();
    private final AtomicLong filesFailed = new AtomicLong();
    private final AtomicLong transportFaults = new AtomicLong();
    private final AtomicLong lockContention = new AtomicLong();
    private final AtomicLong locksTakenOver = new AtomicLong();
    private final AtomicLong locksHeldLong = new AtomicLong();
    private final AtomicLong lateThreads = new AtomicLong();
    private final AtomicLong inferredMoves = new AtomicLong();
    private final AtomicLong failStatesPending = new AtomicLong();
    private final AtomicLong stateWritesPending = new AtomicLong();
    private final AtomicLong openSessions = new AtomicLong();

    EndpointHealth(final HealthRegistry.Kind kind, final String name, final long scanInterval, final NamedLog log,
            final LongSupplier elapsedMillis, final LongSupplier wallMillis, final Runnable lifecycleChanged,
            final Publication publication) {
        this.publication = publication;
        this.kind = kind;
        this.name = name;
        this.scanInterval = scanInterval;
        this.log = log;
        this.elapsedMillis = elapsedMillis;
        this.wallMillis = wallMillis;
        this.lifecycleChanged = lifecycleChanged;
        poll = new AtomicReference<>(new Poll(kind == HealthRegistry.Kind.INBOUND, false, false, 0, State.OK));
        observed.set(computeState());
    }

    void scanInterval(final long millis) {
        scanInterval = millis;
    }

    boolean active() {
        return !poll.get().paused;
    }

    void exporters(final MetricsExporter... selected) {
        exporters = selected;
    }

    private void publishCounter(final MetricsExporter.Metric metric, final AtomicLong total) {
        final long count = total.get();
        final long delta = count - sent[metric.ordinal()];
        if (delta > 0) {
            for (final MetricsExporter exporter : exporters) {
                exporter.addToCounter(this, metric, delta);
            }
            sent[metric.ordinal()] = count;
        }
    }

    private void publishCounters() {
        publishCounter(MetricsExporter.Metric.FILES, filesDone);
        publishCounter(MetricsExporter.Metric.FAILURES, filesFailed);
        publishCounter(MetricsExporter.Metric.TRANSPORT_FAULTS, transportFaults);
        publishCounter(MetricsExporter.Metric.LOCK_CONTENTION, lockContention);
    }

    private void exportState(final State previous, final State current) {
        for (final MetricsExporter exporter : exporters) {
            exporter.setState(this, previous, current);
        }
    }

    private void exportGauges() {
        for (final MetricsExporter exporter : exporters) {
            exporter.setGauge(this, MetricsExporter.Metric.LAST_SUCCESS, lastSuccess.get() / 1000.0);
            exporter.setGauge(this, MetricsExporter.Metric.PENDING, failStatesPending.get());
            exporter.setGauge(this, MetricsExporter.Metric.FLAGGED, count(flags.get(Flag.FAIL_STATE)));
            exporter.setGauge(this, MetricsExporter.Metric.OPEN_SESSIONS, openSessions.get());
        }
    }

    public HealthRegistry.Kind kind() {
        return kind;
    }

    public NamedLog namedLog() {
        return log;
    }

    public void paused() {
        final Poll previous = poll.getAndSet(new Poll(true, false, false, 0, State.OK));
        if (!previous.paused) {
            changed();
        }
    }

    public void resumed() {
        poll.set(new Poll(false, false, false, 0, State.OK));
        changed();
    }

    public void pollStarted() {
        final Poll previous = poll.getAndUpdate(old ->
                new Poll(false, true, true, elapsedMillis.getAsLong(), old.outcome));
        if (previous.paused) {
            changed();
        }
    }

    private void changed() {
        observed.updateAndGet(previous -> computeState());
        lifecycleChanged.run();
    }

    void publishPause() {
        final Poll sample = poll.get();
        if (sample.paused && sample != publishedPause) {
            refresh(false);
        }
    }

    public void progress(final long bytes) {
        if (bytes > 0) {
            fileProgress();
        }
    }

    private void fileProgress() {
        poll.updateAndGet(old -> old.running
                ? new Poll(false, true, true, elapsedMillis.getAsLong(), old.outcome) : old);
    }

    public void pollFinished(final boolean rootReached, final Classification failure) {
        State outcome = State.OK;
        if (failure == null) {
            lastSuccess.set(wallMillis.getAsLong());
        } else {
            recordError(failure);
            if (failure.getKind().isTransport()) {
                outcome = rootReached ? State.DEGRADED : State.DOWN;
            }
        }
        poll.set(new Poll(false, true, false, elapsedMillis.getAsLong(), outcome));
    }

    public void fileDone() {
        filesDone.incrementAndGet();
        fileProgress();
    }

    public void fileFailed(final Classification failure) {
        filesFailed.incrementAndGet();
        recordError(failure);
        fileProgress();
    }

    public void transportFault(final Classification failure) {
        transportFaults.incrementAndGet();
        recordError(failure);
    }

    private void recordError(final Classification failure) {
        final Map<String, Object> error = new LinkedHashMap<>();
        error.put("time", wallMillis.getAsLong());
        error.put("kind", failure.getKind().name());
        if (failure.getStatusText() != null) {
            error.put("text", UriMask.mask(failure.getStatusText()));
        }
        lastError.set(Collections.unmodifiableMap(error));
    }

    public void success() {
        lastSuccess.set(wallMillis.getAsLong());
        poll.set(new Poll(false, false, false, 0, State.OK));
        fileDone();
    }

    public void failure(final Classification failure) {
        fileFailed(failure);
        if (failure.getKind().isTransport()) {
            transportFaults.incrementAndGet();
            poll.set(new Poll(false, false, false, 0, State.DOWN));
        }
    }

    public void breakerState(final Breaker state) {
        breaker.set(state);
    }

    public void lockContention() {
        lockContention.incrementAndGet();
    }

    public void lockTakenOver() {
        locksTakenOver.incrementAndGet();
    }

    public void lockHeldLong() {
        locksHeldLong.incrementAndGet();
    }

    public void lateThread() {
        lateThreads.incrementAndGet();
    }

    public void inferredMove() {
        inferredMoves.incrementAndGet();
    }

    public void openSessions(final long count) {
        openSessions.set(count);
    }

    public void failStatesPending(final long count) {
        failStatesPending.set(count);
    }

    public void stateWritesPending(final long count) {
        stateWritesPending.set(count);
    }

    public void flag(final Flag flag, final String path) {
        final String masked = UriMask.mask(path);
        flags.compute(flag, (key, old) -> {
            final long count = old == null ? 1 : old.count + 1;
            final List<String> paths = old == null ? Collections.emptyList() : old.paths;
            if (paths.size() == 100) {
                return new Flagged(count, paths);
            }
            final List<String> next = new ArrayList<>(paths);
            next.add(masked);
            return new Flagged(count, Collections.unmodifiableList(next));
        });
    }

    public void clearFlag(final Flag flag, final String path) {
        final String masked = UriMask.mask(path);
        flags.computeIfPresent(flag, (key, old) -> {
            if (old.count == 1) {
                return null;
            }
            final List<String> next = new ArrayList<>(old.paths);
            next.remove(masked);
            return new Flagged(old.count - 1, Collections.unmodifiableList(next));
        });
    }

    private State computeState() {
        final Poll sample = poll.get();
        if (sample.paused) {
            return State.PAUSED;
        }
        if (kind == HealthRegistry.Kind.CONNECTION && breaker.get() == Breaker.OPEN) {
            return State.BREAKER_OPEN;
        }
        final long interval = scanInterval;
        final long bound = interval > Long.MAX_VALUE / 3 ? Long.MAX_VALUE : Math.max(180000L, 3 * Math.max(0, interval));
        if (sample.armed && (sample.running || interval > 0)
                && elapsedMillis.getAsLong() - sample.progress >= bound) {
            return State.DOWN;
        }
        if (sample.outcome == State.OK && !flags.isEmpty()) {
            return State.DEGRADED;
        }
        return sample.outcome;
    }

    public State state() {
        return observed.get();
    }

    public synchronized void registerFailStateSnapshot(final Supplier<String> supplier) {
        if (TestPoints.enabled() && kind == HealthRegistry.Kind.INBOUND && !retired && markedRegistry == null) {
            markedRegistry = TestPoints.snapshot("failstate.registry", name, supplier);
        }
    }

    void retire(final Runnable finished) {
        if (TestPoints.enabled()) {
            synchronized (this) {
                retired = true;
                if (markedRegistry != null) {
                    markedRegistry.remove();
                    markedRegistry = null;
                }
            }
        } else {
            retired = true;
        }
        publication.submit(() -> {
            try {
                publishCounters();
            } finally {
                finished.run();
            }
        });
    }

    void refresh(final boolean summary) {
        publication.submit(() -> {
            if (!retired) {
                publish(summary);
            }
        });
    }

    private void publish(final boolean summary) {
        final Poll sample = poll.get();
        final State current = observed.updateAndGet(previous -> computeState());
        if (current == State.PAUSED && sample.paused) {
            publishedPause = sample;
        }
        final State previous = exported;
        if (previous != current) {
            exported = current;
            exportState(previous, current);
            final String message = "Health changed to " + current + ". " + snapshot(current);
            if (current == State.DOWN) {
                writeLog(() -> log.error(message));
            } else if (current == State.DEGRADED) {
                writeLog(() -> log.warn(message));
            } else {
                writeLog(() -> log.debug(message));
            }
        }
        publishCounters();
        exportGauges();
        if (summary) {
            writeLog(() -> log.debug("Health summary " + snapshot(current)));
        }
    }

    public Map<String, Object> snapshot() {
        return snapshot(state());
    }

    private Map<String, Object> snapshot(final State state) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", state.name());
        values.put("lastSuccess", lastSuccess.get());
        values.put("lastError", lastError.get());
        values.put("filesDone", filesDone.get());
        values.put("filesFailed", filesFailed.get());
        values.put("transportFaults", transportFaults.get());
        values.put("lockContention", lockContention.get());
        values.put("locksTakenOver", locksTakenOver.get());
        values.put("locksHeldLong", locksHeldLong.get());
        values.put("lateThreads", lateThreads.get());
        values.put("inferredMoves", inferredMoves.get());
        values.put("failStatesPending", failStatesPending.get());
        values.put("stateWritesPending", stateWritesPending.get());
        final Map<Flag, Flagged> sampled = new EnumMap<>(Flag.class);
        sampled.putAll(flags);
        values.put("failStatesFlagged", count(sampled.get(Flag.FAIL_STATE)));
        values.put("stateWritesFlagged", count(sampled.get(Flag.STATE_WRITE)));
        final Map<String, Object> details = new LinkedHashMap<>();
        sampled.forEach((flag, value) -> {
            final Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("count", value.count);
            detail.put("paths", value.paths);
            details.put(flag.name(), detail);
        });
        values.put("flags", details);
        values.put("breakerState", breaker.get().name());
        values.put("openSessions", openSessions.get());
        return values;
    }

    public static void setStatisticsMetadata(final String uri, final BiConsumer<String, Object> setProperty) {
        final Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("fileURI", UriMask.mask(uri));
        metadata.put("protocol", "file");
        setProperty.accept("statisticsMetadata", metadata);
    }

    static void writeLog(final Runnable write) {
        try {
            write.run();
        } catch (final Throwable ignored) {
            // A logging backend failure must not stop health publication.
        }
    }

    private static long count(final Flagged value) {
        return value == null ? 0 : value.count;
    }
}
