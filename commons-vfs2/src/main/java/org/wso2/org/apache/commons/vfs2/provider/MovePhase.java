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

/**
 * How far a move or a commit came before it failed. Each exception of a move or a commit carries its phase.
 */
public enum MovePhase {

    /**
     * The rename or the link of the source. After a definite failure nothing was moved. After an unclear result the
     * rename may have worked.
     */
    RENAME,

    /**
     * The copy of the source to a temp file, or the time set on the temp file. The target was not touched. A temp
     * file can be left.
     */
    COPY,

    /**
     * The commit of the temp file to the target. After a definite failure the target is not touched, or it is still
     * the old one. After an unclear result the commit may have worked. The source is still in place.
     */
    COMMIT,

    /**
     * On a server that does not replace, the old target was deleted, and the rename of the temp file may have
     * worked.
     */
    AFTER_DELETE,

    /**
     * The delete of the source. The target is complete. After a definite failure the source is still in place. After
     * an unclear result the delete of the source may have worked.
     */
    DELETE_SOURCE;

    /**
     * Tells whether a reply of the server confirmed that the target is complete. That holds only in the phase
     * {@link #DELETE_SOURCE}.
     *
     * @return true when the target is complete.
     */
    public boolean isTargetComplete() {
        return this == DELETE_SOURCE;
    }
}
