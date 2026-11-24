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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.TimeoutException;

import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileNotFolderException;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.StalledTransferException;

import com.hierynomus.msdfsc.DFSException;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;

/**
 * Every exception that the rigs recorded, with its cause chain as recorded, fed into {@link Faults#of(Throwable)}.
 * The sources are the dead connection during the probe and the table of refusals of {@code rig/lock},
 * the results and stacks of {@code rig/smb2}, the findings of {@code rig/ftp}, and the beds {@code tbed/rig-ftps}
 * and {@code tbed/rig-cifs}. The FTP replies and the two SMB2 rows that the provider sets are read at their sites.
 */
public class RigExceptionFaultsTest {

    private static void assertFault(final Fault expected, final Throwable recorded) {
        assertEquals(expected, Faults.of(recorded), String.valueOf(recorded));
        // A provider site wraps the exception of the library, and the fault stays the same.
        assertEquals(expected, Faults.of(new FileSystemException("vfs.provider/get-type.error", recorded, "/in/a")),
                "wrapped " + recorded);
    }

    // rig/lock: the five ways of killing the connection during the probe link.
    @Test
    public void sftpDeadConnectionInEachWayOfCaseTenIsTransport() {
        final IOException closed = new IOException("inputstream is closed");
        // The proxy frozen then killed, the session timeout, and the black hole then killed.
        for (int way = 0; way < 3; way++) {
            assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_FAILURE, closed.toString(), closed));
        }
        // The proxy killed before the call.
        final IOException pipe = new IOException("Pipe closed");
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_FAILURE, pipe.toString(), pipe));
        // The sshd of the session stopped then killed, with a cause that has no text of its own.
        assertFault(Fault.TRANSPORT,
                new SftpException(ChannelSftp.SSH_FX_FAILURE, "java.io.IOException: inputstream is closed",
                        new IOException()));
    }

    // rig/lock: the replies of a refusal with no cause.
    @Test
    public void sftpRefusalsAreApplicationAndAGoneNameIsMissing() {
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_FAILURE, "Failure"));
        assertFault(Fault.APPLICATION, new SftpException(11, "File/Directory already exists"));
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied"));
        assertFault(Fault.APPLICATION,
                new SftpException(ChannelSftp.SSH_FX_OP_UNSUPPORTED, "hardlink@openssh.com is not supported"));
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_FAILURE,
                "/upload/q/a.dat.lock is not unique: [a.dat.qAx1.dat.lock, a.dat.q*x?.dat.lock]"));
        assertFault(Fault.MISSING, new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file"));
    }

    // rig/smb2: the statuses, the socket timeout, the write and the close, and the collisions of rig/lock.
    @Test
    public void smbRecordedStatusesAndTransportErrors() {
        assertFault(Fault.MISSING, reply(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, "Create failed for \\\\h\\data\\in"));
        assertFault(Fault.APPLICATION, reply(NtStatus.STATUS_ACCESS_DENIED, "Create failed for \\\\h\\data\\noaccess"));
        assertFault(Fault.APPLICATION, reply(NtStatus.STATUS_OBJECT_NAME_COLLISION, "Create failed for \\\\h\\data"));
        // The socket timeout: "Cannot resolve path", caused by a DFSException and a transport TimeoutException.
        final DFSException referral = new DFSException(
                TransportException.Wrapper.wrap(new TimeoutException("Timeout expired")));
        final SMBApiException unresolved = new SMBApiException(referral.getStatusCode(),
                SMB2MessageCommandCode.SMB2_CREATE, "Cannot resolve path \\\\127.0.0.1\\data\\in", referral);
        assertEquals(NtStatus.STATUS_OTHER, unresolved.getStatus());
        assertFault(Fault.TRANSPORT, unresolved);
        // A write with no watchdog: the TimeoutException of Share.receive, as Futures.get wraps it.
        assertFault(Fault.TRANSPORT, SMBRuntimeException.Wrapper.wrap(new TimeoutException("Timeout expired")));
        // A write with the watchdog: the raw socket was closed under the writer.
        assertFault(Fault.TRANSPORT, new SMBRuntimeException(new TransportException(new SocketException("Socket closed"))));
        assertFault(Fault.TRANSPORT, new SocketException("Socket closed"));
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_USER_SESSION_DELETED, "Read failed"));
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_NETWORK_SESSION_EXPIRED, "Read failed"));
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_NETWORK_NAME_DELETED, "Read failed"));
    }

    // rig/ftp, the frame codes and the timeouts that the factory and the transfers hit.
    @Test
    public void ftpRecordedExceptionsAreTransport() {
        assertFault(Fault.TRANSPORT, new SocketTimeoutException("Read timed out"));
        assertFault(Fault.TRANSPORT, new SocketTimeoutException("Accept timed out"));
        assertFault(Fault.TRANSPORT, new FTPConnectionClosedException("Connection closed without indication."));
        assertFault(Fault.TRANSPORT,
                new FTPConnectionClosedException("FTP response 421 received.  Server closed connection."));
        assertFault(Fault.TRANSPORT, new SocketException("Connection reset"));
        assertFault(Fault.TRANSPORT, new SocketException("Broken pipe"));
    }

    // tbed/rig-cifs: what a call on a mount gave after the levers.
    @Test
    public void localAndMountErrorsThatNoRowNamesAreTransport() {
        assertFault(Fault.TRANSPORT, new IOException("Host is down"));
        assertFault(Fault.TRANSPORT, new IOException("Resource temporarily unavailable"));
        assertFault(Fault.TRANSPORT, new java.nio.file.FileSystemException("/mnt/cifs/n1", null, "Invalid argument"));
        assertFault(Fault.APPLICATION, new AccessDeniedException("/mnt/cifs/ro/a.xml"));
    }

    // The two rows that the fork adds to the generic table.
    @Test
    public void stalledTransferIsTransportAndAnUnclearResultCarriesTransport() {
        assertFault(Fault.TRANSPORT, new StalledTransferException("A write moved no bytes", 61000, 5, null));
        assertFault(Fault.TRANSPORT, new UnclearResultException("vfs.provider/rename.error",
                new SocketException("Connection reset"), MovePhase.RENAME, null, false, "/in/a.xml"));
    }

    // tbed/rig-ftps: the reflection of fork v9 failed under a FileNotFolderException. No table names the class.
    @Test
    public void inaccessibleObjectUnderAFolderErrorIsApplication() {
        final Throwable recorded = inaccessibleObject("Unable to make field private final sun.security.util.Cache"
                + " sun.security.ssl.SSLSessionContextImpl.sessionHostPortCache accessible");
        assertFault(Fault.APPLICATION, new FileNotFolderException("ftps://ftpuser:***@127.0.0.1:5221/small", recorded));
    }

    // The JDK 8 build has no InaccessibleObjectException, so the class is made by name when the JDK has it.
    private static Throwable inaccessibleObject(final String message) {
        try {
            return (Throwable) Class.forName("java.lang.reflect.InaccessibleObjectException")
                    .getConstructor(String.class).newInstance(message);
        } catch (final ReflectiveOperationException e) {
            return new RuntimeException(message);
        }
    }

    private static SMBApiException reply(final NtStatus status, final String message) {
        return new SMBApiException(status.getValue(), SMB2MessageCommandCode.SMB2_CREATE, message, null);
    }
}
