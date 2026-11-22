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
package org.wso2.vfs.shared.write;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorClassifier.CallType;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;

public final class TempCleanup {
    private static final long HOUR = TimeUnit.HOURS.toNanos(1);
    private static final int FOLDERS = 200;
    private static final int NAMES = 1000;
    private static final int BUDGET = 100;

    private static final class Size {
        final long bytes;
        final long seen;

        Size(final long bytes, final long seen) {
            this.bytes = bytes;
            this.seen = seen;
        }
    }

    private static final class Folder {
        final Map<String, Size> sizes = new LinkedHashMap<>();
        long started;
        boolean running;
        String last;
    }

    private final Map<String, Folder> folders = new LinkedHashMap<>(16, 0.75f, true);
    private final ShareLock locks;
    private final NamedLog log;
    private final ErrorClassifier classifier;
    private final String ownerField;
    private final String suffix;
    private final LongSupplier wall;
    private final LongSupplier nanos;

    public TempCleanup(final String owner, final EndpointHealth health, final ShareLock locks, final String suffix) {
        this(owner, health, locks, suffix, () -> TestPoints.wallMillis("cleanup"), () -> TestPoints.nanoTime("cleanup"));
    }

    TempCleanup(final String owner, final EndpointHealth health, final ShareLock locks, final String suffix,
            final LongSupplier wall, final LongSupplier nanos) {
        this.ownerField = (health.kind() == HealthRegistry.Kind.INBOUND ? "endpoint=" : "connection=") + owner;
        this.locks = locks;
        this.log = health.namedLog();
        this.classifier = new ErrorClassifier(log);
        this.suffix = suffix;
        this.wall = wall;
        this.nanos = nanos;
    }

    private synchronized Folder claim(final String uri, final long now) {
        Folder folder = folders.get(uri);
        if (folder != null) {
            if (folder.running || now - folder.started < HOUR) {
                return null;
            }
        } else {
            if (folders.size() == FOLDERS) {
                final Iterator<Folder> oldest = folders.values().iterator();
                while (oldest.hasNext()) {
                    final Folder entry = oldest.next();
                    if (!entry.running && now - entry.started >= HOUR) {
                        oldest.remove();
                        break;
                    }
                }
                if (folders.size() == FOLDERS) {
                    return null;
                }
            }
            folder = new Folder();
            folders.put(uri, folder);
        }
        folder.started = now;
        folder.running = true;
        return folder;
    }

    private synchronized void finish(final Folder folder) {
        folder.running = false;
    }

    public SweepResult sweep(final LockIo io, final String folderUri) {
        final long stale = locks.staleAgeMillis();
        if (stale == -1) {
            return new SweepResult(0, SweepResult.End.COMPLETE, null);
        }
        final Folder note = claim(folderUri, nanos.getAsLong());
        if (note == null) {
            return new SweepResult(0, SweepResult.End.COMPLETE, null);
        }
        try {
            return sweep(io, folderUri, note, stale);
        } finally {
            finish(note);
        }
    }

    private SweepResult sweep(final LockIo io, final String uri, final Folder note, final long stale) {
        long start = System.nanoTime();
        final Map<FileName, Long> listedSizes = new HashMap<>();
        final NavigableMap<String, FileObject> candidates = new TreeMap<>();
        try {
            final FileObject folder = io.resolve(uri);
            final AtomicOps ops = AtomicOps.of(folder);
            if (ops == null) {
                folder.refresh();
            }
            if (ops == null ? folder.getType() != FileType.FOLDER : !ops.folderExistsNow()) {
                note.sizes.clear();
                return new SweepResult(0, SweepResult.End.COMPLETE, null);
            }
            final FileObject[] files = ops == null ? folder.getChildren() : ops.listConfirmedFolder(folder, listedSizes);
            final long now = wall.getAsLong();
            for (final FileObject file : files) {
                final String name = file.getName().getBaseName();
                final TempName stage = TempNames.parse(name, ".next");
                if (stage != null && stage.targetName().length() > 5 && stage.targetName().endsWith(".fail")) {
                    continue;
                }
                final TempName parsed = TempNames.parse(name, suffix);
                if (parsed != null && !TempNames.inUse(name) && now - parsed.createdSeconds() * 1000 > stale) {
                    candidates.put(name, file);
                }
            }
        } catch (final IOException | RuntimeException error) {
            final Classification failure = classify(error, start, CallType.READ);
            log.warn("The temp folder could not be listed at " + UriMask.mask(uri));
            return stopped(0, failure);
        }
        note.sizes.keySet().retainAll(candidates.keySet());
        int removed = 0;
        boolean firstDelete = true;
        String name = note.last;
        final int count = Math.min(candidates.size(), BUDGET);
        for (int handled = 0; handled < count; handled++) {
            name = name == null ? null : candidates.higherKey(name);
            if (name == null) {
                name = candidates.firstKey();
            }
            note.last = name;
            final FileObject file = candidates.get(name);
            CallType call = CallType.READ;
            start = System.nanoTime();
            try {
                final Long listed = listedSizes.get(file.getName());
                if (listed == null && file.getType() != FileType.FILE) {
                    note.sizes.remove(name);
                    continue;
                }
                final long bytes = listed != null ? listed : file.getContent().getSize();
                if (bytes < 0) {
                    note.sizes.remove(name);
                    continue;
                }
                final long seen = nanos.getAsLong();
                final Size previous = note.sizes.get(name);
                if (previous == null || previous.bytes != bytes) {
                    if (previous != null || note.sizes.size() < NAMES) {
                        note.sizes.put(name, new Size(bytes, seen));
                    }
                } else if (seen - previous.seen >= HOUR) {
                    if (firstDelete && TestPoints.enabled()) {
                        TestPoints.hold("cleanup.sweep.before-first-delete", AtomicWriter.pointKey(file.getName().getURI()), ownerField);
                    }
                    firstDelete = false;
                    if (TempNames.inUse(name)) {
                        note.sizes.remove(name);
                        continue;
                    }
                    call = CallType.CHANGE;
                    start = System.nanoTime();
                    if (file.delete()) {
                        note.sizes.remove(name);
                        removed++;
                        if (removed == 1 && TestPoints.enabled()) {
                            TestPoints.hold("cleanup.after-delete", AtomicWriter.pointKey(uri), ownerField, "removed=1");
                        }
                    } else {
                        log.warn("The temp file could not be deleted at " + UriMask.friendly(file.getName()));
                    }
                }
            } catch (final IOException | RuntimeException error) {
                final Classification failure = classify(error, start, call);
                if (call == CallType.READ) {
                    note.sizes.remove(name);
                }
                log.warn("The temp file was left at " + UriMask.friendly(file.getName()));
                if (failure.getKind().isTransport()) {
                    return stopped(removed, failure);
                }
            }
        }
        return new SweepResult(removed, candidates.size() > BUDGET ? SweepResult.End.CAPPED : SweepResult.End.COMPLETE, null);
    }

    private Classification classify(final Throwable error, final long start, final CallType call) {
        return classifier.classify(error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), call);
    }

    private static SweepResult stopped(final int removed, final Classification failure) {
        return failure.getKind().isTransport() ? new SweepResult(removed, SweepResult.End.TRANSPORT_ERROR, failure)
                : new SweepResult(removed, SweepResult.End.COMPLETE, null);
    }
}
