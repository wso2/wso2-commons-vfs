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
package org.wso2.vfs.shared.failstate;

import java.util.Objects;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.vfs.shared.error.Classification;

/** Recovery information for the action that just ended. */
public final class ActionResult {
    public enum Step { NONE, RENAME, COPY, COMMIT, AFTER_DELETE, DELETE_SOURCE }
    public enum Phase { BEFORE_ACTION, FAILED, UNCLEAR, COPIED }
    public final Step step;
    public final boolean clear;
    public final String temp;
    public final boolean committed;
    public final Phase phase;
    public final Classification failure;

    public ActionResult(Step step, boolean clear, String temp, boolean committed, Classification failure) {
        this(step, clear, temp, committed, committed ? Phase.COPIED : clear ? Phase.FAILED : Phase.UNCLEAR, failure);
    }

    ActionResult(Step step, boolean clear, String temp, boolean committed, Phase phase, Classification failure) {
        this.step = Objects.requireNonNull(step);
        this.clear = clear;
        this.temp = temp;
        this.committed = committed;
        this.phase = Objects.requireNonNull(phase);
        this.failure = failure;
    }

    public static ActionResult beforeAction(Classification failure) {
        return new ActionResult(Step.NONE, true, null, false, Phase.BEFORE_ACTION, failure);
    }

    public static ActionResult of(MoveFailedException error, Classification failure) {
        return new ActionResult(Step.valueOf(error.getPhase().name()), true, error.getTempPath(),
                error.isCommitConfirmed(), failure);
    }

    public static ActionResult of(UnclearResultException error, Classification failure) {
        return new ActionResult(Step.valueOf(error.getPhase().name()), false, error.getTempPath(),
                error.isCommitConfirmed(), failure);
    }
}
