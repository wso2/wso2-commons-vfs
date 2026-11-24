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

import java.util.Map;

import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

/**
 * The calls that the lock, the stale takeover, the commit of a write and a move that must not replace need. Each call
 * that changes the server maps to one library call, with a look, a probe or a cleanup around it where the provider
 * needs one. The SFTP, SMB2, FTP and FTPS file objects and the local file implement it. No call repeats itself, and
 * none falls back to another call by itself.
 */
public interface AtomicOps {

    /**
     * Gets the calls of a file object.
     *
     * @param file the file object, also a decorated one.
     * @return the calls, or null for a provider that has none.
     * @throws FileSystemException if the file object can not be unwrapped.
     */
    static AtomicOps of(final FileObject file) throws FileSystemException {
        final AbstractFileObject<?> inner = FileObjectUtils.getAbstractFileObject(file);
        return inner instanceof AtomicOps ? (AtomicOps) inner : null;
    }

    /**
     * Looks at a name on the server, for every provider. With {@link AtomicOps} it is {@link #existsNow()}. Else it
     * is a refresh and then an exists, with whatever that provider reports.
     *
     * @param file the file object.
     * @return true when the name is there.
     * @throws FileSystemException if the look fails.
     */
    static boolean existsNow(final FileObject file) throws FileSystemException {
        final AtomicOps ops = of(file);
        if (ops != null) {
            return ops.existsNow();
        }
        file.refresh();
        return file.exists();
    }

    /**
     * Tells what this server and the folder of this name can do. It makes no remote call.
     *
     * @return the answer.
     */
    AtomicCapabilities atomicCapabilities();

    /**
     * Tells what this server can do for the pair of the folder of this name and the folder of a target. It makes no
     * remote call.
     *
     * @param target a file object in the target folder, on the same file system.
     * @return the answer. The exclusive publish is yes only when a link from the first folder into the second one
     *         worked on this file system object.
     */
    AtomicCapabilities atomicCapabilities(FileObject target);

    /**
     * Looks at this name on the server. It uses no cached state and reads no content.
     *
     * @return true when the name is there, false only when the server said that the name is not there.
     * @throws FileSystemException for every other failure, with its fault.
     */
    boolean existsNow() throws FileSystemException;

    /**
     * Looks whether this folder is there and can be entered.
     *
     * @return true for a folder that is there, false when the server said that the name is not there.
     * @throws FileSystemException for a name that is a file, and for every other failure, with its fault.
     */
    boolean folderExistsNow() throws FileSystemException;

    /**
     * Lists a folder just confirmed by folderExistsNow, using a fresh listing of that folder.
     * @param folder the file object from which these calls were obtained.
     * @param listedSizes receives available regular file sizes from this listing, or -1 for a known nonfile.
     * @return the children in the completed listing.
     * @throws FileSystemException when the listing fails.
     */
    default FileObject[] listConfirmedFolder(final FileObject folder, final Map<FileName, Long> listedSizes)
            throws FileSystemException {
        folder.refresh();
        return folder.getChildren();
    }

    /**
     * Looks at this name on the server and reads its size and its modified time in one answer.
     *
     * @return the answer.
     * @throws FileSystemException for every failure that is not a definite "not there", with its fault.
     */
    StatNow statNow() throws FileSystemException;

    /**
     * Creates this name with the content. Fails when the name exists.
     *
     * @param content the content.
     * @param scratchName a base name in the same folder, used only by SFTP.
     * @param probeName a base name in the same folder, used by the link test.
     * @return the result.
     * @throws FileSystemException when the call ended with no reply that decides it.
     */
    AtomicResult createExclusive(byte[] content, String scratchName, String probeName) throws FileSystemException;

    /** A marked SFTP caller can pause after the scratch write and before its link. */
    default AtomicResult createExclusive(final byte[] content, final String scratchName, final String probeName,
            final Runnable beforeLink) throws FileSystemException {
        return createExclusive(content, scratchName, probeName);
    }

    /**
     * Makes this finished file appear under the target name as a second name. Fails when the target exists. It never
     * removes a name.
     *
     * @param target the target, on the same file system.
     * @param probeName a base name in the target folder, used by the link test.
     * @return the result.
     * @throws FileSystemException when the call ended with no reply that decides it.
     */
    AtomicResult linkExclusive(FileObject target, String probeName) throws FileSystemException;

    /**
     * Links this temp file to the target as {@link #linkExclusive(FileObject, String)} does, and after that worked
     * removes the temp name. A failed removal is a leftover and not a failure. It is only for a temp file.
     *
     * @param target the target, on the same file system.
     * @param probeName a base name in the target folder, used by the link test.
     * @return the result.
     * @throws FileSystemException when the call ended with no reply that decides it.
     */
    AtomicResult publishExclusive(FileObject target, String probeName) throws FileSystemException;

    /**
     * Renames this temp file onto the target and replaces an existing target in one step.
     *
     * @param target the target, on the same file system.
     * @param probeA the first probe file of the replace test, or null.
     * @param probeB the second probe file of the replace test, in the folder of the first, or null.
     * @return the result.
     * @throws FileSystemException when the call ended with no reply that decides it.
     */
    AtomicResult renameReplacing(FileObject target, FileObject probeA, FileObject probeB)
            throws FileSystemException;

    /**
     * Renames this name away. Of several callers exactly one succeeds.
     *
     * @param awayTarget a file object with a unique name in the same folder.
     * @return the result.
     * @throws FileSystemException when the call ended with no reply that decides it.
     */
    AtomicResult takeAway(FileObject awayTarget) throws FileSystemException;
}
