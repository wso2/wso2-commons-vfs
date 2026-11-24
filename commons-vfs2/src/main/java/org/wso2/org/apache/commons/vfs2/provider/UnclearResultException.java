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

import java.util.Objects;

import org.wso2.org.apache.commons.vfs2.FileSystemException;

/**
 * Thrown when a call that changes the server ended with a transport error or an interrupt before its reply. The
 * result is not known. The call may have taken effect on the server. The client or the channel was discarded before
 * this exception was thrown, so no further call is made on it. It carries the fault {@link Fault#TRANSPORT}, the
 * phase that the call reached, the temp path of the attempt and whether an old target was deleted.
 */
public class UnclearResultException extends FileSystemException {

    /**
     * serialVersionUID format is YYYYMMDD for the date of the last binary change.
     */
    private static final long serialVersionUID = 20261118L;

    private final MovePhase phase;

    private final String tempPath;

    private final boolean oldTargetDeleted;

    /**
     * Constructs the exception.
     *
     * @param code the error code of the message.
     * @param cause the transport error or the interrupt that ended the call.
     * @param phase the phase that the call reached.
     * @param tempPath the exact path of the temp file of this attempt, or null when the call had no temp file.
     * @param oldTargetDeleted true when an old target was deleted in this attempt.
     * @param info array of complementary info (context).
     */
    public UnclearResultException(final String code, final Throwable cause, final MovePhase phase,
            final String tempPath, final boolean oldTargetDeleted, final Object... info) {
        super(code, cause, info);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.tempPath = tempPath;
        this.oldTargetDeleted = oldTargetDeleted;
        setFault(Fault.TRANSPORT);
    }

    /**
     * Gets the phase that the call reached.
     *
     * @return the phase.
     */
    public MovePhase getPhase() {
        return phase;
    }

    /**
     * Gets the exact path of the temp file of this attempt. The path is given whether or not the temp file is still
     * there.
     *
     * @return the path, or null when the call had no temp file.
     */
    public String getTempPath() {
        return tempPath;
    }

    /**
     * Tells whether a reply of the server confirmed that the target is complete. It is true exactly in the phase
     * {@link MovePhase#DELETE_SOURCE}.
     *
     * @return true when the target is complete.
     */
    public boolean isCommitConfirmed() {
        return phase.isTargetComplete();
    }

    /**
     * Tells whether an old target was deleted in this attempt. That happens only on a server that is known not to
     * replace on a rename.
     *
     * @return true when an old target was deleted.
     */
    public boolean isOldTargetDeleted() {
        return oldTargetDeleted;
    }
}
