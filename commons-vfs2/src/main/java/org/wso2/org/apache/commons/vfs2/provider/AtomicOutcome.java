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
 * The outcome of a call of {@link AtomicOps} that changes the server.
 */
public enum AtomicOutcome {

    /**
     * The server confirmed the step.
     */
    DONE,

    /**
     * Refused because the target name exists. Nothing changed.
     */
    EXISTS,

    /**
     * The source name is not there. For a take away it means that the caller lost the takeover.
     */
    GONE,

    /**
     * The server holds the name open for someone else. For a take away it means that the caller lost the takeover.
     * Only SMB2 gives it.
     */
    BUSY,

    /**
     * This provider, this server or this folder has no such step. Nothing changed. For a replacing rename it means
     * that the server is known not to replace on a rename.
     */
    UNSUPPORTED,

    /**
     * The server refused, for another reason or for a reason that is not known. Nothing changed, the target is kept,
     * and nothing is remembered from it.
     */
    REFUSED
}
