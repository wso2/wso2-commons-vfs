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

import java.util.Objects;

/**
 * What a server and a folder can do, as {@link AtomicOps#atomicCapabilities()} answers it. The answer comes from the
 * memory of the file system object and costs no remote call. A caller counts {@link Answer#NOT_KNOWN} as no.
 */
public final class AtomicCapabilities {

    /**
     * One value of the answer.
     */
    public enum Answer {

        /**
         * The step is there.
         */
        YES,

        /**
         * The step is not there.
         */
        NO,

        /**
         * Not decided yet. It is decided at the first use, and until then it counts as no.
         */
        NOT_KNOWN
    }

    private final Answer exclusiveCreate;

    private final Answer exclusivePublish;

    private final Answer replacingRename;

    /**
     * Constructs the answer.
     *
     * @param exclusiveCreate whether a name can be created with a step that fails when the name exists.
     * @param exclusivePublish whether a finished file can be published with a step that fails when the name exists.
     * @param replacingRename whether a rename replaces an existing target in one step.
     */
    public AtomicCapabilities(final Answer exclusiveCreate, final Answer exclusivePublish,
            final Answer replacingRename) {
        this.exclusiveCreate = Objects.requireNonNull(exclusiveCreate, "exclusiveCreate");
        this.exclusivePublish = Objects.requireNonNull(exclusivePublish, "exclusivePublish");
        this.replacingRename = Objects.requireNonNull(replacingRename, "replacingRename");
    }

    /**
     * Gets whether a name can be created with a step that fails when the name exists.
     *
     * @return the answer.
     */
    public Answer getExclusiveCreate() {
        return exclusiveCreate;
    }

    /**
     * Gets whether a finished file can be published with a step that fails when the name exists.
     *
     * @return the answer.
     */
    public Answer getExclusivePublish() {
        return exclusivePublish;
    }

    /**
     * Gets whether a rename replaces an existing target in one step.
     *
     * @return the answer.
     */
    public Answer getReplacingRename() {
        return replacingRename;
    }

    @Override
    public String toString() {
        return "exclusiveCreate=" + exclusiveCreate + ", exclusivePublish=" + exclusivePublish + ", replacingRename="
                + replacingRename;
    }
}
