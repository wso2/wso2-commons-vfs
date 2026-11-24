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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.file.DirectoryStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.io.CopyStreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.util.StalledTransferException;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msdfsc.DFSException;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2PacketHeader;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.JSchProxyException;
import com.jcraft.jsch.SftpException;

/**
 * One test per row of the fault tables. Each exception is built as its library builds it: with the constructor and
 * the message of the throw site, through the library code where that is plain, and by reflection where the library
 * keeps the constructor package private.
 */
public class FaultsTest {

    @TempDir
    Path folder;

    private static FileSystemException withFault(final Fault fault, final Throwable cause) {
        final FileSystemException error = new FileSystemException("vfs.provider/get-type.error", cause, "/in/a.xml");
        error.setFault(fault);
        return error;
    }

    private static FileSystemException wrapped(final Throwable cause) {
        return new FileSystemException("vfs.provider/get-type.error", cause, "/in/a.xml");
    }

    private static Exception jsch(final String simpleName, final Class<?>[] types, final Object... args)
            throws Exception {
        final Constructor<?> constructor = Class.forName("com.jcraft.jsch." + simpleName)
                .getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return (Exception) constructor.newInstance(args);
    }

    private static Exception jsch(final String simpleName, final String message) throws Exception {
        return jsch(simpleName, new Class<?>[] {String.class}, message);
    }

    // As Share.receive builds it for a status reply that is an error.
    private static SMBApiException reply(final long status) {
        final SMB2PacketHeader header = new SMB2PacketHeader();
        header.setStatusCode(status);
        header.setMessageType(SMB2MessageCommandCode.SMB2_CREATE);
        return new SMBApiException(header, "Create failed for \\\\files.example.com\\share\\in\\a.xml");
    }

    private static SMBApiException reply(final NtStatus status) {
        return reply(status.getValue());
    }

    private static UnclearResultException unclear(final MovePhase phase, final String tempPath,
            final boolean oldTargetDeleted) {
        return new UnclearResultException("vfs.provider/rename.error", new SocketException("Connection reset"), phase,
                tempPath, oldTargetDeleted, "/in/a.xml");
    }

    /**
     * Two distinct wrappers of this class compare equal.
     */
    private static final class EqualWrapper extends RuntimeException {

        private static final long serialVersionUID = 1L;

        EqualWrapper(final Throwable cause) {
            super("wrapper", cause);
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof EqualWrapper;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    /**
     * A file system exception of a provider of java.nio.file that is not in the JDK.
     */
    private static final class MountIoException extends java.nio.file.FileSystemException {

        private static final long serialVersionUID = 1L;

        MountIoException(final String file, final String reason) {
            super(file, null, reason);
        }
    }

    /**
     * An access denial of such a provider.
     */
    private static final class MountAccessDeniedException extends AccessDeniedException {

        private static final long serialVersionUID = 1L;

        MountAccessDeniedException(final String file) {
            super(file);
        }
    }

    private static void assertFault(final Fault expected, final Throwable error) {
        assertEquals(expected, Faults.of(error), error.toString());
        assertEquals(expected, Faults.of(wrapped(error)), "wrapped " + error);
    }

    // Steps

    @Test
    public void stepOneTheFirstFaultFromTheOutsideDecides() {
        final FileSystemException inner = withFault(Fault.BUSY, null);
        assertEquals(Fault.BUSY, Faults.of(wrapped(wrapped(inner))));
        assertEquals(Fault.APPLICATION, Faults.of(withFault(Fault.APPLICATION, withFault(Fault.TRANSPORT, null))));
    }

    @Test
    public void stepOneTheFaultOfTheProviderWinsOverTheTables() {
        final SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        assertEquals(Fault.BUSY, Faults.of(withFault(Fault.BUSY, timeout)));
        assertEquals(Fault.MISSING,
                Faults.of(wrapped(withFault(Fault.MISSING, new SftpException(ChannelSftp.SSH_FX_FAILURE, "Failure")))));
    }

    @Test
    public void stepTwoTheFirstExceptionThatATableKnowsDecides() {
        final NoSuchFileException key = new NoSuchFileException("/key");
        final JSchException outer = new JSchException("Session.connect: " + key, key);
        assertEquals(Fault.TRANSPORT, Faults.of(outer));
        assertEquals(Fault.MISSING, Faults.of(wrapped(wrapped(new NoSuchFileException("/in/a.xml")))));
    }

    @Test
    public void stepThreeAnExceptionThatNoTableKnowsIsApplication() {
        assertEquals(Fault.APPLICATION, Faults.of(new IllegalStateException("closed")));
        assertEquals(Fault.APPLICATION, Faults.of(new FileSystemException("vfs.provider/delete-read-only.error")));
        assertEquals(Fault.APPLICATION, Faults.of(wrapped(new RuntimeException("no row"))));
    }

    @Test
    public void aCauseChainWithALoopEnds() {
        final Exception first = new Exception("first");
        final Exception second = new Exception("second", first);
        first.initCause(second);
        assertEquals(Fault.APPLICATION, Faults.of(second));
        assertFalse(Faults.isTransport(second));
    }

    @Test
    public void twoWrappersThatCompareEqualDoNotHideAnInnerFault() {
        final EqualWrapper inner = new EqualWrapper(withFault(Fault.BUSY, null));
        final EqualWrapper outer = new EqualWrapper(inner);
        assertEquals(outer, inner);
        assertEquals(Fault.BUSY, Faults.of(outer));
    }

    // SFTP, JSch 2.27.7

    @Test
    public void sftpStatusWithACauseIsTransport() {
        // A connection that died during a call, and a call that was interrupted, as JSch reports them.
        final IOException closed = new IOException("inputstream is closed");
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_FAILURE, closed.toString(), closed));
        final IOException pipe = new IOException("Pipe closed");
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_FAILURE, pipe.toString(), pipe));
        final InterruptedIOException interrupted = new InterruptedIOException();
        assertFault(Fault.TRANSPORT,
                new SftpException(ChannelSftp.SSH_FX_FAILURE, interrupted.toString(), interrupted));
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file", closed));
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "", closed));
    }

    @Test
    public void sftpNoSuchFileWithNoCauseIsMissing() {
        assertFault(Fault.MISSING, new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file"));
    }

    @Test
    public void sftpNoConnectionAndConnectionLostWithNoCauseAreTransport() {
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_NO_CONNECTION, "No connection"));
        assertFault(Fault.TRANSPORT, new SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "Connection lost"));
    }

    @Test
    public void sftpAnyOtherStatusWithNoCauseIsApplication() {
        // Refusals as OpenSSH and MINA SSHD send them.
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_FAILURE, "Failure"));
        assertFault(Fault.APPLICATION, new SftpException(11, "File/Directory already exists"));
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_PERMISSION_DENIED, "Permission denied"));
        // JSch refuses a hard link by itself when the server has no such extension.
        assertFault(Fault.APPLICATION,
                new SftpException(ChannelSftp.SSH_FX_OP_UNSUPPORTED, "hardlink@openssh.com is not supported"));
        // JSch refuses a path with a wildcard that is not quoted.
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_FAILURE, "/in/a*.lock"));
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_EOF, "EOF"));
        assertFault(Fault.APPLICATION, new SftpException(ChannelSftp.SSH_FX_BAD_MESSAGE, "Bad message"));
    }

    @Test
    public void jschNegotiationHostKeyAndAuthExceptionsAreApplication() throws Exception {
        assertFault(Fault.APPLICATION, jsch("JSchAlgoNegoFailException",
                new Class<?>[] {int.class, String.class, String.class}, 0, "curve25519-sha256",
                "diffie-hellman-group1-sha1"));
        assertFault(Fault.APPLICATION, jsch("JSchUnknownHostKeyException", "reject HostKey: sftp.example.com"));
        assertFault(Fault.APPLICATION,
                jsch("JSchChangedHostKeyException", "HostKey has been changed: sftp.example.com"));
        assertFault(Fault.APPLICATION, jsch("JSchRevokedHostKeyException", "revoked HostKey: sftp.example.com"));
        assertFault(Fault.APPLICATION, jsch("JSchAuthCancelException", "password"));
        assertFault(Fault.APPLICATION, jsch("JSchPartialAuthException", "publickey,password"));
    }

    @Test
    public void anyOtherJschExceptionIsTransport() throws Exception {
        // JSch 2.27.7 reports a failed login with no type of its own. So a wrong password is a transport error.
        assertFault(Fault.TRANSPORT, new JSchException("Auth fail for methods 'publickey,password'"));
        assertFault(Fault.TRANSPORT, new JSchException("timeout: socket is not established"));
        final SocketException reset = new SocketException("Connection reset");
        assertFault(Fault.TRANSPORT, new JSchException("Session.connect: " + reset, reset));
        assertFault(Fault.TRANSPORT, new JSchException("session is down"));
        assertFault(Fault.TRANSPORT, new JSchProxyException("ProxyHTTP: java.net.ConnectException"));
        assertFault(Fault.TRANSPORT, jsch("JSchStrictKexException", "Strict KEX violation"));
        assertFault(Fault.TRANSPORT, jsch("JSchSessionDisconnectException",
                new Class<?>[] {String.class, int.class, String.class, String.class},
                "SSH_MSG_DISCONNECT: 11 Bye", 11, "Bye", ""));
    }

    // FTP and FTPS, commons net 3.9.0. The reply codes are read by the provider where it sends the command.

    @Test
    public void ftpClosedConnectionTimeoutsAndCutRepliesAreTransport() {
        assertFault(Fault.TRANSPORT, new FTPConnectionClosedException("Connection closed without indication."));
        // commons net throws this for a reply 421.
        assertFault(Fault.TRANSPORT,
                new FTPConnectionClosedException("FTP response 421 received.  Server closed connection."));
        assertFault(Fault.TRANSPORT, new SocketTimeoutException("Read timed out"));
        assertFault(Fault.TRANSPORT, new SocketException("Connection reset"));
        assertFault(Fault.TRANSPORT, new EOFException());
        assertFault(Fault.TRANSPORT, new SSLException("Unsupported or unrecognized SSL message"));
        assertFault(Fault.TRANSPORT, new MalformedServerReplyException("Truncated server reply: 22"));
    }

    @Test
    public void ftpCopyStreamExceptionWithATransportCauseIsTransport() {
        final IOException[] causes = {new FTPConnectionClosedException("Connection closed without indication."),
                new SocketTimeoutException("Read timed out"), new SocketException("Broken pipe"), new EOFException(),
                new SSLException("Connection reset"), new MalformedServerReplyException("Truncated server reply: 22")};
        for (final IOException cause : causes) {
            assertFault(Fault.TRANSPORT, new CopyStreamException("IOException caught while copying.", 512L, cause));
        }
    }

    // Local paths and mounts, java.nio.file and java.io

    @Test
    public void localNoSuchFileAndFileNotFoundAreMissing() {
        final Path missing = folder.resolve("missing.xml");
        assertFault(Fault.MISSING, assertThrows(NoSuchFileException.class,
                () -> Files.readAttributes(missing, BasicFileAttributes.class)));
        assertFault(Fault.MISSING, assertThrows(FileNotFoundException.class,
                () -> new FileInputStream(missing.toFile()).close()));
    }

    @Test
    public void localFileAlreadyExistsIsApplication() throws IOException {
        final Path file = Files.createFile(folder.resolve("a.xml"));
        assertFault(Fault.APPLICATION, assertThrows(FileAlreadyExistsException.class, () -> Files.createFile(file)));
    }

    @Test
    public void localAccessDeniedNotDirectoryAndDirectoryNotEmptyAreApplication() throws IOException {
        // Built as sun.nio.fs.UnixException builds it for EACCES.
        assertFault(Fault.APPLICATION, new AccessDeniedException(folder.resolve("closed").toString(), null, null));
        final Path file = Files.createFile(folder.resolve("a.xml"));
        assertFault(Fault.APPLICATION,
                assertThrows(NotDirectoryException.class, () -> Files.newDirectoryStream(file).close()));
        final Path full = Files.createDirectory(folder.resolve("full"));
        Files.createFile(full.resolve("b.xml"));
        assertFault(Fault.APPLICATION, assertThrows(DirectoryNotEmptyException.class, () -> Files.delete(full)));
    }

    @Test
    public void localAnyOtherIoExceptionIsTransport() {
        // A name below a file. sun.nio.fs.UnixException builds it for ENOTDIR.
        final String below = folder.resolve("a.xml").resolve("b.xml").toString();
        assertFault(Fault.TRANSPORT, new java.nio.file.FileSystemException(below, null, "Not a directory"));
        assertFault(Fault.TRANSPORT, new java.nio.file.FileSystemException(below, null, "Input/output error"));
        assertFault(Fault.TRANSPORT, new IOException("Stale file handle"));
    }

    @Test
    public void localFileSystemExceptionOfAnyProviderIsTransport() {
        assertFault(Fault.TRANSPORT, new MountIoException("/mnt/in/a.xml", "Input/output error"));
        assertFault(Fault.APPLICATION, new AccessDeniedException("/mnt/in/a.xml"));
        assertFault(Fault.APPLICATION, new MountAccessDeniedException("/mnt/in/a.xml"));
        assertEquals(Fault.APPLICATION, Faults.of(new FileSystemException("vfs.provider/get-type.error", "/in/a.xml")));
        assertEquals(Fault.APPLICATION, Faults.of(wrapped(new IllegalArgumentException("no row"))));
    }

    // SMB2, smbj 0.14.0. The share that was closed and the not found status of the DFS resolver are set by the
    // provider, with the state of the share.

    @Test
    public void smbTransportCauseComesBeforeTheStatus() {
        // A not found status over a transport cause, with the constructor of DiskShare.
        final DFSException dfs = new DFSException(new TransportException(new SocketException("Connection reset")));
        assertFault(Fault.TRANSPORT, new SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "Cannot resolve path \\\\files.example.com\\share\\in", dfs));
        // Share.send wraps a transport error like this.
        assertFault(Fault.TRANSPORT,
                new SMBRuntimeException(new TransportException("Disconnected")));
        // Futures.get wraps a timeout of a reply like this.
        assertFault(Fault.TRANSPORT, SMBRuntimeException.Wrapper.wrap(new TimeoutException("Timeout expired")));
        assertFault(Fault.TRANSPORT, new SMBApiException(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, new TimeoutException("Timeout expired")));
        // The DFS resolver times out on a referral, and DiskShare reports it with the status that the resolver set.
        final DFSException referral = new DFSException(
                TransportException.Wrapper.wrap(new TimeoutException("Timeout expired")));
        final SMBApiException resolve = new SMBApiException(referral.getStatusCode(),
                SMB2MessageCommandCode.SMB2_CREATE, "Cannot resolve path \\\\dfs.example.com\\root\\in", referral);
        assertEquals(NtStatus.STATUS_OTHER, resolve.getStatus());
        assertFault(Fault.TRANSPORT, resolve);
    }

    @Test
    public void smbSessionStatusesAreTransport() {
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_USER_SESSION_DELETED));
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_NETWORK_SESSION_EXPIRED));
        assertFault(Fault.TRANSPORT, reply(NtStatus.STATUS_NETWORK_NAME_DELETED));
    }

    @Test
    public void smbNotFoundWithNoTransportCauseIsMissing() {
        assertFault(Fault.MISSING, reply(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND));
        assertFault(Fault.MISSING, reply(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND));
    }

    @Test
    public void smbSharingViolationAndDeletePendingAreBusy() {
        assertFault(Fault.BUSY, reply(NtStatus.STATUS_SHARING_VIOLATION));
        assertFault(Fault.BUSY, reply(NtStatus.STATUS_DELETE_PENDING));
    }

    @Test
    public void smbNameCollisionIsApplication() {
        assertFault(Fault.APPLICATION, reply(NtStatus.STATUS_OBJECT_NAME_COLLISION));
    }

    @Test
    public void smbEveryOtherStatusIsApplication() {
        assertFault(Fault.APPLICATION, reply(NtStatus.STATUS_ACCESS_DENIED));
        assertFault(Fault.APPLICATION, reply(NtStatus.STATUS_LOGON_FAILURE));
        final SMBApiException unknown = reply(0xC0000999L);
        assertEquals(NtStatus.STATUS_OTHER, unknown.getStatus());
        assertFault(Fault.APPLICATION, unknown);
        // A share that was closed gives no proof of a dead connection by itself.
        final SMBRuntimeException closed = new SMBRuntimeException("DiskShare has already been closed");
        assertFault(Fault.APPLICATION, closed);
        assertFalse(Faults.isTransport(closed));
    }

    @Test
    public void smbFaultOfTheProviderWinsAndTheStatusIsKept() {
        final SMBApiException denied = reply(NtStatus.STATUS_ACCESS_DENIED);
        final FileSystemException site = wrapped(denied);
        assertFault(Fault.BUSY, new SMBRuntimeException(withFault(Fault.BUSY, denied)));
        assertEquals(Fault.APPLICATION, Faults.of(site));
        assertSame(denied, site.getCause());
        assertTrue(denied.getMessage().startsWith("STATUS_ACCESS_DENIED (0xc0000022): Create failed for"));
        assertEquals(Fault.TRANSPORT, Faults.of(withFault(Fault.TRANSPORT, denied)));
        assertEquals(Fault.TRANSPORT, Faults.of(wrapped(withFault(Fault.TRANSPORT, wrapped(denied)))));
        final SMBRuntimeException timeout = SMBRuntimeException.Wrapper.wrap(new TimeoutException("Timeout expired"));
        assertEquals(Fault.MISSING, Faults.of(wrapped(withFault(Fault.MISSING, timeout))));
    }

    // Any provider

    @Test
    public void genericExceptionsAreTransport() {
        final Throwable[] errors = {new SocketTimeoutException("connect timed out"),
                new SocketException("Connection reset"), new ConnectException("Connection refused"),
                new NoRouteToHostException("No route to host"), new UnknownHostException("sftp.example.com"),
                new EOFException(), new SSLException("Connection reset"),
                new SSLHandshakeException("PKIX path building failed"), new ClosedChannelException(),
                new ClosedByInterruptException(), new InterruptedIOException(), new TimeoutException()};
        for (final Throwable error : errors) {
            assertFault(Fault.TRANSPORT, error);
        }
    }

    // Fork exceptions

    @Test
    public void unclearResultExceptionCarriesTransport() {
        final SftpException missing = new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file");
        final UnclearResultException error = new UnclearResultException("vfs.provider/rename.error", missing,
                MovePhase.RENAME, null, false, "/in/a.xml");
        assertTrue(error instanceof FileSystemException);
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(missing, error.getCause());
        assertEquals("vfs.provider/rename.error", error.getCode());
        assertArrayEquals(new String[] {"/in/a.xml"}, error.getInfo());
        assertFault(Fault.TRANSPORT, error);
    }

    @Test
    public void movePhaseHasFiveValuesAndOnlyTheDeleteOfTheSourceHasACompleteTarget() {
        assertArrayEquals(new MovePhase[] {MovePhase.RENAME, MovePhase.COPY, MovePhase.COMMIT, MovePhase.AFTER_DELETE,
                MovePhase.DELETE_SOURCE}, MovePhase.values());
        for (final MovePhase phase : MovePhase.values()) {
            assertEquals(phase == MovePhase.DELETE_SOURCE, phase.isTargetComplete(), phase.name());
        }
    }

    @Test
    public void unclearResultExceptionCarriesItsPhase() {
        for (final MovePhase phase : MovePhase.values()) {
            final String tempPath = phase == MovePhase.RENAME ? null : "/out/a.xml.sj1q8z0k4w2rp5n.part";
            final UnclearResultException error = unclear(phase, tempPath, phase == MovePhase.AFTER_DELETE);
            assertSame(phase, error.getPhase());
            assertEquals(phase == MovePhase.DELETE_SOURCE, error.isCommitConfirmed(), phase.name());
            assertEquals(Fault.TRANSPORT, error.getFault());
            assertEquals(Fault.TRANSPORT, Faults.of(error));
        }
        assertThrows(NullPointerException.class, () -> unclear(null, null, false));
    }

    @Test
    public void unclearResultExceptionCarriesItsTempPathAndTheOldTarget() {
        assertEquals("/out/a.xml.sj1q8z0k4w2rp5n.part",
                unclear(MovePhase.COPY, "/out/a.xml.sj1q8z0k4w2rp5n.part", false).getTempPath());
        final UnclearResultException rename = unclear(MovePhase.RENAME, null, true);
        assertNull(rename.getTempPath());
        assertTrue(rename.isOldTargetDeleted());
        assertTrue(unclear(MovePhase.AFTER_DELETE, "/out/a.xml.sj1q8z0k4w2rp5n.part", true).isOldTargetDeleted());
        assertFalse(unclear(MovePhase.COMMIT, "/out/a.xml.sj1q8z0k4w2rp5n.part", false).isOldTargetDeleted());
    }

    @Test
    public void fileSystemExceptionHasNoFaultUntilASiteSetsOne() {
        final FileSystemException error = new FileSystemException("vfs.provider/get-type.error", "/in/a.xml");
        assertNull(error.getFault());
        error.setFault(Fault.MISSING);
        assertEquals(Fault.MISSING, error.getFault());
    }

    // The two short forms

    @Test
    public void isTransportFollowsTheFault() {
        assertTrue(Faults.isTransport(new SftpException(ChannelSftp.SSH_FX_CONNECTION_LOST, "Connection lost")));
        assertTrue(Faults.isTransport(withFault(Fault.TRANSPORT, null)));
        assertFalse(Faults.isTransport(new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file")));
        assertFalse(Faults.isTransport(withFault(Fault.BUSY, new SocketTimeoutException("Read timed out"))));
        assertFalse(Faults.isTransport(new AccessDeniedException("/in/a.xml")));
    }

    @Test
    public void isTransportIsTrueForAnInterruptedCall() {
        // smbj wraps an interrupted wait for a reply like this.
        final SMBRuntimeException interrupted = SMBRuntimeException.Wrapper.wrap(new InterruptedException());
        assertTrue(Faults.isTransport(interrupted));
        assertTrue(Faults.isTransport(wrapped(interrupted)));
        assertTrue(Faults.isTransport(new InterruptedIOException()));
    }

    @Test
    public void isTransportSeesAnInterruptBelowAFaultOfTheProvider() {
        final FileSystemException marked = withFault(Fault.APPLICATION, new ClosedByInterruptException());
        assertEquals(Fault.APPLICATION, Faults.of(marked));
        assertTrue(Faults.isTransport(marked));
    }

    @Test
    public void isFastIsTrueOnlyUnderFiveSeconds() {
        final long now = System.nanoTime();
        assertTrue(Faults.isFast(now));
        assertTrue(Faults.isFast(now - TimeUnit.SECONDS.toNanos(3)));
        assertFalse(Faults.isFast(now - TimeUnit.SECONDS.toNanos(5)));
        assertFalse(Faults.isFast(now - TimeUnit.SECONDS.toNanos(30)));
    }

    // Loading

    // A jar of com.hierynomus.asn1 with no smbj in it. The smbj bundle of the pack holds that package too, so the
    // separate jar is taken: from the test class path on the Maven libraries, and from the pack next to smbj.
    private static URL asnOneJar() throws Exception {
        final Path smbj = Paths.get(SMBRuntimeException.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI());
        final Enumeration<URL> found = FaultsTest.class.getClassLoader()
                .getResources("com/hierynomus/asn1/ASN1ParseException.class");
        while (found.hasMoreElements()) {
            final URL jar = ((JarURLConnection) found.nextElement().openConnection()).getJarFileURL();
            if (!Paths.get(jar.toURI()).equals(smbj)) {
                return jar;
            }
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(smbj.getParent(), "asn-one*.jar")) {
            return jars.iterator().next().toUri().toURL();
        }
    }

    @Test
    public void noLibraryIsNeededOnTheClassPath() throws Exception {
        final URL fork = Faults.class.getProtectionDomain().getCodeSource().getLocation();
        final URL lang = ArrayUtils.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {fork, lang, asnOneJar()}, null)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(SftpException.class.getName()));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass(FTPConnectionClosedException.class.getName()));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(SMBRuntimeException.class.getName()));
            final Method of = loader.loadClass(Faults.class.getName()).getMethod("of", Throwable.class);
            assertEquals("TRANSPORT", String.valueOf(of.invoke(null, new SocketTimeoutException("Read timed out"))));
            assertEquals("MISSING", String.valueOf(of.invoke(null, new NoSuchFileException("/in/a.xml"))));
            assertEquals("APPLICATION", String.valueOf(of.invoke(null, new IllegalStateException("closed"))));
            assertEquals("TRANSPORT",
                    String.valueOf(of.invoke(null, new MountIoException("/mnt/in/a.xml", "Input/output error"))));
            // An exception of com.hierynomus.asn1 has the vendor prefix of smbj and is no exception of smbj.
            final Object parse = loader.loadClass("com.hierynomus.asn1.ASN1ParseException")
                    .getConstructor(String.class).newInstance("Unknown tag");
            assertEquals("APPLICATION", String.valueOf(of.invoke(null, parse)));
        }
    }

    @Test
    public void stalledTransferExceptionIsTransport() {
        final StalledTransferException stalled = new StalledTransferException("A write moved no bytes", 61000, 5,
                null);
        assertEquals(Fault.TRANSPORT, Faults.of(stalled));
        assertTrue(Faults.isTransport(stalled));
        assertEquals(Fault.TRANSPORT,
                Faults.of(new FileSystemException("vfs.provider/get-type.error", stalled, "/in/a.xml")));
    }

    @Test
    public void stalledTransferInsideTheFtpCopyExceptionIsTransport() {
        final CopyStreamException copy = new CopyStreamException("IOException caught while copying.", 5,
                new StalledTransferException("A write moved no bytes", 61000, 5, null));
        assertEquals(Fault.TRANSPORT, Faults.of(copy));
        assertTrue(Faults.isTransport(new FileSystemException("vfs.provider/get-type.error", copy, "/in/a.xml")));
    }
}
