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

import org.wso2.org.apache.commons.vfs2.FileSystemException;

/** A definite failure of a move. */
public final class MoveFailedException extends FileSystemException {
    private static final long serialVersionUID = 20261121L;

    private final MovePhase phase;
    private final String tempPath;
    private final boolean oldTargetDeleted;
    private final boolean targetExists;
    private final boolean connectionLost;

    MoveFailedException(final String source, final String target, final Throwable cause, final Fault fault,
            final MovePhase phase, final String tempPath, final boolean oldTargetDeleted, final boolean targetExists,
            final boolean connectionLost) {
        super("vfs.provider/rename.error", cause, source, target);
        setFault(fault);
        this.phase = phase;
        this.tempPath = tempPath;
        this.oldTargetDeleted = oldTargetDeleted;
        this.targetExists = targetExists;
        this.connectionLost = connectionLost;
    }

    public MovePhase getPhase() {
        return phase;
    }

    public String getTempPath() {
        return tempPath;
    }

    public boolean isCommitConfirmed() {
        return phase.isTargetComplete();
    }

    public boolean isOldTargetDeleted() {
        return oldTargetDeleted;
    }

    public boolean isTargetExists() {
        return targetExists;
    }

    public boolean isConnectionLost() {
        return connectionLost;
    }
}
