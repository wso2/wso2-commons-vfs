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
package org.wso2.vfs.shared.error;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;

import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.UriMask;

public final class ErrorClassifier {
    public enum CallType {
        READ, CHANGE
    }

    private final NamedLog log;

    public ErrorClassifier(final NamedLog log) {
        this.log = log;
    }

    public Classification classify(final Throwable error, final long elapsedMillis, final CallType call) {
        final boolean fast = elapsedMillis < 5000;
        final Fault fault = Faults.match(error);
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable last = error;
        final StringJoiner messages = new StringJoiner(" caused by ");
        final StringJoiner chain = new StringJoiner(" caused by ");
        Throwable move = null;
        for (Throwable link = error; link != null && seen.add(link); link = link.getCause()) {
            if (move == null && (link instanceof MoveFailedException || link instanceof UnclearResultException)) {
                move = link;
            }
            final String message = link.getMessage();
            if (message != null && !message.isEmpty()) {
                messages.add(UriMask.mask(message));
            }
            if (fault == null) {
                chain.add(link.getClass().getName() + ": " + message);
                last = link;
            }
        }
        final String status = messages.length() == 0 ? null : messages.toString();
        if (move instanceof UnclearResultException) {
            final UnclearResultException unclear = (UnclearResultException) move;
            return new Classification(ErrorKind.UNCLEAR, fast, status, error, unclear.getPhase(),
                    unclear.getTempPath(), unclear.isCommitConfirmed());
        }
        if (move instanceof MoveFailedException) {
            final MoveFailedException failed = (MoveFailedException) move;
            return new Classification(ErrorKind.valueOf(failed.getFault().name()), fast, status, error,
                    failed.getPhase(), failed.getTempPath(), failed.isCommitConfirmed());
        }
        final ErrorKind kind;
        if (fault == null) {
            kind = ErrorKind.APPLICATION;
            log.warnUnknown(last.getClass(), chain.toString());
        } else if (fault == Fault.TRANSPORT && call == CallType.CHANGE) {
            kind = ErrorKind.UNCLEAR;
        } else {
            kind = ErrorKind.valueOf(fault.name());
        }
        return new Classification(kind, fast, status, error, null, null, false);
    }
}
