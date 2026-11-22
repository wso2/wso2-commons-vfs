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

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.io.CopyStreamException;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.StalledTransferException;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMBApiException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchAlgoNegoFailException;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.JSchHostKeyException;
import com.jcraft.jsch.SftpException;

/**
 * Tells what an exception of a file system call means for the connection.
 * <p>
 * The steps, in order. First, the first {@link FileSystemException} in the cause chain that carries a fault decides,
 * because its provider decided with the library exception in hand. Second, the first exception in the chain that a
 * table knows decides. Third, the answer is {@link Fault#APPLICATION}. The chain is walked from the outside in.
 * </p>
 * <p>
 * The rules of one library sit in their own class. That class is loaded only when the chain holds an exception of
 * that library, so this class does not need the library on the class path.
 * </p>
 */
public final class Faults {

    private static final long FAST_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private static final String JSCH = "com.jcraft.jsch.";

    private static final String COMMONS_NET = "org.apache.commons.net.";

    private static final String SMB_RUNTIME = "com.hierynomus.smbj.common.SMBRuntimeException";

    private static final Class<?>[] GENERIC_TRANSPORT = {SocketTimeoutException.class, SocketException.class,
            ConnectException.class, NoRouteToHostException.class, UnknownHostException.class, EOFException.class,
            SSLException.class, ClosedChannelException.class, InterruptedIOException.class, TimeoutException.class};

    private Faults() {
    }

    /**
     * Gets the fault of an exception.
     *
     * @param error the exception of a file system call.
     * @return the fault.
     */
    public static Fault of(final Throwable error) {
        final Fault fault = match(error);
        return fault != null ? fault : Fault.APPLICATION;
    }

    /**
     * Gets the first provider fault or table match in the cause chain.
     *
     * @param error the exception of a file system call.
     * @return the matched fault, or null when no row applies.
     */
    public static Fault match(final Throwable error) {
        final List<Throwable> chain = chainOf(error);
        for (final Throwable link : chain) {
            if (link instanceof FileSystemException) {
                final Fault fault = ((FileSystemException) link).getFault();
                if (fault != null) {
                    return fault;
                }
            }
        }
        for (final Throwable link : chain) {
            final Fault fault = rowOf(link);
            if (fault != null) {
                return fault;
            }
        }
        return null;
    }

    /**
     * Tells whether an exception is a transport error. That is the case when {@link #of(Throwable)} gives
     * {@link Fault#TRANSPORT}, or when the call was interrupted. An {@link InterruptedException} or a
     * {@link ClosedByInterruptException} anywhere in the cause chain shows the interrupt.
     *
     * @param error the exception of a file system call.
     * @return true for a transport error.
     */
    public static boolean isTransport(final Throwable error) {
        return of(error) == Fault.TRANSPORT || chainOf(error).stream().anyMatch(
                link -> link instanceof InterruptedException || link instanceof ClosedByInterruptException);
    }

    /**
     * Tells whether less than 5 seconds have passed since the given start. A call may be tried again only then.
     *
     * @param startNanos the start, as {@link System#nanoTime()} gave it.
     * @return true when less than 5 seconds have passed.
     */
    public static boolean isFast(final long startNanos) {
        return System.nanoTime() - startNanos < FAST_LIMIT_NANOS;
    }

    private static List<Throwable> chainOf(final Throwable error) {
        final List<Throwable> chain = new ArrayList<>();
        // By identity. Two exceptions that compare equal are still two links.
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = error; link != null && seen.add(link); link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }

    private static Fault rowOf(final Throwable error) {
        final String name = error.getClass().getName();
        if (name.startsWith(JSCH)) {
            return SftpRules.of(error);
        }
        if (name.startsWith(COMMONS_NET)) {
            return FtpRules.of(error);
        }
        if (isSmbRuntime(error)) {
            return SmbRules.of(error);
        }
        if (isGenericTransport(error) || error instanceof StalledTransferException) {
            return Fault.TRANSPORT;
        }
        return localRow(error);
    }

    // Reads only the names of classes that are already loaded, so smbj is not needed on the class path.
    private static boolean isSmbRuntime(final Throwable error) {
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            if (SMB_RUNTIME.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGenericTransport(final Throwable error) {
        for (final Class<?> type : GENERIC_TRANSPORT) {
            if (type.isInstance(error)) {
                return true;
            }
        }
        return false;
    }

    private static Fault localRow(final Throwable error) {
        if (error instanceof NoSuchFileException || error instanceof FileNotFoundException) {
            return Fault.MISSING;
        }
        if (error instanceof FileAlreadyExistsException || error instanceof AccessDeniedException
                || error instanceof NotDirectoryException || error instanceof DirectoryNotEmptyException) {
            return Fault.APPLICATION;
        }
        // The local table covers the exceptions of java.io and java.nio.file, and the file system exceptions of any
        // provider of java.nio.file. Any other of them is a transport error.
        final String name = error.getClass().getName();
        if (error instanceof java.nio.file.FileSystemException
                || error instanceof IOException && (name.startsWith("java.io.") || name.startsWith("java.nio.file."))) {
            return Fault.TRANSPORT;
        }
        return null;
    }

    /**
     * The rules of JSch.
     */
    private static final class SftpRules {

        // JSch keeps these two classes package private, so they are known by name.
        private static final String AUTH_CANCEL = "com.jcraft.jsch.JSchAuthCancelException";

        private static final String PARTIAL_AUTH = "com.jcraft.jsch.JSchPartialAuthException";

        static Fault of(final Throwable error) {
            if (error instanceof SftpException) {
                // JSch wraps a broken connection as a status with the exception as its cause.
                if (error.getCause() != null) {
                    return Fault.TRANSPORT;
                }
                switch (((SftpException) error).id) {
                case ChannelSftp.SSH_FX_NO_SUCH_FILE:
                    return Fault.MISSING;
                case ChannelSftp.SSH_FX_NO_CONNECTION:
                case ChannelSftp.SSH_FX_CONNECTION_LOST:
                    return Fault.TRANSPORT;
                default:
                    return Fault.APPLICATION;
                }
            }
            final String name = error.getClass().getName();
            if (error instanceof JSchAlgoNegoFailException || error instanceof JSchHostKeyException
                    || AUTH_CANCEL.equals(name) || PARTIAL_AUTH.equals(name)) {
                return Fault.APPLICATION;
            }
            if (error instanceof JSchException) {
                return Fault.TRANSPORT;
            }
            return null;
        }
    }

    /**
     * The rules of smbj, for its runtime exception and its API exception. The two rows that need the state of the
     * share or of the DFS resolver are set by the provider.
     */
    private static final class SmbRules {

        static Fault of(final Throwable error) {
            // The cause comes before the status. TransportException is an IOException.
            final List<Throwable> chain = chainOf(error);
            for (final Throwable cause : chain.subList(1, chain.size())) {
                if (cause instanceof IOException || cause instanceof TimeoutException) {
                    return Fault.TRANSPORT;
                }
            }
            if (!(error instanceof SMBApiException)) {
                return null;
            }
            final NtStatus status = ((SMBApiException) error).getStatus();
            if (status == NtStatus.STATUS_USER_SESSION_DELETED || status == NtStatus.STATUS_NETWORK_SESSION_EXPIRED
                    || status == NtStatus.STATUS_NETWORK_NAME_DELETED) {
                return Fault.TRANSPORT;
            }
            if (status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) {
                return Fault.MISSING;
            }
            if (status == NtStatus.STATUS_SHARING_VIOLATION || status == NtStatus.STATUS_DELETE_PENDING) {
                return Fault.BUSY;
            }
            return Fault.APPLICATION;
        }
    }

    /**
     * The rules of commons net. A reply code is read by the provider where it sends the command, so only the
     * exceptions are here.
     */
    private static final class FtpRules {

        static Fault of(final Throwable error) {
            if (error instanceof FTPConnectionClosedException || error instanceof MalformedServerReplyException) {
                return Fault.TRANSPORT;
            }
            if (error instanceof CopyStreamException && isCopyCause(error.getCause())) {
                return Fault.TRANSPORT;
            }
            return null;
        }

        private static boolean isCopyCause(final Throwable cause) {
            return cause instanceof FTPConnectionClosedException || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException || cause instanceof EOFException
                    || cause instanceof SSLException || cause instanceof MalformedServerReplyException;
        }
    }
}
