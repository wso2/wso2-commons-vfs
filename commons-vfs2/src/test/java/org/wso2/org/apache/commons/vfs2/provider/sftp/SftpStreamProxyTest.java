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
package org.wso2.org.apache.commons.vfs2.provider.sftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.ProgressWatchdog;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;
import org.wso2.org.apache.commons.vfs2.util.StalledTransferException;

import com.jcraft.jsch.Session;

/**
 * The stream proxy: its input is read with a bound, its output is watched, and its proxy session is a child group of
 * the session to the target. The integration cases run a proxy server with an emulated netcat, and a target server
 * behind a relay that can go silent while the proxy stays healthy.
 */
public class SftpStreamProxyTest {

    private static final SftpFileSystemConfigBuilder BUILDER = SftpFileSystemConfigBuilder.getInstance();

    private SftpTestServer proxy;

    private SftpTestServer target;

    private TestRelay relay;

    @BeforeEach
    public void start() throws Exception {
        proxy = new SftpTestServer();
        target = new SftpTestServer();
        target.write("in/small.txt", "small");
        final byte[] big = new byte[4 * 1024 * 1024];
        new Random(7).nextBytes(big);
        Files.write(target.getRoot().resolve("in").resolve("big.bin"), big);
        relay = new TestRelay(target.getPort());
    }

    @AfterEach
    public void stop() throws Exception {
        relay.close();
        target.close();
        proxy.close();
    }

    private FileSystemOptions throughProxy() throws Exception {
        return throughProxy(proxy.getPort());
    }

    // The proxy server at the given port, for a relay in front of it.
    private FileSystemOptions throughProxy(final int proxyPort) throws Exception {
        final FileSystemOptions options = SftpTestServer.options();
        BUILDER.setProxyType(options, SftpFileSystemConfigBuilder.PROXY_STREAM);
        BUILDER.setProxyHost(options, "localhost");
        BUILDER.setProxyPort(options, proxyPort);
        BUILDER.setProxyUser(options, SftpTestServer.USER);
        BUILDER.setProxyPassword(options, SftpTestServer.USER);
        BUILDER.setProxyCommand(options, SftpStreamProxy.NETCAT_COMMAND);
        BUILDER.setProxyOptions(options, SftpTestServer.options());
        return options;
    }

    private String targetUri(final String path) {
        return SftpTestServer.uri(relay.getPort(), path);
    }

    private static StandardFileSystemManager manager() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        return manager;
    }

    private static long millisSince(final long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void awaitTrue(final String what, final java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail(what);
            }
            Thread.sleep(20);
        }
    }

    // The bounded input on its own, on a plain pipe.

    @Test
    public void zeroLengthReadReturnsZero() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 100);
        assertEquals(0, input.read(new byte[4], 0, 0));
    }

    @Test
    public void bytesThatAreThereComeAtOnceAndNeverMoreThanAreThere() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        writer.write(new byte[] {1, 2, 3});
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 60000);
        final byte[] buffer = new byte[100];
        final long start = System.nanoTime();
        assertEquals(3, input.read(buffer, 0, buffer.length));
        assertTrue(millisSince(start) < 1000);
        assertArrayEquals(new byte[] {1, 2, 3}, Arrays.copyOf(buffer, 3));
        assertEquals(0, pipe.available());
    }

    @Test
    public void silentLiveChannelEndsAtTheTimeout() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 300);
        final long start = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> input.read(new byte[8], 0, 8));
        final long elapsed = millisSince(start);
        assertTrue(elapsed >= 300 && elapsed < 1300, "elapsed " + elapsed);
    }

    @Test
    public void timeoutOfZeroMeansNoBound() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 0);
        final CompletableFuture<Void> late = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(700);
                writer.write(9);
            } catch (final IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        final long start = System.nanoTime();
        assertEquals(9, input.read());
        assertTrue(millisSince(start) >= 600);
        late.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void endWithBufferedBytesGivesTheBytesAndThenTheEnd() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        writer.write(new byte[] {4, 5, 6, 7, 8});
        writer.close();
        final AtomicBoolean ended = new AtomicBoolean(true);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, ended::get, 60000);
        final byte[] buffer = new byte[3];
        assertEquals(3, input.read(buffer, 0, 3));
        assertEquals(2, input.read(buffer, 0, 3));
        assertEquals(-1, input.read(buffer, 0, 3));
    }

    @Test
    public void setupThatPassedItsDeadlineTakesNoByteThatIsThereAndASetupInTimeDoes() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        writer.write(42);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 15000);
        input.setSetupDeadline(System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1));
        assertThrows(SocketTimeoutException.class, input::read);
        input.setSetupDeadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        assertEquals(42, input.read());
    }

    @Test
    public void channelWaitGivesEachOfTheTwoWaitsOfJschAtMostHalfOfTheTimeThatIsLeft() throws Exception {
        // With no setup deadline the timeout of the endpoint stays as it is.
        assertEquals(18000, SftpStreamProxy.channelWait(18000, 0));
        assertEquals(0, SftpStreamProxy.channelWait(0, 0));
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final int bounded = SftpStreamProxy.channelWait(18000, deadline);
        assertTrue(bounded > 4500 && bounded <= 5000, "wait " + bounded);
        final int unbounded = SftpStreamProxy.channelWait(0, deadline);
        assertTrue(unbounded > 4500 && unbounded <= 5000, "wait " + unbounded);
        // A shorter timeout of the endpoint stays.
        assertEquals(2000, SftpStreamProxy.channelWait(2000, deadline));
        assertThrows(SocketTimeoutException.class,
                () -> SftpStreamProxy.channelWait(18000, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1)));
    }

    @Test
    public void interruptKeepsTheFlagAndThrowsInterruptedIOException() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 0);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> input.read(new byte[1], 0, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void slowProgressIsNoTimeout() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 500);
        final CompletableFuture<Void> trickle = CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 8; i++) {
                    Thread.sleep(250);
                    writer.write(i);
                }
            } catch (final IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        for (int i = 0; i < 8; i++) {
            assertEquals(i, input.read());
        }
        trickle.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void boundSwitchesWhenTheSessionIsConnectedAndCloseEndsTheStream() throws Exception {
        final PipedInputStream pipe = new PipedInputStream();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 5000);
        input.setTimeout(200);
        final long start = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> input.read());
        assertTrue(millisSince(start) < 2000);
        input.close();
        assertEquals(-1, input.read());
    }

    @Test
    public void fullPipeDoesNotHoldItsWriterBackForASecond() throws Exception {
        // A pipe of 32 KB, as JSch gives for a channel, and a writer that fills it again and again.
        final PipedInputStream pipe = new PipedInputStream(32 * 1024);
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        final int total = 2 * 1024 * 1024;
        final CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
            try {
                final byte[] chunk = new byte[8 * 1024];
                for (int sent = 0; sent < total; sent += chunk.length) {
                    writer.write(chunk);
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 0);
        final byte[] buffer = new byte[32 * 1024];
        final long start = System.nanoTime();
        int got = 0;
        while (got < total) {
            got += input.read(buffer, 0, buffer.length);
        }
        final long elapsed = millisSince(start);
        producer.get(5, TimeUnit.SECONDS);
        // 64 fills of the pipe. A writer that waited 1 s at each fill would take about a minute.
        assertTrue(elapsed < 5000, "2 MB took " + elapsed + " ms");
    }

    // A pipe that tells when a read first looked at it, so a test changes the bound of a read that already waits.
    private static final class WatchedPipe extends PipedInputStream {

        private final CountDownLatch looked = new CountDownLatch(1);

        @Override
        public synchronized int available() throws IOException {
            looked.countDown();
            return super.available();
        }

        void awaitRead() throws InterruptedException {
            assertTrue(looked.await(5, TimeUnit.SECONDS), "the read started");
        }
    }

    private static CompletableFuture<Object> waitingRead(final SftpStreamProxy.BoundedInput input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return input.read();
            } catch (final IOException e) {
                return e;
            }
        });
    }

    @Test
    public void readThatWaitsWithNoBoundTakesTheBoundOfTheSwitch() throws Exception {
        final WatchedPipe pipe = new WatchedPipe();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 0);
        final CompletableFuture<Object> read = waitingRead(input);
        pipe.awaitRead();
        Thread.sleep(300);
        assertFalse(read.isDone());
        final long switched = System.nanoTime();
        input.setTimeout(50);
        final Object result = read.get(5, TimeUnit.SECONDS);
        final long elapsed = millisSince(switched);
        assertTrue(result instanceof SocketTimeoutException, String.valueOf(result));
        assertTrue(elapsed >= 50 && elapsed < 1000, "elapsed " + elapsed);
    }

    @Test
    public void readThatWaitsWithABoundHasNoBoundAfterASwitchToZeroAndGetsItsBytes() throws Exception {
        final WatchedPipe pipe = new WatchedPipe();
        final PipedOutputStream writer = new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 400);
        final CompletableFuture<Object> read = waitingRead(input);
        pipe.awaitRead();
        input.setTimeout(0);
        Thread.sleep(1000);
        assertFalse(read.isDone());
        writer.write(42);
        assertEquals(42, read.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void readThatWaitsTakesTheShorterBoundFromTheMomentOfTheSwitch() throws Exception {
        final WatchedPipe pipe = new WatchedPipe();
        new PipedOutputStream(pipe);
        final SftpStreamProxy.BoundedInput input = new SftpStreamProxy.BoundedInput(pipe, () -> false, 5000);
        final long start = System.nanoTime();
        final CompletableFuture<Object> read = waitingRead(input);
        pipe.awaitRead();
        Thread.sleep(300);
        final long switched = System.nanoTime();
        input.setTimeout(200);
        final Object result = read.get(10, TimeUnit.SECONDS);
        final long sinceSwitch = millisSince(switched);
        assertTrue(result instanceof SocketTimeoutException, String.valueOf(result));
        assertTrue(sinceSwitch >= 200 && sinceSwitch < 1500, "since the switch " + sinceSwitch);
        assertTrue(millisSince(start) < 3000);
    }

    @Test
    public void readThatWaitsAfterASwitchStillEndsAtTheEndAtACloseAndAtAnInterrupt() throws Exception {
        final AtomicBoolean ended = new AtomicBoolean();
        final WatchedPipe endPipe = new WatchedPipe();
        final PipedOutputStream endWriter = new PipedOutputStream(endPipe);
        final SftpStreamProxy.BoundedInput atEnd = new SftpStreamProxy.BoundedInput(endPipe, ended::get, 0);
        final byte[] buffer = new byte[4];
        final CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try {
                return atEnd.read(buffer, 0, 4);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        });
        endPipe.awaitRead();
        atEnd.setTimeout(60000);
        endWriter.write(new byte[] {1, 2});
        ended.set(true);
        assertEquals(2, first.get(5, TimeUnit.SECONDS));
        assertEquals(-1, atEnd.read());

        final WatchedPipe closePipe = new WatchedPipe();
        new PipedOutputStream(closePipe);
        final SftpStreamProxy.BoundedInput closed = new SftpStreamProxy.BoundedInput(closePipe, () -> false, 0);
        final CompletableFuture<Object> waiting = waitingRead(closed);
        closePipe.awaitRead();
        closed.setTimeout(60000);
        closed.close();
        assertEquals(-1, waiting.get(5, TimeUnit.SECONDS));

        final WatchedPipe interruptPipe = new WatchedPipe();
        new PipedOutputStream(interruptPipe);
        final SftpStreamProxy.BoundedInput interrupted = new SftpStreamProxy.BoundedInput(interruptPipe, () -> false,
            0);
        final AtomicReference<Thread> reader = new AtomicReference<>();
        final CompletableFuture<Object> blocked = CompletableFuture.supplyAsync(() -> {
            reader.set(Thread.currentThread());
            try {
                return interrupted.read();
            } catch (final IOException e) {
                return e;
            } finally {
                Thread.interrupted();
            }
        });
        interruptPipe.awaitRead();
        interrupted.setTimeout(60000);
        reader.get().interrupt();
        assertTrue(blocked.get(5, TimeUnit.SECONDS) instanceof InterruptedIOException);
    }

    // The proxy with real sessions.

    @Test
    public void sessionThroughTheProxyReadsAndHasNoSocketOfItsOwn() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), throughProxy());
            try (InputStream in = file.getContent().getInputStream()) {
                assertEquals("small", new String(readAll(in), "UTF-8"));
            }
            assertEquals(1, relay.getAccepted());
            assertEquals(1, proxy.getCommands().stream().filter(c -> c.startsWith("nc ")).count());
        }
    }

    @Test
    public void silentTargetBehindAHealthyProxyEndsAReadAtTheReadBound() throws Exception {
        final FileSystemOptions options = throughProxy();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(1000));
        BUILDER.setKeepAliveCountMax(options, 2);
        BUILDER.setConnectTimeout(options, Duration.ofMillis(1000));
        try (StandardFileSystemManager manager = manager()) {
            final String test = "silentTargetBehindAHealthyProxyEndsAReadAtTheReadBound";
            final FileObject file = SetupConnect.withRetry(test,
                    () -> manager.resolveFile(targetUri("in/big.bin"), options));
            final InputStream in = SetupConnect.withRetry(test, () -> {
                final InputStream opened = file.getContent().getInputStream();
                try {
                    assertTrue(opened.read(new byte[1024]) > 0);
                } catch (final IOException e) {
                    throw SetupConnect.closed(opened, e);
                }
                return opened;
            });
            relay.pause();
            final long start = System.nanoTime();
            final IOException error = assertThrows(IOException.class, () -> {
                final byte[] buffer = new byte[64 * 1024];
                while (in.read(buffer) >= 0) {
                    // Read on until the bound ends the read.
                }
            });
            final long elapsed = millisSince(start);
            assertNotNull(error);
            // Timeout times count plus one is 3 s. The proxy session answered its own keep alives all the time.
            assertTrue(elapsed >= 2000 && elapsed < 6000, "elapsed " + elapsed);
            try {
                in.close();
            } catch (final IOException e) {
                // The session is gone.
            }
        }
    }

    @Test
    public void keepAliveCountDecidesHowManyReadTimeoutsEndTheNestedSession() throws Exception {
        final long zero = silentStatTime(0);
        final long three = silentStatTime(3);
        assertTrue(zero < 2500, "count 0 took " + zero);
        assertTrue(three >= 3500, "count 3 took " + three);
    }

    // The time until a stat on a silent target fails, with a session timeout of 1 s. The stat retry is not in the
    // count: a stat that fails after 5 s or more gets no retry, and one that fails earlier gets one, which then fails
    // at the connect bound of 1 s.
    private long silentStatTime(final int count) throws Exception {
        final FileSystemOptions options = throughProxy();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(1000));
        BUILDER.setKeepAliveCountMax(options, count);
        BUILDER.setConnectTimeout(options, Duration.ofMillis(1000));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = SetupConnect.withRetry("silentStatTime", () -> {
                final FileObject resolved = manager.resolveFile(targetUri("in/small.txt"), options);
                assertTrue(resolved.exists());
                return resolved;
            });
            relay.pause();
            final long start = System.nanoTime();
            file.refresh();
            assertThrows(FileSystemException.class, file::exists);
            return millisSince(start);
        } finally {
            relay.resume();
        }
    }

    @Test
    public void publicPermissionCallThroughTheProxyEndsWithinTheProbeTimeoutWhenTheTargetHoldsItsBanner()
            throws Exception {
        target.setIds("4242", "4242 77");
        final FileSystemOptions options = throughProxy();
        BUILDER.setConnectTimeout(options, Duration.ofMillis(15000));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            final Session before = SftpTestServer.sessionOf(fileSystem);
            // A new connection to the target gets no banner, and the session to the target ends. The proxy server
            // stays up. The emulated netcat notices a cut only at the read bound, so the session is ended directly.
            relay.holdNewConnections(true);
            before.disconnect();
            assertFalse(before.isConnected());
            final long start = System.nanoTime();
            final Exception error = assertThrows(Exception.class, fileSystem::getUId);
            final long elapsed = millisSince(start);
            assertTrue(elapsed >= 9000 && elapsed <= 11000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertTrue(target.getCommands().isEmpty());
            relay.holdNewConnections(false);
            assertEquals(4242, fileSystem.getUId());
        }
    }

    @Test
    public void publicPermissionCallThroughTheProxyEndsWithinTheProbeTimeoutWhenTheProxyDelaysBothAcknowledgements()
            throws Exception {
        target.setIds("4242", "4242 77");
        final FileSystemOptions options = throughProxy();
        BUILDER.setConnectTimeout(options, Duration.ofMillis(18000));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), options);
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            SftpTestServer.sessionOf(fileSystem).disconnect();
            // The proxy server answers the open of the channel to the target and its request each after 6200 ms.
            proxy.setDelays(6200, 6200, 0);
            final long start = System.nanoTime();
            final Exception error = assertThrows(Exception.class, fileSystem::getUId);
            final long elapsed = millisSince(start);
            assertTrue(elapsed <= 11000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertTrue(target.getCommands().isEmpty());
            proxy.setDelays(0, 0, 0);
            assertEquals(4242, fileSystem.getUId());
        }
    }

    @Test
    public void callerThatIsInterruptedAlreadyOpensNoConnectionThroughTheProxyAndTheNextCallWorks() throws Exception {
        target.setIds("4242", "4242 77");
        try (TestRelay jump = new TestRelay(proxy.getPort()); StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), throughProxy(jump.getPort()));
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            SftpTestServer.sessionOf(fileSystem).disconnect();
            final int proxyCommands = proxy.getCommands().size();
            Thread.currentThread().interrupt();
            final long start = System.nanoTime();
            final Exception error;
            final boolean kept;
            try {
                error = assertThrows(Exception.class, fileSystem::getUId);
            } finally {
                kept = Thread.interrupted();
            }
            final long elapsed = millisSince(start);
            assertTrue(kept, "the caller keeps its interrupt");
            assertTrue(elapsed < 1000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            assertTrue(hasCause(error, InterruptedIOException.class), String.valueOf(error));
            assertEquals(1, jump.getAccepted(), "no new connection to the proxy");
            assertEquals(1, relay.getAccepted(), "no new connection to the target");
            assertEquals(proxyCommands, proxy.getCommands().size());
            assertTrue(target.getCommands().isEmpty());
            assertEquals(4242, fileSystem.getUId());
            assertEquals(2, jump.getAccepted());
            assertEquals(Arrays.asList("id -u", "id -G"), target.getCommands());
        }
    }

    @Test
    public void interruptDuringTheSetupOfTheProxySessionEndsBeforeTheChannelToTheTargetAndKeepsTheFlag()
            throws Exception {
        target.setIds("4242", "4242 77");
        try (TestRelay jump = new TestRelay(proxy.getPort()); StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), throughProxy(jump.getPort()));
            assertTrue(file.exists());
            final SftpFileSystem fileSystem = (SftpFileSystem) file.getFileSystem();
            SftpTestServer.sessionOf(fileSystem).disconnect();
            awaitTrue("the old proxy session is closed", () -> proxy.getActiveSessions() == 0);
            final int proxyCommands = proxy.getCommands().size();
            jump.holdNewConnections(true);
            final AtomicReference<Throwable> error = new AtomicReference<>();
            final AtomicBoolean kept = new AtomicBoolean();
            final Thread caller = new Thread(() -> {
                try {
                    fileSystem.getUId();
                } catch (final Exception e) {
                    error.set(e);
                }
                kept.set(Thread.interrupted());
            }, "permission-caller");
            caller.start();
            // The caller has a new connection to the proxy, which holds its banner. The interrupt comes now, and the
            // setup of the proxy session goes on after the release.
            awaitTrue("the caller connects to the proxy", () -> jump.getAccepted() == 2);
            caller.interrupt();
            jump.holdNewConnections(false);
            caller.join(15000);
            assertFalse(caller.isAlive());
            assertNotNull(error.get());
            assertTrue(kept.get(), "the caller keeps its interrupt");
            assertEquals(Fault.TRANSPORT, Faults.of(error.get()));
            assertTrue(hasCause(error.get(), InterruptedIOException.class), String.valueOf(error.get()));
            // The proxy session of the attempt was set up, and no channel to the target was asked for.
            assertEquals(proxyCommands, proxy.getCommands().size());
            assertEquals(1, relay.getAccepted(), "no new connection to the target");
            assertTrue(target.getCommands().isEmpty());
            awaitTrue("the proxy session of the attempt is closed", () -> proxy.getActiveSessions() == 0);
            assertEquals(4242, fileSystem.getUId());
            assertEquals(Arrays.asList("id -u", "id -G"), target.getCommands());
        }
    }

    @Test
    public void healthyIdleSessionStaysUp() throws Exception {
        final FileSystemOptions options = throughProxy();
        BUILDER.setSessionTimeout(options, Duration.ofMillis(500));
        BUILDER.setKeepAliveCountMax(options, 2);
        // With no connect timeout of its own the connect would take the 500 ms of the session, which is too short
        // for a handshake through the proxy on a busy machine. This test is about the idle session only.
        BUILDER.setConnectTimeout(options, Duration.ofMillis(10000));
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/small.txt"), options);
            assertTrue(file.exists());
            // Six read timeouts, each one answered by a keep alive.
            Thread.sleep(3000);
            file.refresh();
            assertTrue(file.exists());
            assertEquals(1, relay.getAccepted());
        }
    }

    @Test
    public void connectToASilentTargetEndsAtTheConnectBound() throws Exception {
        relay.holdNewConnections(true);
        final FileSystemOptions options = throughProxy();
        BUILDER.setConnectTimeout(options, Duration.ofMillis(1500));
        try (StandardFileSystemManager manager = manager()) {
            final long start = System.nanoTime();
            final FileSystemException error = assertThrows(FileSystemException.class,
                    () -> manager.resolveFile(targetUri("in/small.txt"), options));
            final long elapsed = millisSince(start);
            assertTrue(elapsed >= 1400 && elapsed < 5000, "elapsed " + elapsed);
            assertEquals(Fault.TRANSPORT, Faults.of(error));
            awaitTrue("the proxy session is closed after the failed connect", () -> proxy.getActiveSessions() == 0);
        } finally {
            relay.holdNewConnections(false);
        }
    }

    @Test
    public void abortDuringTheConnectClosesTheProxySocketAtOnce() throws Exception {
        relay.holdNewConnections(true);
        final FileSystemOptions options = throughProxy();
        try (StandardFileSystemManager manager = manager()) {
            final CompletableFuture<Long> connect = CompletableFuture.supplyAsync(() -> {
                try {
                    manager.resolveFile(targetUri("in/small.txt"), options);
                    return -1L;
                } catch (final FileSystemException e) {
                    return System.nanoTime();
                }
            });
            awaitTrue("the proxy has a session", () -> proxy.getActiveSessions() == 1);
            Thread.sleep(500);
            final long abort = System.nanoTime();
            manager.abortAll();
            final long failed = connect.get(10, TimeUnit.SECONDS);
            assertTrue(failed > 0, "the connect failed");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(failed - abort) < 2000, "the connect ended at once");
            awaitTrue("the proxy session is closed", () -> proxy.getActiveSessions() == 0);
        } finally {
            relay.holdNewConnections(false);
        }
    }

    @Test
    public void childThatIsRegisteredAfterAnAbortIsAbortedAndOpensNoSession() throws Exception {
        final SocketGroup owner = new SocketGroup();
        owner.abort("stopped");
        final SftpStreamProxy streamProxy = new SftpStreamProxy(SftpStreamProxy.NETCAT_COMMAND, SftpTestServer.USER,
                "localhost", proxy.getPort(), SftpTestServer.USER, SftpTestServer.options(), owner, 1000, 60000);
        assertThrows(Exception.class, () -> streamProxy.connect(null, "localhost", relay.getPort(), 1000));
        assertTrue(streamProxy.getGroup().isAborted());
        assertEquals("stopped", streamProxy.getGroup().reason());
        assertTrue(streamProxy.getGroup().isEmpty());
        assertTrue(owner.isEmpty());
        Thread.sleep(300);
        assertEquals(0, proxy.getActiveSessions());
    }

    @Test
    public void closeTakesTheChildOutOfItsOwnerAndASecondCloseChangesNothing() throws Exception {
        final SocketGroup owner = new SocketGroup();
        final SftpStreamProxy streamProxy = new SftpStreamProxy(SftpStreamProxy.NETCAT_COMMAND, SftpTestServer.USER,
                "localhost", proxy.getPort(), SftpTestServer.USER, SftpTestServer.options(), owner, 1000, 60000);
        streamProxy.connect(null, "localhost", relay.getPort(), 5000);
        assertFalse(owner.isEmpty());
        assertFalse(streamProxy.getGroup().getSockets().isEmpty());
        streamProxy.close();
        assertTrue(owner.isEmpty());
        streamProxy.close();
        assertTrue(owner.isEmpty());
        awaitTrue("the proxy session is closed", () -> proxy.getActiveSessions() == 0);
    }

    @Test
    public void abortOfTheManagerReachesTheRawSocketOfTheProxy() throws Exception {
        try (StandardFileSystemManager manager = manager()) {
            final FileObject file = manager.resolveFile(targetUri("in/big.bin"), throughProxy());
            final InputStream in = file.getContent().getInputStream();
            assertTrue(in.read(new byte[1024]) > 0);
            relay.pause();
            final CompletableFuture<Long> reader = CompletableFuture.supplyAsync(() -> {
                final byte[] buffer = new byte[64 * 1024];
                try {
                    while (in.read(buffer) >= 0) {
                        // Read on until the abort ends the read.
                    }
                    return -1L;
                } catch (final IOException e) {
                    return System.nanoTime();
                }
            });
            Thread.sleep(1000);
            assertFalse(reader.isDone());
            final long abort = System.nanoTime();
            manager.abortAll();
            final long ended = reader.get(10, TimeUnit.SECONDS);
            assertTrue(ended > 0, "the read failed");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(ended - abort) < 2000, "the read ended at once");
            awaitTrue("the proxy session is closed", () -> proxy.getActiveSessions() == 0);
        }
    }

    @Test
    public void writeThatFlushesAgainstAClosedChannelWindowEndsAtTheStallTimeout() throws Exception {
        // The channel of the proxy gets a small window and the session to the target a large one. So the window of
        // the proxy channel closes first, and the write of the session to the target waits inside the watched output.
        proxy.close();
        target.close();
        relay.close();
        proxy = new SftpTestServer(64 * 1024);
        target = new SftpTestServer(512 * 1024 * 1024);
        relay = new TestRelay(target.getPort());
        final FileSystemOptions options = throughProxy();
        BUILDER.setWriteStallTimeout(options, Duration.ofMillis(2000));
        BUILDER.setSessionTimeout(options, Duration.ofMillis(120000));
        try (StandardFileSystemManager manager = manager();
                LogCapture log = new LogCapture(ProgressWatchdog.class)) {
            final FileObject file = manager.resolveFile(targetUri("out/c32.bin"), options);
            final OutputStream out = file.getContent().getOutputStream();
            final byte[] chunk = new byte[32 * 1024];
            out.write(chunk);
            out.flush();
            relay.pause();
            final long[] lastProgress = {System.nanoTime()};
            final long throwAt;
            try {
                for (int i = 0; i < 8192; i++) {
                    out.write(chunk);
                    lastProgress[0] = System.nanoTime();
                }
                out.close();
                fail("the write was not ended");
                return;
            } catch (final IOException e) {
                throwAt = System.nanoTime();
                assertTrue(hasCause(e, StalledTransferException.class), "a stalled transfer in the cause chain");
                assertEquals(Fault.TRANSPORT, Faults.of(e));
            }
            final long sinceProgress = TimeUnit.NANOSECONDS.toMillis(throwAt - lastProgress[0]);
            assertTrue(sinceProgress < 2000 + 1000 + 1000, "ended " + sinceProgress + " ms after the last progress");
            awaitTrue("one warning of the watchdog", () -> log.messages(Level.WARN).size() == 1);
            assertTrue(log.messages(Level.WARN).get(0).startsWith("A write to localhost moved no bytes for "),
                    log.messages(Level.WARN).toString());
            // The close of the stream connects again, so the target answers again first.
            relay.resume();
            try {
                out.close();
            } catch (final IOException e) {
                // The session is gone.
            }
        }
    }

    private static boolean hasCause(final Throwable error, final Class<?> type) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (type.isInstance(link)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
}
