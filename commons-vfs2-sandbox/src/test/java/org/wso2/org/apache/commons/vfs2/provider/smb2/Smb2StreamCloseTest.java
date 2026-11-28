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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.File;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;
import org.wso2.org.apache.commons.vfs2.util.MonitorOutputStream;
import org.wso2.org.apache.commons.vfs2.util.RawMonitorInputStream;

/**
 * The close of the SMB2 streams. Each close has its own try, so a failed close never skips the close of the handle.
 * After a transport error the sockets are closed first and the client is discarded.
 */
public class Smb2StreamCloseTest {

    private final List<String> order = new ArrayList<>();

    private Client client;

    private Smb2FileSystem fileSystem;

    private Smb2FileObject fileObject;

    private void open() throws Exception {
        client = new Client(null, order);
        fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper, new Client().wrapper));
        fileObject = new Smb2FileObject(Smb2Doubles.name("in/big.bin"), fileSystem, Smb2Doubles.rootName());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    // A double of the handle of smbj. It counts its closes and can throw.
    private DiskEntry handle(final RuntimeException error) {
        final DiskEntry handle = mock(DiskEntry.class);
        doAnswer(call -> {
            order.add("handle-close aborted=" + client.group.isAborted());
            if (error != null) {
                throw error;
            }
            return null;
        }).when(handle).close();
        return handle;
    }

    // A double of the file object. It counts its closes and can throw.
    private FileObject file(final boolean fails) throws FileSystemException {
        final FileObject file = mock(FileObject.class);
        doAnswer(call -> {
            order.add("file-close");
            if (fails) {
                throw new FileSystemException("the close of the file failed");
            }
            return null;
        }).when(file).close();
        return file;
    }

    // A double of the read stream of smbj.
    private InputStream readStream(final IOException closeError, final RuntimeException readError) {
        return new InputStream() {
            @Override
            public int read() {
                if (readError != null) {
                    throw readError;
                }
                return 'a';
            }

            @Override
            public void close() throws IOException {
                order.add("stream-close aborted=" + client.group.isAborted());
                if (closeError != null) {
                    throw closeError;
                }
            }
        };
    }

    private long handleCloses() {
        return order.stream().filter(line -> line.startsWith("handle-close")).count();
    }

    @Test
    public void wirePathUsesTheShareRelativeNameWithoutItsQuery() throws Exception {
        open();
        for (final String suffix : new String[] { "", "?transport.vfs.diskShareAccessMask=MAXIMUM_ALLOWED" }) {
            final Smb2FileObject file = new Smb2FileObject(Smb2Doubles.name("in/small%20name.txt" + suffix),
                    fileSystem, Smb2Doubles.rootName());
            assertEquals("in\\small name.txt", file.getRelPathToShare());
            final Smb2FileObject share = new Smb2FileObject(Smb2Doubles.name(suffix), fileSystem, Smb2Doubles.rootName());
            assertEquals("", share.getRelPathToShare());
        }
    }

    @Test
    public void endOfStreamReturnsTheHealthyClientAndClosesTheHandleOnce() throws Exception {
        open();
        final InputStream stream = fileObject.new Smb2InputStream(client.wrapper,
                new ByteArrayInputStream(new byte[] { 7 }), file(false), handle(null));
        assertEquals(7, stream.read());
        assertEquals(-1, stream.read());
        assertEquals(0, fileSystem.getHandedOutCount());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
        stream.close();
        assertEquals(1, handleCloses());
    }

    @Test
    public void refusedHandleCloseAtEndOfStreamKeepsTheCauseAndHealthyClient() throws Exception {
        open();
        final SMBApiException refused = refused(true);
        final InputStream stream = fileObject.new Smb2InputStream(client.wrapper,
                new ByteArrayInputStream(new byte[0]), file(false), handle(refused));
        final FileSystemException failure = assertThrows(FileSystemException.class, stream::read);
        assertSame(refused, failure.getCause());
        assertEquals(Fault.APPLICATION, failure.getFault());
        assertEquals(0, fileSystem.getHandedOutCount());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
        stream.close();
        assertEquals(1, handleCloses());
    }

    @Test
    public void bulkEndOfStreamPreservesTheFailedHandleCloseAndDiscardsTheClient() throws Exception {
        open();
        final SMBRuntimeException error = new SMBRuntimeException(new TransportException("connection lost"));
        final InputStream stream = fileObject.new Smb2InputStream(client.wrapper,
                new ByteArrayInputStream(new byte[0]), file(false), handle(error));
        final FileSystemException failure = assertThrows(FileSystemException.class, () -> stream.read(new byte[8]));
        assertSame(error, failure.getCause());
        assertEquals(Fault.TRANSPORT, failure.getFault());
        assertEquals(0, fileSystem.getHandedOutCount());
        assertEquals(0, fileSystem.getOpenClientCount());
        stream.close();
        assertEquals(1, handleCloses());
    }

    @Test
    public void handleIsClosedOnceWhenTheStreamCloseThrows() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper,
                readStream(new IOException("the close of the stream failed"), null), file(false), handle(null));
        assertThrows(IOException.class, in::close);
        assertEquals(1, handleCloses());
        assertEquals(1, order.stream().filter(line -> line.startsWith("file-close")).count());
        // A plain IOException is a transport error by the table of faults, so the sockets are closed before the
        // handle and the client is discarded.
        assertEquals("handle-close aborted=true", order.get(order.size() - 2));
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void handleIsClosedOnceWhenTheFileCloseThrows() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, readStream(null, null), file(true),
                handle(null));
        assertThrows(IOException.class, in::close);
        assertEquals(1, handleCloses());
    }

    @Test
    public void handleIsClosedOnceWhenTheStreamAndTheFileCloseThrow() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper,
                readStream(new IOException("the close of the stream failed"), null), file(true), handle(null));
        assertThrows(IOException.class, in::close);
        assertEquals(1, handleCloses());
    }

    @Test
    public void handleIsClosedOnceWhenTheStreamCloseThrowsUnchecked() throws Exception {
        open();
        final InputStream stream = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public void close() {
                order.add("stream-close");
                throw new SMBRuntimeException("unchecked close error");
            }
        };
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, stream, file(false), handle(null));
        assertThrows(IOException.class, in::close);
        assertEquals(1, handleCloses());
    }

    @Test
    public void readWithATransportErrorAbortsBeforeEveryCloseAndDiscardsTheClient() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper,
                readStream(null, new SMBRuntimeException(new TransportException("read timeout"))), file(false),
                handle(new SMBRuntimeException(new TransportException("transport is disconnected"))));
        final FileSystemException readError = assertThrows(FileSystemException.class, in::read);
        assertEquals(Fault.TRANSPORT, readError.getFault());
        assertTrue(readError.getCause() instanceof SMBRuntimeException);
        assertThrows(IOException.class, in::close);
        assertEquals(Arrays.asList("stream-close aborted=true", "file-close", "handle-close aborted=true",
                "client-close aborted=true"), order);
        assertFalse(client.wrapper.isAliveLocally());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    // A double of the write stream of smbj. A write or a close can throw.
    private OutputStream writeStream(final RuntimeException writeError, final RuntimeException closeError) {
        return new OutputStream() {
            @Override
            public void write(final int b) {
                if (writeError != null) {
                    throw writeError;
                }
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                if (writeError != null) {
                    throw writeError;
                }
            }

            @Override
            public void close() {
                order.add("stream-close aborted=" + client.group.isAborted());
                if (closeError != null) {
                    throw closeError;
                }
            }
        };
    }

    @Test
    public void writeThatTimesOutAbortsFirstClosesTheHandleOnceAndDiscardsTheClient() throws Exception {
        open();
        final SMBRuntimeException timeout = new SMBRuntimeException(new TransportException("write timeout"));
        final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper, writeStream(timeout, timeout),
                handle(new SMBRuntimeException(new TransportException("transport is disconnected"))));
        final FileSystemException writeError = assertThrows(FileSystemException.class,
                () -> out.write(new byte[1 << 20]));
        assertEquals(Fault.TRANSPORT, writeError.getFault());
        assertSame(timeout, writeError.getCause());
        assertEquals(Fault.TRANSPORT, assertThrows(FileSystemException.class, out::close).getFault());
        assertEquals(1, handleCloses());
        assertTrue(order.stream().allMatch(line -> !line.contains("aborted=false")), order.toString());
        assertEquals("client-close aborted=true", order.get(order.size() - 1));
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void writeThatTheServerRefusesClosesTheHandleAndKeepsTheClient() throws Exception {
        open();
        final SMBApiException full = new SMBApiException(NtStatus.STATUS_DISK_FULL.getValue(),
                SMB2MessageCommandCode.SMB2_WRITE, "disk full", null);
        final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper, writeStream(full, full), handle(null));
        // A small write stays in the buffer of the base class, so the flush of the close meets the error too.
        out.write(new byte[16]);
        final FileSystemException closeError = assertThrows(FileSystemException.class, out::close);
        assertEquals(Fault.APPLICATION, closeError.getFault());
        assertSame(full, closeError.getCause());
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    private SMBApiException refused(final boolean read) {
        return read ? new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(), SMB2MessageCommandCode.SMB2_READ,
                "access denied", null)
                : new SMBApiException(NtStatus.STATUS_DISK_FULL.getValue(), SMB2MessageCommandCode.SMB2_WRITE,
                        "disk full", null);
    }

    // The server refuses a read or a write, which on its own keeps the client. Then the close of the handle meets a
    // transport timeout, or works as the control.
    private void refusalThenHandleClose(final boolean read, final boolean timeout) throws Exception {
        open();
        final DiskEntry handle = handle(timeout ? new SMBRuntimeException(new TransportException("close timeout"))
                : null);
        final SMBApiException refusal = refused(read);
        final Closeable stream;
        final FileSystemException first;
        if (read) {
            final InputStream in = fileObject.new Smb2InputStream(client.wrapper, readStream(null, refusal),
                    file(false), handle);
            stream = in;
            first = assertThrows(FileSystemException.class, in::read);
        } else {
            final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper, writeStream(refusal, null),
                    handle);
            stream = out;
            first = assertThrows(FileSystemException.class, () -> out.write(new byte[32768]));
        }
        assertEquals(Fault.APPLICATION, first.getFault());
        assertSame(refusal, first.getCause());
        if (timeout) {
            assertEquals(Fault.TRANSPORT, assertThrows(FileSystemException.class, stream::close).getFault());
        } else {
            stream.close();
        }
        assertEquals(1, handleCloses());
        // The handle close ran before anything knew of the transport failure.
        assertTrue(order.contains("handle-close aborted=false"), order.toString());
        if (timeout) {
            assertTrue(client.group.isAborted());
            assertEquals(1, client.closes.get());
            assertEquals(0, fileSystem.getOpenClientCount());
            assertNotSame(client.wrapper, fileSystem.getClient());
        } else {
            assertFalse(client.group.isAborted());
            assertSame(client.wrapper, fileSystem.getClient());
        }
    }

    @Test
    public void readRefusedThenAHandleCloseTimeoutDiscardsTheClient() throws Exception {
        refusalThenHandleClose(true, true);
    }

    @Test
    public void readRefusedWithAGoodHandleCloseKeepsTheClient() throws Exception {
        refusalThenHandleClose(true, false);
    }

    @Test
    public void writeRefusedThenAHandleCloseTimeoutDiscardsTheClient() throws Exception {
        refusalThenHandleClose(false, true);
    }

    @Test
    public void writeRefusedWithAGoodHandleCloseKeepsTheClient() throws Exception {
        refusalThenHandleClose(false, false);
    }

    @Test
    public void readRefusedThenATransportErrorOfTheStreamCloseAbortsBeforeTheHandleClose() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper,
                readStream(new SocketTimeoutException("close timeout"), refused(true)), file(false), handle(null));
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class, in::read).getFault());
        assertThrows(IOException.class, in::close);
        assertEquals(Arrays.asList("stream-close aborted=false", "file-close", "handle-close aborted=true",
                "client-close aborted=true"), order);
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void writeRefusedThenATransportErrorOfTheStreamCloseAbortsBeforeTheHandleClose() throws Exception {
        open();
        final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper,
                writeStream(refused(false), new SMBRuntimeException(new TransportException("close timeout"))),
                handle(null));
        assertEquals(Fault.APPLICATION,
                assertThrows(FileSystemException.class, () -> out.write(new byte[32768])).getFault());
        assertEquals(Fault.TRANSPORT, assertThrows(FileSystemException.class, out::close).getFault());
        assertEquals("handle-close aborted=true", order.get(order.size() - 2));
        assertEquals("client-close aborted=true", order.get(order.size() - 1));
        assertEquals(1, handleCloses());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void wrongReadArgumentsAreJavaErrorsAndLeaveTheClientUsable() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, readStream(null, null), file(false),
                handle(null));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], 2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> in.read(new byte[1], 0, -1));
        assertThrows(NullPointerException.class, () -> in.read(null, 0, 1));
        assertEquals(0, in.read(new byte[1], 1, 0));
        assertEquals('a', in.read());
        in.close();
        // A closed stream answers as before, also to a wrong slice.
        assertEquals(-1, in.read(new byte[1], 2, 1));
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void wrongWriteArgumentsAreJavaErrorsAndLeaveTheClientUsable() throws Exception {
        open();
        final ByteArrayOutputStream smbj = new ByteArrayOutputStream();
        final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper, smbj, handle(null));
        assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[1], 2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[1], 0, -1));
        // A long slice would pass the buffer of the base class straight to smbj.
        assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[1], 0, 1 << 20));
        assertThrows(NullPointerException.class, () -> out.write(null, 0, 1));
        out.write(new byte[1], 1, 0);
        out.write(new byte[16]);
        out.close();
        assertEquals(16, smbj.size());
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
        // A closed stream answers as before: the closed check comes first, also for a wrong slice.
        assertThrows(FileSystemException.class, () -> out.write(new byte[1], 2, 1));
    }

    // A failed flush of the stream that the caller holds. smbj sends the buffered bytes in the flush, and a refusal or
    // a timeout comes back. The close of that stream flushes again before it closes the SMB2 stream.
    private void failedFlushThenClose(final boolean timeout) throws Exception {
        open();
        final RuntimeException error = timeout ? new SMBRuntimeException(new TransportException("write timeout"))
                : refused(false);
        final List<Boolean> abortedAtWrite = new ArrayList<>();
        final OutputStream smbj = new OutputStream() {
            @Override
            public void write(final int b) {
                abortedAtWrite.add(client.group.isAborted());
                throw error;
            }
        };
        final OutputStream out = contentStream(fileObject.new Smb2OutputStream(client.wrapper, smbj, handle(null)));
        out.write(1);
        final FileSystemException flushError = assertThrows(FileSystemException.class, out::flush);
        assertSame(error, flushError.getCause());
        assertEquals(timeout ? Fault.TRANSPORT : Fault.APPLICATION, flushError.getFault());
        // The local check before the close already sees a dead client, and only then.
        assertEquals(!timeout, fileSystem.isAliveLocally());
        final long start = System.nanoTime();
        assertThrows(FileSystemException.class, out::close);
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertEquals(1, handleCloses());
        assertTrue(order.contains("content-close"));
        if (timeout) {
            // Every write after the first met closed sockets, so none of them waited a request timeout.
            assertEquals(false, abortedAtWrite.get(0));
            assertTrue(abortedAtWrite.subList(1, abortedAtWrite.size()).stream().allMatch(Boolean::booleanValue),
                    abortedAtWrite.toString());
            assertEquals(1, client.closes.get());
            assertNotSame(client.wrapper, fileSystem.getClient());
        } else {
            assertFalse(client.group.isAborted());
            assertSame(client.wrapper, fileSystem.getClient());
        }
    }

    @Test
    public void flushTimeoutAbortsAtOnceSoTheCloseDoesNotWaitAgain() throws Exception {
        failedFlushThenClose(true);
    }

    @Test
    public void flushRefusedKeepsTheClientHealthyAndUsable() throws Exception {
        failedFlushThenClose(false);
    }

    // A read of smbj that blocks until the sockets of the client are closed, as a real read on a dead wire does. Then
    // it fails as smbj fails, or it returns a byte, as a read whose reply came in at that moment does.
    private InputStream blockedRead(final CountDownLatch entered, final boolean failsAtTheEnd) {
        return new InputStream() {
            @Override
            public int read() {
                entered.countDown();
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!client.group.isAborted() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                if (failsAtTheEnd) {
                    throw new SMBRuntimeException(new TransportException("connection reset"));
                }
                return 'a';
            }
        };
    }

    private void closeWhileAReadIsInSmbj(final boolean failsAtTheEnd) throws Exception {
        open();
        final CountDownLatch entered = new CountDownLatch(1);
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, blockedRead(entered, failsAtTheEnd),
                file(false), handle(null));
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread reader = new Thread(() -> {
            try {
                result.set(in.read());
            } catch (final IOException e) {
                result.set(e);
            }
        }, "reader");
        reader.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        // The reader holds the monitor of the stream while it waits in smbj. The close does not need it.
        final long start = System.nanoTime();
        in.close();
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        reader.join(10000);
        assertFalse(reader.isAlive());
        if (failsAtTheEnd) {
            assertEquals(Fault.TRANSPORT, ((FileSystemException) result.get()).getFault());
        } else {
            assertEquals((int) 'a', result.get());
        }
        // The client was retired before it went back, so the next borrower gets another one.
        assertTrue(client.group.isAborted());
        assertEquals(1, client.closes.get());
        assertNotSame(client.wrapper, fileSystem.getClient());
        assertEquals(1, handleCloses());
        in.close();
        assertEquals(1, handleCloses());
        assertEquals(1, client.closes.get());
    }

    @Test
    public void closeWhileAReadWaitsInSmbjEndsTheReadAndRetiresTheClient() throws Exception {
        closeWhileAReadIsInSmbj(true);
    }

    @Test
    public void closeWhileAReadWaitsInSmbjRetiresTheClientAlsoWhenTheReadReturns() throws Exception {
        closeWhileAReadIsInSmbj(false);
    }

    @Test
    public void readThatEndsBeforeTheCloseKeepsOrRetiresTheClientByItsOwnResult() throws Exception {
        for (final boolean fails : new boolean[] {false, true}) {
            order.clear();
            open();
            final InputStream in = fileObject.new Smb2InputStream(client.wrapper,
                    readStream(null, fails ? new SMBRuntimeException(new TransportException("reset")) : null),
                    file(false), handle(null));
            if (fails) {
                assertEquals(Fault.TRANSPORT, assertThrows(FileSystemException.class, in::read).getFault());
                // The sockets were closed at the failure, before any close step.
                assertTrue(client.group.isAborted());
            } else {
                assertEquals('a', in.read());
            }
            in.close();
            in.close();
            assertEquals(1, handleCloses());
            if (fails) {
                assertNotSame(client.wrapper, fileSystem.getClient());
            } else {
                assertFalse(client.group.isAborted());
                assertSame(client.wrapper, fileSystem.getClient());
            }
        }
    }

    // A read of smbj into a buffer, which is how a skip with a mark fills the buffer. It blocks until the sockets of
    // the client are closed and then fails as smbj fails, or it returns bytes, as a read whose reply came in then.
    private InputStream blockedBufferRead(final CountDownLatch entered, final boolean failsAtTheEnd) {
        return new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("a buffered stream reads into its buffer");
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) {
                entered.countDown();
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!client.group.isAborted() && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                if (failsAtTheEnd) {
                    throw new SMBRuntimeException(new TransportException("connection reset"));
                }
                buffer[offset] = 'a';
                return 1;
            }
        };
    }

    // The stream that a caller holds, the wrapper of the file content around the SMB2 stream.
    private InputStream contentRead(final InputStream smbj) throws FileSystemException {
        return new RawMonitorInputStream(fileObject.new Smb2InputStream(client.wrapper, smbj, file(false),
                handle(null)));
    }

    private void closeWhileAMarkedSkipIsInSmbj(final boolean failsAtTheEnd) throws Exception {
        open();
        final CountDownLatch entered = new CountDownLatch(1);
        final InputStream in = contentRead(blockedBufferRead(entered, failsAtTheEnd));
        in.mark(4096);
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread skipper = new Thread(() -> {
            try {
                result.set(in.skip(1));
            } catch (final IOException e) {
                result.set(e);
            }
        }, "skipper");
        skipper.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        final long start = System.nanoTime();
        in.close();
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        skipper.join(10000);
        assertFalse(skipper.isAlive());
        if (failsAtTheEnd) {
            final FileSystemException error = (FileSystemException) result.get();
            assertEquals(Fault.TRANSPORT, error.getFault());
            assertTrue(error.getCause() instanceof SMBRuntimeException);
        } else {
            assertEquals(1L, result.get());
        }
        // The client was retired before it went back, so the next borrower gets another, healthy one.
        assertTrue(client.group.isAborted());
        final Smb2ClientWrapper next = (Smb2ClientWrapper) fileSystem.getClient();
        assertNotSame(client.wrapper, next);
        assertTrue(next.isAliveLocally());
        assertEquals(1, handleCloses());
        in.close();
        assertEquals(1, handleCloses());
    }

    @Test
    public void closeWhileAMarkedSkipFillsTheBufferEndsItAndRetiresTheClient() throws Exception {
        closeWhileAMarkedSkipIsInSmbj(true);
    }

    @Test
    public void closeWhileAMarkedSkipFillsTheBufferRetiresTheClientAlsoWhenTheSkipReturns() throws Exception {
        closeWhileAMarkedSkipIsInSmbj(false);
    }

    @Test
    public void markedSkipThatFailsBeforeTheCloseKeepsTheCauseAndRetiresTheClient() throws Exception {
        open();
        final SMBRuntimeException timeout = new SMBRuntimeException(new TransportException("read timeout"));
        final InputStream in = contentRead(readStream(null, timeout));
        in.mark(4096);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> in.skip(1));
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(timeout, error.getCause());
        // The sockets were closed at the failure, before any close step.
        assertTrue(client.group.isAborted());
        in.close();
        assertEquals(1, handleCloses());
        assertNotSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void skipsThatNeedNoServerKeepTheBufferAndTheClient() throws Exception {
        open();
        final InputStream in = contentRead(readStream(null, null));
        // An unmarked skip with an empty buffer skips in the stream of smbj.
        assertEquals(3L, in.skip(3));
        // A read fills the buffer. A marked skip is served from it, and reset goes back to the mark.
        in.mark(4096);
        final int first = in.read();
        assertEquals(5L, in.skip(5));
        in.reset();
        assertEquals(first, in.read());
        assertEquals(0L, in.skip(0));
        assertEquals(0L, in.skip(-4));
        in.close();
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void skipOnTheOldStreamAfterAHealthyCloseLeavesTheNextOwnerAlone() throws Exception {
        open();
        final InputStream in = contentRead(readStream(null, null));
        in.close();
        // The client went back and has a new owner now.
        assertSame(client.wrapper, fileSystem.getClient());
        assertThrows(IOException.class, () -> in.skip(1));
        assertEquals(-1, in.read());
        assertFalse(client.group.isAborted());
        assertTrue(client.wrapper.isAliveLocally());
        assertEquals(1, handleCloses());
    }

    @Test
    public void idleCloseKeepsTheClient() throws Exception {
        open();
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, readStream(null, null), file(false),
                handle(null));
        in.close();
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    // A write handle of an append. smbj asks the handle for the end of the file before it makes the stream.
    private File appendHandle(final RuntimeException endOfFileError, final RuntimeException closeError) {
        final File handle = mock(File.class);
        when(handle.getOutputStream(true)).thenCallRealMethod();
        when(handle.getOutputStream(null, true)).thenCallRealMethod();
        doThrow(endOfFileError).when(handle).getFileInformation(FileStandardInformation.class);
        doAnswer(call -> {
            order.add("handle-close aborted=" + client.group.isAborted());
            if (closeError != null) {
                throw closeError;
            }
            return null;
        }).when(handle).close();
        return handle;
    }

    private FileSystemException appendFails(final RuntimeException endOfFileError, final RuntimeException closeError)
            throws Exception {
        open();
        // The factory borrows the client itself.
        fileSystem.putClient(client.wrapper);
        final File handle = appendHandle(endOfFileError, closeError);
        doReturn(handle).when(client.wrapper).getDiskEntryWrite(anyString(), eq(true));
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> fileObject.doGetOutputStream(true));
        assertSame(endOfFileError, error.getCause());
        assertEquals(1, handleCloses());
        return error;
    }

    @Test
    public void appendRefusedAfterTheOpenClosesTheHandleOnceAndKeepsTheClient() throws Exception {
        final FileSystemException error = appendFails(refused(true), null);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertEquals(Arrays.asList("handle-close aborted=false"), order);
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void appendTimeoutAfterTheOpenAbortsBeforeTheHandleCloseAndRetiresTheClient() throws Exception {
        final FileSystemException error = appendFails(new SMBRuntimeException(new TransportException("timeout")),
                null);
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertEquals("handle-close aborted=true", order.get(0));
        assertNotSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void appendRefusedWithAHandleCloseTimeoutKeepsTheRefusalAndRetiresTheClient() throws Exception {
        final SMBRuntimeException closeError = new SMBRuntimeException(new TransportException("close timeout"));
        final FileSystemException error = appendFails(refused(true), closeError);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertSame(closeError, error.getSuppressed()[0]);
        assertEquals("handle-close aborted=false", order.get(0));
        assertNotSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void failedWriteOpenHasNoHandleToClose() throws Exception {
        open();
        fileSystem.putClient(client.wrapper);
        final SMBApiException denied = refused(false);
        doThrow(denied).when(client.wrapper).getDiskEntryWrite(anyString(), eq(true));
        assertSame(denied, assertThrows(FileSystemException.class, () -> fileObject.doGetOutputStream(true))
                .getCause());
        assertEquals(0, handleCloses());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void appendThatWorksGivesTheHandleToTheStream() throws Exception {
        open();
        fileSystem.putClient(client.wrapper);
        final File handle = mock(File.class);
        doAnswer(call -> {
            order.add("handle-close aborted=" + client.group.isAborted());
            return null;
        }).when(handle).close();
        when(handle.getOutputStream(true)).thenReturn(new ByteArrayOutputStream());
        doReturn(handle).when(client.wrapper).getDiskEntryWrite(anyString(), eq(true));
        final OutputStream out = fileObject.doGetOutputStream(true);
        assertEquals(0, handleCloses());
        out.write(new byte[16]);
        out.close();
        assertEquals(1, handleCloses());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void readSetupThatFailsAfterTheOpenClosesTheHandle() throws Exception {
        for (final boolean timeout : new boolean[] {false, true}) {
            order.clear();
            open();
            fileSystem.putClient(client.wrapper);
            final RuntimeException error = timeout ? new SMBRuntimeException(new TransportException("timeout"))
                    : refused(true);
            final File handle = mock(File.class);
            doThrow(error).when(handle).getInputStream();
            doAnswer(call -> {
                order.add("handle-close aborted=" + client.group.isAborted());
                return null;
            }).when(handle).close();
            doReturn(handle).when(client.wrapper).getDiskEntryRead(anyString());
            client.info(Smb2ClientWrapperTest.info(false));
            final FileSystemException thrown = assertThrows(FileSystemException.class, fileObject::doGetInputStream);
            assertSame(error, thrown.getCause());
            assertEquals(timeout ? Arrays.asList("handle-close aborted=true", "client-close aborted=true")
                    : Arrays.asList("handle-close aborted=false"), order);
            if (timeout) {
                assertNotSame(client.wrapper, fileSystem.getClient());
            } else {
                assertSame(client.wrapper, fileSystem.getClient());
            }
        }
    }

    // The stream of the file content wraps the SMB2 stream the same way. Its close catches only an IOException from
    // its flush, so an unchecked error of smbj there would stop it before it closes the SMB2 stream.
    private OutputStream contentStream(final OutputStream smb2) {
        return new MonitorOutputStream(smb2) {
            @Override
            protected void onClose() {
                order.add("content-close");
            }
        };
    }

    @Test
    public void closeOfTheContentStreamReachesTheHandleAndDiscardsALostClient() throws Exception {
        open();
        // The reader of smbj saw the connection die, so smbj closed the share, and its next write says so with no
        // cause.
        client.wrapper.connectionLost();
        order.clear();
        final SMBRuntimeException closed = new SMBRuntimeException("DiskShare has already been closed");
        final OutputStream out = contentStream(fileObject.new Smb2OutputStream(client.wrapper,
                writeStream(closed, null), handle(null)));
        // The bytes stay in the buffer of the content stream, so its flush in the close makes the first write.
        out.write(new byte[16]);
        final FileSystemException error = assertThrows(FileSystemException.class, out::close);
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertSame(closed, error.getCause());
        assertEquals(Arrays.asList("stream-close aborted=true", "handle-close aborted=true",
                "client-close aborted=true", "content-close"), order);
        assertEquals(0, fileSystem.getOpenClientCount());
        assertEquals(0, fileSystem.getHandedOutCount());
    }

    @Test
    public void closeOfTheContentStreamAfterARefusedWriteKeepsTheClient() throws Exception {
        open();
        final SMBApiException full = new SMBApiException(NtStatus.STATUS_DISK_FULL.getValue(),
                SMB2MessageCommandCode.SMB2_WRITE, "disk full", null);
        final OutputStream out = contentStream(fileObject.new Smb2OutputStream(client.wrapper,
                writeStream(full, null), handle(null)));
        out.write(new byte[16]);
        final FileSystemException error = assertThrows(FileSystemException.class, out::close);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertSame(full, error.getCause());
        assertEquals(Arrays.asList("stream-close aborted=false", "handle-close aborted=false", "content-close"), order);
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void closedShareOfAClientThatLostNothingIsNoTransportErrorAtTheCaller() throws Exception {
        open();
        // The share of the client is connected and its reader saw no loss, so the bare exception proves nothing.
        final SMBRuntimeException closed = new SMBRuntimeException("DiskShare has already been closed");
        final InputStream in = fileObject.new Smb2InputStream(client.wrapper, readStream(null, closed), file(false),
                handle(null));
        final FileSystemException error = assertThrows(FileSystemException.class, in::read);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertSame(closed, error.getCause());
        in.close();
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }

    @Test
    public void goodWriteClosesTheStreamAndTheHandleAndPutsTheClientBack() throws Exception {
        open();
        final OutputStream out = fileObject.new Smb2OutputStream(client.wrapper, writeStream(null, null),
                handle(null));
        out.write(new byte[16]);
        out.close();
        assertEquals(1, handleCloses());
        assertFalse(client.group.isAborted());
        assertSame(client.wrapper, fileSystem.getClient());
    }
}
