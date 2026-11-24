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
package org.wso2.vfs.shared.failstate;

import java.util.Objects;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;

/** Metadata read under the file lock. The content stream supplies the digest at EOF. */
public final class FileIdentity {
    public enum TimeSource { COMMAND, LISTING }

    public final String uri;
    public final long size;
    public final long mtime;
    public final TimeSource mtimeSource;
    private String digest;

    public FileIdentity(String uri, long size, long mtime, TimeSource source, String digest) {
        this.uri = uri;
        this.size = size;
        this.mtime = mtime;
        this.mtimeSource = Objects.requireNonNull(source);
        this.digest = digest;
    }

    static FileIdentity from(String uri, StatNow stat) {
        return new FileIdentity(uri, stat.getSize(), stat.getLastModifiedMillis(),
                stat.isFromListing() ? TimeSource.LISTING : TimeSource.COMMAND, null);
    }

    public String digest() {
        return digest;
    }

    void digest(String value) {
        digest = value;
    }

    boolean readable() {
        return size >= 0 && mtime != 0;
    }

    boolean changed(FileIdentity other) {
        return size != other.size || mtimeSource == other.mtimeSource && mtime != other.mtime;
    }
}
