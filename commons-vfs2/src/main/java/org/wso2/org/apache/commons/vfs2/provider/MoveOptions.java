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

import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

/** Options shared by the rename and copy paths of a move. */
public final class MoveOptions {
    private final boolean replaceExisting;
    private final FileObject tempFile;
    private final String probeName;
    private final FileObject probeA;
    private final FileObject probeB;

    public MoveOptions(final boolean replaceExisting, final FileObject tempFile, final String probeName,
            final FileObject probeA, final FileObject probeB) {
        this.replaceExisting = replaceExisting;
        this.tempFile = tempFile;
        this.probeName = probeName;
        this.probeA = probeA;
        this.probeB = probeB;
    }

    public boolean isReplaceExisting() {
        return replaceExisting;
    }

    public FileObject getTempFile() {
        return tempFile;
    }

    String probeName(final FileObject target, final FileObject temp) {
        if (probeName != null) {
            return probeName;
        }
        final String suffix = CommitStep.suffix(temp, target);
        return CommitStep.tempName(target.getName().getBaseName(), suffix == null ? ".part" : suffix);
    }

    CommitStep.Options commitOptions() {
        return new CommitStep.Options(true, probeName, probeA, probeB);
    }

    public boolean needsTargetLock(final FileObject source, final FileObject target) throws FileSystemException {
        if (tempFile == null || !FileObjectUtils.isInstanceOf(source, AbstractFileObject.class)
                || FileObjectUtils.getAbstractFileObject(source).observedType() != FileType.FILE) {
            return true;
        }
        return !canPublish(source, target) || !canPublish(tempFile, target);
    }

    private static boolean canPublish(final FileObject file, final FileObject target) throws FileSystemException {
        if (file.getFileSystem() != target.getFileSystem()) {
            return false;
        }
        final AtomicOps ops = AtomicOps.of(file);
        return ops != null && ops.atomicCapabilities(target).getExclusivePublish() == AtomicCapabilities.Answer.YES;
    }
}
