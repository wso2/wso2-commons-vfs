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

import org.wso2.vfs.shared.error.Classification;

public final class DigestResult {
    public enum Kind { DONE, GONE, TRANSPORT_ERROR, ERROR }
    public final Kind kind;
    public final String algorithm;
    public final String digest;
    public final long count;
    public final Classification failure;

    DigestResult(Kind kind, String digest, long count, Classification failure) {
        this.kind = kind;
        this.algorithm = kind == Kind.DONE ? "SHA-256" : null;
        this.digest = digest;
        this.count = count;
        this.failure = failure;
    }
}
