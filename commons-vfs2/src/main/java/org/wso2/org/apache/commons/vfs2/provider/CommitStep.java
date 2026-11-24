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
package org.wso2.org.apache.commons.vfs2.provider;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/** Publishes a finished file in the target file system. */
public final class CommitStep {
    public enum Mode { CREATE_NEW, OVERWRITE }

    public static final class Options {
        private final boolean allowDeleteThenRename;
        private final String probeName;
        private final FileObject probeA;
        private final FileObject probeB;

        public Options(final boolean allowDeleteThenRename, final String probeName,
                final FileObject probeA, final FileObject probeB) {
            this.allowDeleteThenRename = allowDeleteThenRename;
            this.probeName = probeName;
            this.probeA = probeA;
            this.probeB = probeB;
        }
    }

    private static final Log LOG = LogFactory.getLog(CommitStep.class);
    private static final long TOKEN_RANGE = 78364164096L; // 36 to the seventh power.

    private CommitStep() {
    }

    static String tempName(final String base, final String suffix) {
        final long seconds = System.currentTimeMillis() / 1000;
        final long random = ThreadLocalRandom.current().nextLong(TOKEN_RANGE);
        return base + "." + tokenPart(seconds) + tokenPart(random) + suffix;
    }

    private static String tokenPart(final long value) {
        return String.format(Locale.ROOT, "%7s", Long.toString(value, 36)).replace(' ', '0');
    }

    static String pointKey(final FileObject file) {
        if (!TestPoints.enabled()) {
            return null;
        }
        return file.getName().getFriendlyURI().replaceFirst("(://)[^/?#]*@", "$1").split("[?#]", 2)[0];
    }

    static FileObject sibling(final FileObject file, final String name) throws FileSystemException {
        return file.getParent().resolveFile(name);
    }

    static String suffix(final FileObject temp, final FileObject target) {
        final String base = target.getName().getBaseName();
        final String name = temp.getName().getBaseName();
        final int start = base.length() + 1;
        final int end = start + 14;
        if (!name.startsWith(base + ".") || name.length() <= end || !name.substring(start, end).matches("[0-9a-z]{14}")) {
            return null;
        }
        return name.substring(end);
    }

    static AtomicResult replace(final AtomicOps ops, final FileObject temp, final FileObject target,
            final Options options) throws FileSystemException {
        if (!options.allowDeleteThenRename) {
            return ops.renameReplacing(target, null, null);
        }
        if (options.probeA != null || options.probeB != null) {
            return ops.renameReplacing(target, options.probeA, options.probeB);
        }
        final String suffix = suffix(temp, target);
        if (suffix == null) {
            return ops.renameReplacing(target, null, null);
        }
        final String base = target.getName().getBaseName();
        final FileObject first = sibling(temp, tempName(base, suffix));
        final FileObject second = sibling(temp, tempName(base, suffix));
        return ops.renameReplacing(target, first, second);
    }

    public static CommitResult commit(final FileObject temp, final FileObject target, final Mode mode,
            final Options options) throws FileSystemException {
        return commit(temp, target, mode, options, pointKey(temp));
    }

    static CommitResult commit(final FileObject temp, final FileObject target, final Mode mode,
            final Options options, final String sourceKey) throws FileSystemException {
        Objects.requireNonNull(mode, "mode");
        final AtomicOps ops = AtomicOps.of(temp);
        if (ops == null || AtomicOps.of(target) == null || temp.getFileSystem() != target.getFileSystem()) {
            return error(new AtomicResult(AtomicOutcome.UNSUPPORTED, "Atomic commit is unavailable"), false);
        }
        if (FileObjectUtils.getAbstractFileObject(temp).entryRelation(target) != AbstractFileObject.EntryRelation.DISTINCT) {
            return error(new AtomicResult(AtomicOutcome.REFUSED, "The temp file is the target"), false);
        }
        boolean deleted = false;
        final boolean create = mode == Mode.CREATE_NEW;
        try {
            AtomicResult reply;
            CommitResult.Method method;
            if (create) {
                final String suffix = suffix(temp, target);
                final String name = options.probeName == null
                        ? tempName(target.getName().getBaseName(), suffix == null ? ".part" : suffix) : options.probeName;
                reply = ops.publishExclusive(target, name);
                method = CommitResult.Method.LINK;
            } else {
                reply = replace(ops, temp, target, options);
                method = CommitResult.Method.REPLACING_RENAME;
            }
            if (reply.getOutcome() == AtomicOutcome.UNSUPPORTED && !reply.isConnectionLost()) {
                if (!create && !options.allowDeleteThenRename) {
                    return new CommitResult(CommitResult.Outcome.REPLACE_NOT_POSSIBLE, null, reply, false);
                }
                if (AtomicOps.existsNow(target)) {
                    if (create) {
                        return new CommitResult(CommitResult.Outcome.TARGET_EXISTS, null,
                                new AtomicResult(AtomicOutcome.EXISTS, null), false);
                    }
                    deleted = deleteObservedTarget(target);
                    if (!deleted) {
                        return error(new AtomicResult(AtomicOutcome.REFUSED, "Target delete returned false"), false);
                    }
                }
                if (create) {
                    TestPoints.hold("move.no-replace.after-look", sourceKey);
                }
                reply = ops.takeAway(target);
                method = create ? CommitResult.Method.CHECK_THEN_RENAME : CommitResult.Method.DELETE_THEN_RENAME;
            }
            if (reply.getOutcome() == AtomicOutcome.DONE) {
                detach(temp);
                detach(target);
                return new CommitResult(CommitResult.Outcome.COMMITTED, method, reply, deleted);
            }
            if (create && reply.getOutcome() == AtomicOutcome.EXISTS) {
                return new CommitResult(CommitResult.Outcome.TARGET_EXISTS, null, reply, deleted);
            }
            return error(reply, deleted);
        } catch (final FileSystemException | RuntimeException e) {
            final boolean confirmed = AbstractFileObject.confirmedRemoval(e, target);
            if (e instanceof RuntimeException && !confirmed) {
                throw (RuntimeException) e;
            }
            deleted |= confirmed;
            final Fault fault = Faults.of(e);
            if (fault == Fault.TRANSPORT) {
                throw unclear(e, deleted ? MovePhase.AFTER_DELETE : MovePhase.COMMIT,
                        temp.getName().getPath(), deleted, "commit", target);
            }
            return error(new AtomicResult(outcome(fault), e.getMessage()), deleted);
        }
    }

    static UnclearResultException unclear(final Throwable cause, final MovePhase phase, final String tempPath,
            final boolean deleted, final String step, final FileObject file) {
        Throwable original = cause;
        if (cause instanceof UnclearResultException) {
            final UnclearResultException previous = (UnclearResultException) cause;
            if (previous.getPhase() == phase && Objects.equals(previous.getTempPath(), tempPath)
                    && previous.isOldTargetDeleted() == deleted) {
                return previous;
            }
            if (previous.getCause() != null) {
                original = previous.getCause();
            }
        }
        return new UnclearResultException("vfs.provider/atomic-not-known.error", original, phase, tempPath, deleted,
                step, file.getName().getFriendlyURI());
    }

    private static CommitResult error(final AtomicResult result, final boolean deleted) {
        return new CommitResult(CommitResult.Outcome.APPLICATION_ERROR, null, result, deleted);
    }

    private static AtomicOutcome outcome(final Fault fault) {
        switch (fault) {
        case MISSING:
            return AtomicOutcome.GONE;
        case BUSY:
            return AtomicOutcome.BUSY;
        default:
            return AtomicOutcome.REFUSED;
        }
    }

    static boolean deleteObservedTarget(final FileObject target) throws FileSystemException {
        FileObjectUtils.getAbstractFileObject(target).detachAfterCommit();
        return target.delete();
    }

    private static void detach(final FileObject file) {
        try {
            FileObjectUtils.getAbstractFileObject(file).detachAfterCommit();
        } catch (final Exception e) {
            LOG.warn("Could not clear the cached file after the commit", e);
        }
    }
}
