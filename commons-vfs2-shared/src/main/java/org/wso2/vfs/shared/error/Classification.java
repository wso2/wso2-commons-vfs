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
package org.wso2.vfs.shared.error;

import org.wso2.org.apache.commons.vfs2.provider.MovePhase;

public final class Classification {
    private final ErrorKind kind;
    private final boolean fast;
    private final String statusText;
    private final Throwable cause;
    private final MovePhase phase;
    private final String tempPath;
    private final boolean commitConfirmed;

    Classification(final ErrorKind kind, final boolean fast, final String statusText, final Throwable cause,
            final MovePhase phase, final String tempPath, final boolean commitConfirmed) {
        this.kind = kind;
        this.fast = fast;
        this.statusText = statusText;
        this.cause = cause;
        this.phase = phase;
        this.tempPath = tempPath;
        this.commitConfirmed = commitConfirmed;
    }

    /**
     * A refusal that came as a reply of the server, with the text that says why.
     *
     * @param statusText the text, with no password in it.
     * @param fast whether the reply came in under 5 s.
     * @return the classification of kind {@link ErrorKind#APPLICATION}.
     */
    public static Classification application(final String statusText, final boolean fast) {
        return new Classification(ErrorKind.APPLICATION, fast, statusText, null, null, null, false);
    }

    public ErrorKind getKind() {
        return kind;
    }

    public boolean isFast() {
        return fast;
    }

    public String getStatusText() {
        return statusText;
    }

    public Throwable getCause() {
        return cause;
    }

    public MovePhase getPhase() {
        return phase;
    }

    public String getTempPath() {
        return tempPath;
    }

    public boolean isCommitConfirmed() {
        return commitConfirmed;
    }
}
