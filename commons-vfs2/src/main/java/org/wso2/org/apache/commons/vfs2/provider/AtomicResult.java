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
package org.wso2.org.apache.commons.vfs2.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The result of a call of {@link AtomicOps} that changes the server.
 */
public final class AtomicResult {

    private final AtomicOutcome outcome;

    private final String detail;

    private final Fault fault;

    private final List<String> leftovers;

    private final boolean connectionLost;

    /**
     * Constructs a result with no leftover and a connection that is still in use.
     *
     * @param outcome the outcome.
     * @param detail the reply of the server as text, for the log.
     */
    public AtomicResult(final AtomicOutcome outcome, final String detail) {
        this(outcome, detail, Collections.emptyList(), false);
    }

    /**
     * Constructs a result.
     *
     * @param outcome the outcome.
     * @param detail the reply of the server as text, for the log.
     * @param leftovers the names that may still be on the server and that the call could not remove.
     * @param connectionLost true when a later call of the same method ended with a transport error.
     */
    public AtomicResult(final AtomicOutcome outcome, final String detail, final List<String> leftovers,
            final boolean connectionLost) {
        this(outcome, detail, leftovers, connectionLost, null);
    }

    /** Constructs a result with the classified fault of the deciding reply. */
    public AtomicResult(final AtomicOutcome outcome, final String detail, final List<String> leftovers,
            final boolean connectionLost, final Fault fault) {
        this.fault = fault;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.detail = detail;
        this.leftovers = Collections.unmodifiableList(new ArrayList<>(leftovers));
        this.connectionLost = connectionLost;
    }

    /** Returns the classified fault of the deciding reply, when supplied by the provider. */
    public Fault getFault() {
        return fault;
    }

    /**
     * Gets the outcome.
     *
     * @return the outcome.
     */
    public AtomicOutcome getOutcome() {
        return outcome;
    }

    /**
     * Gets the reply of the server as text, for the log: an NT status name, an SFTP id with its message, an FTP reply,
     * or a Java exception class with its reason.
     *
     * @return the detail, or null when the call sent nothing.
     */
    public String getDetail() {
        return detail;
    }

    /**
     * Gets the names that may still be on the server and that the call could not remove.
     *
     * @return the names, never null.
     */
    public List<String> getLeftovers() {
        return leftovers;
    }

    /**
     * Tells whether the reply that decided the outcome came, and a later call of the same method ended with a
     * transport error. The outcome is still valid. The connection was discarded.
     *
     * @return true when the connection was lost after the deciding reply.
     */
    public boolean isConnectionLost() {
        return connectionLost;
    }

    @Override
    public String toString() {
        return outcome + (detail == null ? "" : " (" + detail + ")")
                + (leftovers.isEmpty() ? "" : " leftovers=" + leftovers) + (connectionLost ? " connectionLost" : "");
    }
}
