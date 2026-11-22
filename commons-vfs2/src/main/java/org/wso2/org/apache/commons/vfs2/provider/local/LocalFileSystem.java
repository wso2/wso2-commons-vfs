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
package org.wso2.org.apache.commons.vfs2.provider.local;

import java.io.File;
import java.io.FilePermission;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSelector;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

/**
 * A local file system.
 */
public class LocalFileSystem extends AbstractFileSystem {

    // A result of the mount test counts only when the last counted one is at least this old.
    static final long COUNT_GAP_NANOS = TimeUnit.SECONDS.toNanos(10);

    // The count of results that marks a folder pair "no link".
    static final int MARK_COUNT = 3;

    // How long a "no link" mark holds.
    static final long NO_LINK_NANOS = TimeUnit.MINUTES.toNanos(10);

    // The link state of one folder pair, by the memory rule of a mount. Every time is on the monotonic clock. The
    // calls on the mount run outside its monitor.
    static final class LinkMemory {

        private boolean links;

        private int count;

        private long lastCountedNanos;

        private boolean marked;

        private long markEndNanos;

        synchronized boolean isLinked() {
            return links;
        }

        // True while the "no link" mark holds.
        synchronized boolean isMarked(final long nowNanos) {
            return !links && marked && markEndNanos - nowNanos > 0;
        }

        // True after a mark that has ended, when the next failed link is checked once with no count.
        synchronized boolean isAfterMark() {
            return !links && marked;
        }

        // A link that works marks the pair "links" and clears the count. It is never taken back.
        synchronized void markLinks() {
            links = true;
            count = 0;
        }

        // Counts one result of the mount test. True when this result sets the first mark, which logs the one WARN.
        synchronized boolean count(final long nowNanos) {
            if (links) {
                return false;
            }
            if (count > 0 && nowNanos - lastCountedNanos < COUNT_GAP_NANOS) {
                return false;
            }
            count++;
            lastCountedNanos = nowNanos;
            if (count < MARK_COUNT) {
                return false;
            }
            final boolean first = !marked;
            mark(nowNanos);
            return first;
        }

        // Marks the pair "no link" for 10 more minutes at once, with no count.
        synchronized void mark(final long nowNanos) {
            if (!links) {
                marked = true;
                markEndNanos = nowNanos + NO_LINK_NANOS;
            }
        }
    }

    // The link memory per folder pair, the source folder and the target folder.
    private final ConcurrentMap<String, LinkMemory> linkMemory = new ConcurrentHashMap<>();

    private final String rootFile;

    // The memory of a pair that an observation is recorded for.
    LinkMemory getLinkMemory(final String pair) {
        return linkMemory.computeIfAbsent(pair, key -> new LinkMemory());
    }

    // The memory of a pair, or null when nothing was observed for it. A read adds no entry.
    LinkMemory findLinkMemory(final String pair) {
        return linkMemory.get(pair);
    }

    /**
     * Constructs a new instance.
     *
     * @param rootFileName The root file name of this file system.
     * @param rootFile The root of this file system.
     * @param fileSystemOptions Options to build this file system.
     */
    public LocalFileSystem(final FileName rootFileName, final String rootFile, final FileSystemOptions fileSystemOptions) {
        super(rootFileName, null, fileSystemOptions);
        this.rootFile = rootFile;
    }

    /**
     * Returns the capabilities of this file system.
     */
    @Override
    protected void addCapabilities(final Collection<Capability> caps) {
        caps.addAll(DefaultLocalFileProvider.capabilities);
    }

    /**
     * Creates a file object.
     */
    @Override
    protected FileObject createFile(final AbstractFileName name) throws FileSystemException {
        // Create the file
        return new LocalFile(this, rootFile, name);
    }

    /**
     * Creates a temporary local copy of a file and its descendants.
     */
    @Override
    protected File doReplicateFile(final FileObject fileObject, final FileSelector selector) throws Exception {
        final LocalFile localFile = (LocalFile) FileObjectUtils.getAbstractFileObject(fileObject);
        final File file = localFile.getLocalFile();
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new FilePermission(file.getAbsolutePath(), "read"));
        }
        return file;
    }

}
