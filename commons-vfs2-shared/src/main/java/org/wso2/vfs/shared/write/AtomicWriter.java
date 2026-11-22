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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.NameScope;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.CommitResult;
import org.wso2.org.apache.commons.vfs2.provider.CommitStep;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorClassifier.CallType;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.lock.HeldLock;
import org.wso2.vfs.shared.lock.LockIo;
import org.wso2.vfs.shared.lock.LockOutcome;
import org.wso2.vfs.shared.lock.ShareLock;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;

public final class AtomicWriter {
    public interface Content {
        void writeTo(OutputStream output) throws IOException;
    }

    public interface Attribute {
        void apply(FileObject temp) throws IOException;
    }

    /** The operation owns the name across retries. No client or stream is kept here. */
    public static final class Temp implements AutoCloseable {
        public final TempName name;
        public final String uri;
        public final String target;
        public final String folder;
        private final String parent;
        private final Folder note;

        private Temp(final TempName name, final FileObject file, final String target, final String parent,
                final Folder note) {
            this.name = name;
            this.uri = file.getName().getURI();
            this.target = target;
            this.parent = parent;
            this.note = note;
            this.folder = file.getName().getParent().getURI();
        }

        @Override
        public void close() {
            name.end();
        }
    }

    public enum Outcome { COMMITTED, TARGET_EXISTS, REPLACE_NOT_POSSIBLE, LOCK_BUSY, ERROR }

    /** Preserve the deciding reply even when a later cleanup loses the connection. */
    public static final class Result {
        public final Outcome outcome;
        public final CommitResult commit;
        public final LockOutcome lock;
        public final Classification failure;
        private Classification cleanupFailure;
        private LockOutcome release;
        private SweepResult sweep;

        private Result(final Outcome outcome, final CommitResult commit, final LockOutcome lock,
                final Classification failure) {
            this.outcome = outcome;
            this.commit = commit;
            this.lock = lock;
            this.failure = failure;
        }

        public Classification cleanupFailure() { return cleanupFailure; }
        public LockOutcome release() { return release; }
        public SweepResult sweep() { return sweep; }

        public boolean connectionLost() {
            return commit != null && commit.isConnectionLost() || lock != null && lock.connectionLost()
                    || transport(failure) || transport(cleanupFailure) || release != null && transport(release.classification)
                    || sweep != null && sweep.ended() == SweepResult.End.TRANSPORT_ERROR;
        }

        private static boolean transport(final Classification error) {
            return error != null && error.getKind().isTransport();
        }
    }

    private static final class Folder {
        private Boolean useChild;
    }

    private static final int FOLDER_LIMIT = 1000;
    private final Map<String, Folder> folders = new LinkedHashMap<>();
    private final AtomicBoolean replaceWarned = new AtomicBoolean();
    private final String owner;
    private final String ownerField;
    private final ShareLock locks;
    private final NamedLog log;
    private final ErrorClassifier classifier;
    private final boolean useFolder;
    private final String folderName;
    private final String suffix;
    private final TempCleanup cleanup;

    public AtomicWriter(final String owner, final EndpointHealth health, final ShareLock locks) {
        this(owner, health, locks, true, ".part", ".part");
    }

    public AtomicWriter(final String owner, final EndpointHealth health, final ShareLock locks,
            final boolean useFolder, final String folderName, final String suffix) {
        this.owner = owner;
        this.ownerField = (health.kind() == HealthRegistry.Kind.INBOUND ? "endpoint=" : "connection=") + owner;
        this.locks = locks;
        this.log = health.namedLog();
        this.classifier = new ErrorClassifier(log);
        this.useFolder = useFolder;
        this.folderName = folderName;
        this.suffix = suffix;
        this.cleanup = new TempCleanup(owner, health, locks, suffix);
    }

    private Classification classify(final Throwable error, final long start, final CallType type) {
        return classifier.classify(error, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), type);
    }

    private Folder folderNote(final String key) {
        synchronized (folders) {
            Folder note = folders.get(key);
            if (note == null) {
                if (folders.size() == FOLDER_LIMIT) {
                    folders.remove(folders.keySet().iterator().next());
                }
                note = new Folder();
                folders.put(key, note);
            }
            return note;
        }
    }

    private FileObject tempFolder(final FileObject parent, final Folder note) throws IOException {
        if (note == null) {
            return parent;
        }
        synchronized (note) {
            if (Boolean.FALSE.equals(note.useChild)) {
                return parent;
            }
            final FileObject child = parent.resolveFile(folderName, NameScope.CHILD);
            if (note.useChild == null) {
                final long start = System.nanoTime();
                try {
                    child.refresh();
                    child.createFolder();
                    note.useChild = true;
                } catch (final IOException | RuntimeException error) {
                    if (classify(error, start, CallType.CHANGE).getKind().isTransport()) {
                        throw error;
                    }
                    note.useChild = false;
                    log.warn("The temp folder could not be created. Using " + UriMask.friendly(parent.getName()));
                    return parent;
                }
            }
            return child;
        }
    }

    public Temp prepare(final LockIo io, final String targetUri) throws IOException {
        final FileObject target = io.resolve(targetUri);
        final FileObject parent = target.getParent();
        final String key = parent.getName().getURI();
        final Folder note = useFolder ? folderNote(key) : null;
        final FileObject folder = tempFolder(parent, note);
        final TempName name = TempNames.begin(target.getName().getBaseName(), suffix);
        try {
            return new Temp(name, folder.resolveFile(name.name(), NameScope.CHILD), targetUri, key, note);
        } catch (final IOException | RuntimeException error) {
            name.end();
            throw error;
        }
    }

    /** The frame may set metadata on the closed temp before calling commit. */
    public void transfer(final LockIo io, final Temp temp, final Content content) throws IOException {
        final long start = System.nanoTime();
        try (OutputStream output = io.resolve(temp.uri).getContent().getOutputStream(false)) {
            content.writeTo(TestPoints.enabled() ? marked(output, temp.folder) : output);
        } catch (final IOException | RuntimeException error) {
            final Classification primary = classify(error, start, CallType.CHANGE);
            if (primary.getKind() == ErrorKind.MISSING && !temp.folder.equals(temp.parent)) {
                synchronized (folders) {
                    folders.remove(temp.parent, temp.note);
                }
            }
            if (!primary.getKind().isTransport()) {
                // A failed write can also lose the connection when the stream closes.
                for (final Throwable close : error.getSuppressed()) {
                    if (classify(close, start, CallType.CHANGE).getKind().isTransport()) {
                        final IOException lost = new IOException("The temp stream could not close.", close);
                        lost.addSuppressed(error);
                        throw lost;
                    }
                }
            }
            throw error;
        }
    }

    static String pointKey(final String uri) {
        return uri.replaceFirst("(://)[^/?#]*@", "$1").split("[?#]", 2)[0];
    }

    private OutputStream marked(final OutputStream output, final String folder) {
        return new FilterOutputStream(output) {
            private int remaining = 1024 * 1024;

            private void progress(final int bytes) {
                remaining -= bytes;
                if (remaining == 0) {
                    TestPoints.hold("temp.in-write", pointKey(folder), ownerField, "bytes=1048576");
                }
            }

            @Override
            public void write(final int value) throws IOException {
                out.write(value);
                if (remaining > 0) {
                    progress(1);
                }
            }

            @Override
            public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                if (remaining <= 0 || length == 0) {
                    out.write(bytes, offset, length);
                    return;
                }
                final int first = Math.min(length, remaining);
                out.write(bytes, offset, first);
                progress(first);
                if (first < length) {
                    out.write(bytes, offset + first, length - first);
                }
            }
        };
    }

    private void warnDeleted(final boolean deleted) {
        if (deleted) {
            warnNonReplacing();
            log.warn("The old target was deleted before the rename.");
        }
    }

    private void warnNonReplacing() {
        if (replaceWarned.compareAndSet(false, true)) {
            log.warn("This server has no replacing rename. The name can be missing after a failure until the file is written again.");
        }
    }

    private Result publish(final LockIo io, final Temp temp, final CommitStep.Mode mode,
            final boolean allowDeleteThenRename) {
        final long start = System.nanoTime();
        final TempName link = TempNames.begin("vfsprobe", suffix);
        final TempName first = TempNames.begin("vfsprobe", suffix);
        final TempName second = TempNames.begin("vfsprobe", suffix);
        try {
            final FileObject source = io.resolve(temp.uri);
            final FileObject target = io.resolve(temp.target);
            final boolean probe = mode == CommitStep.Mode.OVERWRITE && allowDeleteThenRename;
            final FileObject folder = source.getParent();
            final CommitStep.Options options = new CommitStep.Options(allowDeleteThenRename, link.name(),
                    probe ? folder.resolveFile(first.name(), NameScope.CHILD) : null,
                    probe ? folder.resolveFile(second.name(), NameScope.CHILD) : null);
            final CommitResult answer = CommitStep.commit(source, target, mode, options);
            if (answer.getMethod() == CommitResult.Method.DELETE_THEN_RENAME) {
                warnNonReplacing();
            }
            if (answer.getOutcome() != CommitResult.Outcome.COMMITTED) {
                warnDeleted(answer.isOldTargetDeleted());
            }
            final Outcome outcome = answer.getOutcome() == CommitResult.Outcome.APPLICATION_ERROR
                    ? Outcome.ERROR : Outcome.valueOf(answer.getOutcome().name());
            return new Result(outcome, answer, null, null);
        } catch (final IOException | RuntimeException error) {
            if (error instanceof UnclearResultException) {
                warnDeleted(((UnclearResultException) error).isOldTargetDeleted());
            }
            return failure(error, start, CallType.CHANGE);
        } finally {
            second.end();
            first.end();
            link.end();
        }
    }

    private Result failure(final Throwable error, final long start, final CallType type) {
        return new Result(Outcome.ERROR, null, null, classify(error, start, type));
    }

    private Result lockResult(final LockOutcome lock) {
        return new Result(lock.kind == LockOutcome.Kind.LOCK_BUSY || lock.kind == LockOutcome.Kind.BUSY_LOCAL
                ? Outcome.LOCK_BUSY : Outcome.ERROR, null, lock, lock.classification);
    }

    private void discard(final LockIo io, final Temp temp, final Result result, final Runnable beforeDelete) {
        final long start = System.nanoTime();
        try {
            if (beforeDelete != null) {
                beforeDelete.run();
            }
            final FileObject file = io.resolve(temp.uri);
            if (!file.delete() && AtomicOps.existsNow(file)) {
                log.warn("The temp file was left at " + UriMask.friendly(file.getName()));
            }
        } catch (final IOException | RuntimeException error) {
            result.cleanupFailure = classify(error, start, CallType.CHANGE);
            log.warn("The temp file was left at " + UriMask.mask(temp.uri));
        }
    }

    /** The caller owns the local handle and the temp name. They can span attempts. */
    public Result commit(final LockIo io, final HeldLock held, final Temp temp, final CommitStep.Mode mode,
            final boolean enableLock, final int tries, final long gap, final boolean allowDeleteThenRename,
            final Runnable beforeDelete) {
        return commit(io, held, temp, mode, enableLock, tries, gap, allowDeleteThenRename, beforeDelete, Long.MAX_VALUE);
    }

    private Result commit(final LockIo io, final HeldLock held, final Temp temp, final CommitStep.Mode mode,
            final boolean enableLock, final int tries, final long gap, final boolean allowDeleteThenRename,
            final Runnable beforeDelete, final long remainingWaitMillis) {
        if (enableLock) {
            final LockOutcome lock = held.lockShare(io, tries, gap, remainingWaitMillis);
            if (lock.kind != LockOutcome.Kind.ACQUIRED) {
                final Result result = lockResult(lock);
                if (!result.connectionLost()) {
                    discard(io, temp, result, beforeDelete);
                }
                if (result.connectionLost() && lock.kind != LockOutcome.Kind.LOCK_BUSY) {
                    held.abandon();
                }
                return result;
            }
            if (TestPoints.enabled()) {
                TestPoints.hold("commit.after-lock-share", owner, ownerField);
            }
        }
        final Result result = publish(io, temp, mode, allowDeleteThenRename);
        if (result.outcome != Outcome.COMMITTED && !result.connectionLost()) {
            discard(io, temp, result, beforeDelete);
        }
        if (result.connectionLost()) {
            held.abandon();
            return result;
        }
        if (enableLock) {
            result.release = held.unlockShare(io);
        }
        if (result.outcome == Outcome.COMMITTED && !result.connectionLost()) {
            result.sweep = cleanup.sweep(io, temp.folder);
            log.debug("Temp files removed: " + result.sweep.removed());
        }
        if (result.connectionLost()) {
            held.abandon();
        }
        return result;
    }

    public Result write(final LockIo io, final String targetUri, final CommitStep.Mode mode, final Content content,
            final boolean enableLock, final int tries, final long gap, final Attribute... attributes) {
        final long localStart = System.nanoTime();
        final LockOutcome local = locks.enterLocalOnly(targetUri, tries, gap);
        final long localMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - localStart);
        final long gaps = Math.max(0L, tries - 1L);
        final long budget = gaps > 0 && gap > Long.MAX_VALUE / gaps ? Long.MAX_VALUE : gaps * gap;
        final long remainingWaitMillis = Math.max(0, budget - localMillis);
        if (local.kind != LockOutcome.Kind.ACQUIRED) {
            return lockResult(local);
        }
        Result result = null;
        Temp temp = null;
        long start = System.nanoTime();
        CallType type = CallType.READ;
        try {
            final FileObject target = io.resolve(targetUri);
            if (target.getType() == FileType.FOLDER) {
                final FileSystemException error = new FileSystemException("The target is a folder.");
                error.setFault(Fault.APPLICATION);
                throw error;
            }
            if (mode == CommitStep.Mode.CREATE_NEW && AtomicOps.existsNow(target)) {
                return new Result(Outcome.TARGET_EXISTS, null, null, null);
            }
            type = CallType.CHANGE;
            start = System.nanoTime();
            temp = prepare(io, targetUri);
            start = System.nanoTime();
            transfer(io, temp, content);
            for (final Attribute attribute : attributes) {
                start = System.nanoTime();
                try {
                    attribute.apply(io.resolve(temp.uri));
                } catch (final IOException | RuntimeException error) {
                    final Classification fault = classify(error, start, type);
                    if (fault.getKind().isTransport()) {
                        result = new Result(Outcome.ERROR, null, null, fault);
                        return result;
                    }
                    log.warn("The temp file metadata could not be set at " + UriMask.mask(temp.uri));
                }
            }
            result = commit(io, local.handle, temp, mode, enableLock, tries, gap, true, null, remainingWaitMillis);
            return result;
        } catch (final IOException | RuntimeException error) {
            result = failure(error, start, type);
            if (temp != null && !result.connectionLost()) {
                discard(io, temp, result, null);
            }
            return result;
        } finally {
            if (temp != null) {
                temp.close();
            }
            if (result != null && result.connectionLost()) {
                local.handle.abandon();
            } else {
                local.handle.releaseChecked(io);
            }
        }
    }
}
