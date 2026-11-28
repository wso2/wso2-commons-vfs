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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.wso2.org.apache.commons.vfs2.FileSystemException;

/**
 * What VFS expects from an FTP client to provide.
 */
public interface FtpClient {

    /**
     * One reply of the server.
     */
    final class Reply {

        private final int code;

        private final String text;

        /**
         * Constructs a reply.
         *
         * @param code the reply code.
         * @param text the reply text, or for a reply 213 to SIZE or MDTM the value after the code.
         */
        public Reply(final int code, final String text) {
            this.code = code;
            this.text = text;
        }

        /**
         * Gets the reply code.
         *
         * @return the reply code.
         */
        public int getCode() {
            return code;
        }

        /**
         * Gets the reply text, or for a reply 213 to SIZE or MDTM the value after the code.
         *
         * @return the text.
         */
        public String getText() {
            return text;
        }

        @Override
        public String toString() {
            return code + " " + text;
        }
    }

    /**
     * The replies of a rename in two steps.
     */
    final class RenameReplies {

        private final Reply from;

        private final Reply to;

        /**
         * Constructs the replies.
         *
         * @param from the reply to RNFR.
         * @param to the reply to RNTO, or null when RNTO was not sent.
         */
        public RenameReplies(final Reply from, final Reply to) {
            this.from = from;
            this.to = to;
        }

        /**
         * Gets the reply to RNFR.
         *
         * @return the reply.
         */
        public Reply getFrom() {
            return from;
        }

        /**
         * Gets the reply to RNTO.
         *
         * @return the reply, or null when RNTO was not sent because RNFR was refused.
         */
        public Reply getTo() {
            return to;
        }
    }

    /**
     * A listing whose final reply was read.
     */
    final class Listing {

        private final FTPFile[] entries;

        private final Reply failure;

        private final boolean entered;

        private final boolean backRefused;

        /**
         * Constructs a listing.
         *
         * @param entries the entries of a listing that ended with a good reply, or null.
         * @param failure the reply of a listing that failed in both forms, or null.
         * @param entered true when the second form changed into the folder.
         * @param backRefused true when the change back to the first folder was refused.
         */
        public Listing(final FTPFile[] entries, final Reply failure, final boolean entered,
                final boolean backRefused) {
            this.entries = entries;
            this.failure = failure;
            this.entered = entered;
            this.backRefused = backRefused;
        }

        /**
         * Gets the entries of a listing that ended with a good reply.
         *
         * @return the entries, or null when the listing failed or the folder can not be entered.
         */
        public FTPFile[] getEntries() {
            return entries;
        }

        /**
         * Gets the reply of a listing that failed in both forms.
         *
         * @return the reply, or null when the listing ended with a good reply or the folder can not be entered.
         */
        public Reply getFailure() {
            return failure;
        }

        /**
         * Tells whether the second form changed into the folder.
         *
         * @return true when the folder was entered.
         */
        public boolean isEntered() {
            return entered;
        }

        /**
         * Tells whether the change back to the first folder was refused. Such a client is not used again.
         *
         * @return true when the change back was refused.
         */
        public boolean isBackRefused() {
            return backRefused;
        }
    }

    /**
     * A change into a folder and back.
     */
    final class Change {

        private final Reply reply;

        private final boolean entered;

        private final boolean backRefused;

        /**
         * Constructs a change.
         *
         * @param reply the reply to the change into the folder.
         * @param entered true when the change into the folder worked.
         * @param backRefused true when the change back to the first folder was refused.
         */
        public Change(final Reply reply, final boolean entered, final boolean backRefused) {
            this.reply = reply;
            this.entered = entered;
            this.backRefused = backRefused;
        }

        /**
         * Gets the reply to the change into the folder.
         *
         * @return the reply.
         */
        public Reply getReply() {
            return reply;
        }

        /**
         * Tells whether the change into the folder worked.
         *
         * @return true when the folder was entered.
         */
        public boolean isEntered() {
            return entered;
        }

        /**
         * Tells whether the change back to the first folder was refused. Such a client is not used again.
         *
         * @return true when the change back was refused.
         */
        public boolean isBackRefused() {
            return backRefused;
        }
    }

    /**
     * Aborts the current operation.
     *
     * @return true if aborted.
     * @throws IOException If an I/O error occurs
     */
    boolean abort() throws IOException;

    /**
     * Closes the sockets of the client at once. It sends no command and waits for no reply, so any thread may call it.
     * A client after this call is not handed out again.
     */
    default void abortNow() {
        // A client with no socket group has nothing to close here.
    }

    /**
     * Tells from local state whether the client looks alive. It makes no remote call.
     *
     * @return true when the client is connected and was not aborted.
     */
    default boolean isAliveLocally() {
        try {
            return isConnected();
        } catch (final FileSystemException e) {
            return false;
        }
    }

    /**
     * Tells whether an idle client can still be used. The caller holds the client, so no command runs on it. It sends
     * nothing to the server.
     *
     * @return true when the client looks alive.
     */
    default boolean probeIdle() {
        return isAliveLocally();
    }

    /**
     * Returns an OutputStream through which data can be written to append to a file on the server with the given name.
     *
     * @param relPath The name of the remote file.
     * @return An OutputStream through which the remote file can be appended.
     * @throws IOException If an I/O error occurs.
     */
    OutputStream appendFileStream(String relPath) throws IOException;

    /**
     * There are a few FTPClient methods that do not complete the entire sequence of FTP commands to complete a transaction.
     * These commands require some action by the programmer after the reception of a positive intermediate command. After
     * the programmer's code completes its actions, it must call this method to receive the completion reply from the server
     * and verify the success of the entire transaction.
     *
     * @return true if successfully completed, false if not.
     * @throws IOException If an I/O error occurs.
     */
    boolean completePendingCommand() throws IOException;

    /**
     * Deletes a file on the FTP server.
     *
     * @param relPath The relPath of the file to be deleted.
     * @return true if successfully completed, false if not.
     * @throws IOException If an I/O error occurs.
     */
    boolean deleteFile(String relPath) throws IOException;

    /**
     * Sends the FTP QUIT command to the server, receive the reply, and return the reply code.
     *
     * @throws IOException If an I/O error occurs.
     */
    void disconnect() throws IOException;

    /**
     * Gets the integer value of the reply code of the last FTP reply.
     *
     * @return The integer value of the reply code of the last FTP reply.
     * @throws IOException If an I/O error occurs.
     */
    @SuppressWarnings("unused")
    default int getReplyCode() throws IOException {
        return FTPReply.COMMAND_OK;
    }

    /**
     * Gets the entire text of the last FTP server response exactly as it was received, including all end of line markers in
     * NETASCII format.
     *
     * @return The entire text from the last FTP response as a String.
     * @throws IOException If an I/O error occurs.
     */
    String getReplyString() throws IOException;

    /**
     * Queries the server for a supported feature.
     *
     * @param feature the name of the feature, converted to upper case.
     * @return {@code true} if the feature is present, {@code false} if the feature is not present or the FTP command
     *         failed.
     *
     * @throws IOException If an I/O error occurs.
     * @since 2.8.0
     */
    boolean hasFeature(String feature) throws IOException;

    /**
     * Tests if the client is currently connected to a server.
     *
     * @return true if the client is currently connected to a server, false otherwise.
     * @throws FileSystemException If an I/O error occurs.
     */
    boolean isConnected() throws FileSystemException;

    /**
     * Changes into a folder and back to the first folder. A read command. A transport reply aborts the client.
     *
     * @param relPath The folder.
     * @return the change.
     * @throws IOException if an I/O error occurs.
     */
    Change enterAndBack(String relPath) throws IOException;

    /**
     * Using the default system autodetect mechanism, obtain a list of file information for the current working directory or
     * for just a single file.
     * <p>
     * TODO This interface should not leak Apache Commons NET types like FTPFile
     * </p>
     *
     * @param relPath The file or directory to list.
     * @return an array of FTPFile.
     * @throws IOException If an I/O error occurs.
     */
    FTPFile[] listFiles(String relPath) throws IOException;

    /**
     * Lists a folder and reads the final reply of the listing. A read command. A transport reply aborts the client and
     * sends no further command on it. A refusal that is not a transport reply runs the second form: a change into the
     * folder, a listing with no path, and the change back.
     *
     * @param relPath The folder.
     * @param hidden true to ask for hidden names with the option {@code -a}.
     * @return the listing.
     * @throws IOException if an I/O error occurs.
     */
    Listing listChecked(String relPath, boolean hidden) throws IOException;

    /**
     * Creates a new subdirectory on the FTP server in the current directory (if a relative pathname is given) or where
     * specified (if an absolute pathname is given).
     *
     * @param relPath The pathname of the directory to create.
     * @return true if successfully completed, false if not.
     * @throws IOException If an I/O error occurs.
     */
    boolean makeDirectory(String relPath) throws IOException;

    /**
     * Sends MDTM and gives its reply. A read command. A transport reply aborts the client.
     *
     * @param relPath The file.
     * @return the reply, with the time as its text for a reply 213.
     * @throws IOException if an I/O error occurs.
     */
    Reply mdtmReply(String relPath) throws IOException;

    /**
     * Sends the MDTM command to get a file's date and time information after file transfer. It is typically more accurate
     * than the {@code "LIST"} command response. Time values are always represented in UTC (GMT), and in the Gregorian
     * calendar regardless of what calendar may have been in use at the date and time the file was last modified.
     * <p>
     * NOTE: not all remote FTP servers support {@code MDTM}.
     * </p>
     *
     * @param relPath The relative path of the file object to execute {@code MDTM} command against
     * @return new {@code Instant} object containing the {@code MDTM} timestamp.
     * @throws IOException If an I/O error occurs.
     * @since 2.8.0
     */
    @SuppressWarnings("unused")
    default Instant mdtmInstant(final String relPath) throws IOException {
        return null;
    }

    /**
     * Removes a directory on the FTP server (if empty).
     *
     * @param relPath The pathname of the directory to remove.
     * @return true if successfully completed, false if not.
     * @throws IOException If an I/O error occurs.
     */
    boolean removeDirectory(String relPath) throws IOException;

    /**
     * Renames a remote file.
     *
     * @param from The name of the remote file to rename.
     * @param to The new name of the remote file.
     * @return true if successfully completed, false if not.
     * @throws IOException If an I/O error occurs.
     */
    boolean rename(String from, String to) throws IOException;

    /**
     * Renames in two steps. RNTO is sent only after a positive intermediate reply to RNFR. Nothing is sent again. A
     * reply 421 to a step is given as the reply of that step, and the caller does not use the client again.
     *
     * @param from The name to rename.
     * @param to The new name.
     * @return the replies.
     * @throws IOException if an I/O error occurs. The client is aborted, and the rename may have worked.
     */
    RenameReplies renameSteps(String from, String to) throws IOException;

    /**
     * Returns an InputStream from which a named file from the server can be read.
     *
     * @param relPath The name of the remote file.
     * @return An InputStream from which the remote file can be read.
     * @throws IOException If an I/O error occurs.
     */
    InputStream retrieveFileStream(String relPath) throws IOException;

    /**
     * Returns an InputStream from which a named file from the server can be read.
     *
     * @param relPath The name of the remote file.
     * @param bufferSize buffer size.
     * @return An InputStream from which the remote file can be read.
     * @throws IOException If an I/O error occurs.
     */
    default InputStream retrieveFileStream(final String relPath, final int bufferSize) throws IOException {
        // Backward compatibility: no buffer size.
        return retrieveFileStream(relPath);
    }

    /**
     * Returns an InputStream from which a named file from the server can be read.
     *
     * @param relPath The name of the remote file.
     * @param restartOffset restart offset.
     * @return An InputStream from which the remote file can be read.
     * @throws IOException If an I/O error occurs.
     */
    InputStream retrieveFileStream(String relPath, long restartOffset) throws IOException;

    /**
     * Sets the buffer size for buffered data streams.
     *
     * @param bufferSize The size of the buffer.
     * @throws FileSystemException If an I/O error occurs.
     */
    @SuppressWarnings("unused")
    default void setBufferSize(final int bufferSize) throws FileSystemException {
        // Backward compatibility: do nothing.
    }

    /**
     * Returns an OutputStream through which data can be written to store a file on the server using the given name.
     *
     * @param relPath The name to give the remote file.
     * @return An OutputStream through which the remote file can be written.
     * @throws IOException If an I/O error occurs.
     */
    OutputStream storeFileStream(String relPath) throws IOException;

    /**
     * Sends SIZE and gives its reply. A read command. A transport reply aborts the client.
     *
     * @param relPath The file.
     * @return the reply, with the size as its text for a reply 213.
     * @throws IOException if an I/O error occurs.
     */
    Reply sizeReply(String relPath) throws IOException;

}
