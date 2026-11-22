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

import java.util.List;

/** The reply of a commit and the last atomic operation that decided it. */
public final class CommitResult {
    public enum Outcome { COMMITTED, TARGET_EXISTS, REPLACE_NOT_POSSIBLE, APPLICATION_ERROR }

    public enum Method { LINK, REPLACING_RENAME, CHECK_THEN_RENAME, DELETE_THEN_RENAME }

    private final Outcome outcome;
    private final Method method;
    private final AtomicResult result;
    private final boolean oldTargetDeleted;

    CommitResult(final Outcome outcome, final Method method, final AtomicResult result, final boolean oldTargetDeleted) {
        this.outcome = outcome;
        this.method = method;
        this.result = result;
        this.oldTargetDeleted = oldTargetDeleted;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Method getMethod() {
        return method;
    }

    public AtomicResult getAtomicResult() {
        return result;
    }

    public boolean isOldTargetDeleted() {
        return oldTargetDeleted;
    }

    public List<String> getLeftovers() {
        return result.getLeftovers();
    }

    public boolean isConnectionLost() {
        return result.isConnectionLost();
    }
}
