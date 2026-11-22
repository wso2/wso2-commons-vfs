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

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Ends a write that is blocked in a socket.
 * <p>
 * One daemon thread per copy of this library reads the marks of every registered stream once per scan. When one send
 * has moved no bytes for the stall timeout of its stream, the watcher aborts the socket group of that stream. The
 * blocked writer then fails on its own thread. The watcher runs no operation and waits for nothing.
 * </p>
 * <p>
 * The watcher starts with the first registration. It ends itself when the registry has been empty for one minute,
 * and the next registration starts a new one.
 * </p>
 * <p>
 * The watcher writes no log line itself, because a log that hangs would then hold the only thread that can end the
 * writes of the other connections. It hands each line to a second thread, which lives only while lines wait.
 * </p>
 */
public final class ProgressWatchdog {

    /**
     * The name of the watcher thread.
     */
    public static final String THREAD_NAME = "vfs-progress-watchdog";

    static final String REPORTER_NAME = "vfs-stall-report";

    // The most lines that wait for the log. A line that finds no room is dropped and counted.
    static final int REPORT_LIMIT = 64;

    private static final long IDLE_END_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final Set<WatchedOutputStream> REGISTRY = ConcurrentHashMap.newKeySet();

    // How many streams are registered, and since when none is. A change puts a new object here, so a reader gets the
    // count and the time of one moment, and one empty time of the registry is one object.
    private static final AtomicReference<State> STATE = new AtomicReference<>(new State(0, false, 0));

    // A test of this package holds it to stop the watcher at its end.
    static final Object THREAD_LOCK = new Object();

    // Guarded by THREAD_LOCK.
    private static Thread watcher;

    private static final AtomicLong SCANS = new AtomicLong();

    private static final BlockingQueue<Report> REPORTS = new ArrayBlockingQueue<>(REPORT_LIMIT);

    private static final AtomicInteger DROPPED = new AtomicInteger();

    private static final Object REPORTER_LOCK = new Object();

    // Guarded by REPORTER_LOCK.
    private static Thread reporter;

    // A test replaces the clock and the log and shortens the scan period.
    static volatile LongSupplier clock = System::nanoTime;

    static volatile long scanMillis = 1000;

    static volatile Log log = LogFactory.getLog(ProgressWatchdog.class);

    private ProgressWatchdog() {
    }

    /**
     * Registers a stream and starts the watcher when none runs. A stream with the stall timeout zero is not watched.
     *
     * @param stream the stream to watch.
     */
    public static void register(final WatchedOutputStream stream) {
        if (stream.getStallTimeoutMillis() <= 0) {
            return;
        }
        // The count goes up before the stream is in the set, so the count is never under the size of the set.
        count(1);
        if (!REGISTRY.add(stream)) {
            // It was registered before.
            count(-1);
        }
        synchronized (THREAD_LOCK) {
            if (watcher == null) {
                watcher = start(ProgressWatchdog::run, THREAD_NAME);
            }
        }
    }

    /**
     * Takes a stream out of the registry. It takes no lock, because a removal runs inside an abort.
     *
     * @param stream the stream.
     */
    public static void unregister(final WatchedOutputStream stream) {
        if (REGISTRY.remove(stream)) {
            count(-1);
        }
    }

    private static void count(final int change) {
        State old;
        State next;
        do {
            old = STATE.get();
            next = new State(old.streams + change, false, 0);
        } while (!STATE.compareAndSet(old, next));
        if (next.streams == 0) {
            // The clock is read after the count is zero. So the registry was empty at that time, and the watcher
            // never counts a time in which a stream was registered. Until the time is set the watcher does not end.
            // A registration in between puts its own state here, and then this time is not needed.
            STATE.compareAndSet(next, new State(0, true, clock.getAsLong()));
        }
    }

    // Zero means that no send is in progress, so the clock of a mark never gives zero.
    static long now() {
        final long nanos = clock.getAsLong();
        return nanos == 0 ? 1 : nanos;
    }

    static int registered() {
        return REGISTRY.size();
    }

    static Thread watcher() {
        synchronized (THREAD_LOCK) {
            return watcher;
        }
    }

    static Thread reporter() {
        synchronized (REPORTER_LOCK) {
            return reporter;
        }
    }

    // The number of scans that ended with a watcher that goes on.
    static long scans() {
        return SCANS.get();
    }

    // The field is set after the start. So a thread that could not start leaves no dead thread in the field.
    private static Thread start(final Runnable body, final String name) {
        final Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void run() {
        try {
            while (true) {
                try {
                    Thread.sleep(scanMillis);
                } catch (final InterruptedException e) {
                    // Nobody stops the watcher. It ends only by the rule of the empty registry.
                }
                final long now = clock.getAsLong();
                scan(now);
                final State state = STATE.get();
                if (state.emptyTimed && now - state.emptySince >= IDLE_END_NANOS) {
                    synchronized (THREAD_LOCK) {
                        // Only the empty time that was just measured ends the watcher. A registration puts a new
                        // state first and takes this lock after. So either the state differs here and the watcher
                        // goes on, or the registration finds no watcher and starts one.
                        if (STATE.get() == state) {
                            watcher = null;
                            return;
                        }
                    }
                }
                SCANS.incrementAndGet();
            }
        } finally {
            synchronized (THREAD_LOCK) {
                if (watcher == Thread.currentThread()) {
                    watcher = null;
                }
            }
        }
    }

    static void scan(final long now) {
        for (final WatchedOutputStream stream : REGISTRY) {
            try {
                check(stream, now);
            } catch (final RuntimeException e) {
                // One stream must not end the scan of the others, and it must not end the watcher.
                report("The write watchdog could not check a stream.", e);
            }
        }
    }

    private static void check(final WatchedOutputStream stream, final long now) {
        final SocketGroup group = stream.getGroup();
        if (stream.isDone() || group.isAborted()) {
            unregister(stream);
            return;
        }
        // The byte count is read before the marks and once more after them. A stream adds the bytes of a send after
        // it has cleared the mark of that send. So with a mark that is set and a count that did not change, the
        // count is the one before the send of that mark.
        final long bytes = stream.getBytesWritten();
        final long stalledNanos = Math.max(elapsed(now, stream.getWriteSince()), elapsed(now, stream.closeSince()));
        if (stalledNanos <= TimeUnit.MILLISECONDS.toNanos(stream.getStallTimeoutMillis())) {
            return;
        }
        if (bytes != stream.getBytesWritten()) {
            // A send ended while this scan read the stream. The next scan reads the stream again.
            return;
        }
        // From here on the stall is a fact, also when the send ends before the sockets are closed.
        final String reason = "A write to " + stream.host() + " moved no bytes for "
                + TimeUnit.NANOSECONDS.toMillis(stalledNanos) + " ms";
        // The reason is claimed first and the sockets are closed after. The warning is asked for last, and only when
        // this reason was the first. An abort from elsewhere that was first keeps its own reason.
        final boolean first = group.claim(reason);
        group.abort(reason);
        unregister(stream);
        if (first) {
            report(reason + " after " + bytes + " bytes. The watchdog closes the sockets of this connection.", null);
        }
    }

    private static long elapsed(final long now, final long since) {
        return since == 0 ? 0 : now - since;
    }

    // Hands a line to the reporter and never waits. No error of it reaches the watcher.
    private static void report(final String text, final Throwable error) {
        try {
            if (!REPORTS.offer(new Report(text, error))) {
                DROPPED.incrementAndGet();
            }
            synchronized (REPORTER_LOCK) {
                if (reporter == null) {
                    reporter = start(ProgressWatchdog::deliver, REPORTER_NAME);
                }
            }
        } catch (final RuntimeException | Error e) {
            // The line is lost, or it waits for the next reporter. The watcher goes on.
        }
    }

    private static void deliver() {
        try {
            while (true) {
                final Report report;
                synchronized (REPORTER_LOCK) {
                    report = REPORTS.poll();
                    if (report == null) {
                        // A request adds its line first and takes this lock after. So it finds no reporter and
                        // starts one.
                        reporter = null;
                        return;
                    }
                }
                warn(report.text, report.error);
                final int dropped = DROPPED.getAndSet(0);
                if (dropped > 0) {
                    warn(dropped + " more lines of the write watchdog were dropped, because the log took too long.",
                            null);
                }
            }
        } finally {
            synchronized (REPORTER_LOCK) {
                if (reporter == Thread.currentThread()) {
                    reporter = null;
                }
            }
        }
    }

    private static void warn(final String text, final Throwable error) {
        try {
            if (error == null) {
                log.warn(text);
            } else {
                log.warn(text, error);
            }
        } catch (final RuntimeException e) {
            // The log failed. A failure of the log is not logged.
        }
    }

    private static final class State {

        private final int streams;

        // True when emptySince is set. It is set only while no stream is registered.
        private final boolean emptyTimed;

        private final long emptySince;

        State(final int streams, final boolean emptyTimed, final long emptySince) {
            this.streams = streams;
            this.emptyTimed = emptyTimed;
            this.emptySince = emptySince;
        }
    }

    private static final class Report {

        private final String text;

        private final Throwable error;

        Report(final String text, final Throwable error) {
            this.text = text;
            this.error = error;
        }
    }
}
