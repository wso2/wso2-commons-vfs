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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.FileType;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;

/**
 * The probe of {@code findFile} and its fast window. The provider of the test makes
 * file systems whose files answer {@code exists()} with an error after a time that the test gives.
 */
public class FindFileProbeTest {

    static final String URI = "probe://user:secret@127.0.0.1:2121/in/small.txt";

    private DefaultFileSystemManager manager;

    private ProbeProvider provider;

    @BeforeEach
    public void start() throws FileSystemException {
        provider = new ProbeProvider();
        manager = new DefaultFileSystemManager();
        manager.addProvider("probe", provider);
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
    }

    @Test
    public void fileThatAnswersComesFromTheCachedFileSystem() throws Exception {
        final FileObject first = manager.resolveFile(URI);
        final FileObject second = manager.resolveFile(URI);
        assertSame(first.getFileSystem(), second.getFileSystem());
        assertEquals(1, provider.made.size());
        assertEquals(2, provider.made.get(0).answers.get());
        assertEquals(0, provider.made.get(0).aborts.get());
    }

    @Test
    public void errorThatIsNoTransportErrorIsThrownWithNoAbortAndNoRebuild() throws Exception {
        for (final Fault fault : new Fault[] {Fault.APPLICATION, Fault.BUSY, Fault.MISSING}) {
            manager.resolveFile(URI);
            final ProbeFileSystem cached = provider.last();
            final FileSystemException refused = new FileSystemException("refused by the server");
            refused.setFault(fault);
            cached.failNext(refused, 0);
            final FileSystemException thrown = assertThrows(FileSystemException.class, () -> manager.resolveFile(URI));
            assertSame(refused, thrown.getCause());
            assertEquals(fault, Faults.of(thrown));
            assertEquals(0, cached.aborts.get());
            assertFalse(cached.closed);
            assertSame(cached, provider.last());
            // The file system stays in the cache and serves the next resolve.
            assertSame(cached, manager.resolveFile(URI).getFileSystem());
        }
    }

    @Test
    public void fastTransportErrorAbortsAndRebuildsOnceInTheWindowOfTheProbe() throws Exception {
        manager.resolveFile(URI);
        final ProbeFileSystem cached = provider.last();
        cached.failNext(new FileSystemException("read failed", new SocketException("Connection reset")), 0);
        final long before = System.nanoTime();
        final FileObject file = manager.resolveFile(URI);
        final ProbeFileSystem rebuilt = provider.last();

        assertNotSame(cached, rebuilt);
        assertSame(rebuilt, file.getFileSystem());
        assertEquals(2, provider.made.size());
        assertEquals(1, cached.aborts.get());
        assertTrue(cached.closed);
        // The probe does not run exists() again on the new file system.
        assertEquals(0, rebuilt.answers.get());
        // The rebuild got the start of the window of the probe, which opened right before exists().
        final long window = provider.windows.get(1);
        assertTrue(window >= before, "the window opened before the resolve");
        assertTrue(window <= cached.lastAnswerAt, "the window opened after exists() was entered");
        assertTrue(file.exists());
    }

    @Test
    public void slowTransportErrorAbortsAndThrowsWithNoRebuild() throws Exception {
        manager.resolveFile(URI);
        final ProbeFileSystem cached = provider.last();
        final FileSystemException timeout = new FileSystemException("read failed",
                new SocketTimeoutException("Read timed out"));
        cached.failNext(timeout, 5100);
        final long start = System.nanoTime();
        final FileSystemException thrown = assertThrows(FileSystemException.class, () -> manager.resolveFile(URI));
        final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertSame(timeout, thrown.getCause());
        assertEquals(Fault.TRANSPORT, Faults.of(thrown));
        assertEquals(1, provider.made.size());
        assertEquals(1, cached.aborts.get());
        assertTrue(cached.closed);
        assertTrue(millis < 6000, "took " + millis + " ms");
        // The next resolve builds a fresh file system.
        final FileObject next = manager.resolveFile(URI);
        assertNotSame(cached, next.getFileSystem());
        assertEquals(2, provider.made.size());
    }

    @Test
    public void rebuildThatFailsThrowsItsOwnError() throws Exception {
        manager.resolveFile(URI);
        final ProbeFileSystem cached = provider.last();
        final FileSystemException refused = new FileSystemException("connect refused");
        refused.setFault(Fault.TRANSPORT);
        provider.failNextCreate(refused);
        cached.failNext(new FileSystemException("read failed", new SocketException("Connection reset")), 0);
        final FileSystemException thrown = assertThrows(FileSystemException.class, () -> manager.resolveFile(URI));
        assertSame(refused, thrown);
        assertEquals(1, cached.aborts.get());
        assertTrue(cached.closed);
        assertEquals(1, provider.made.size());
    }

    @Test
    public void interruptIsATransportErrorOfTheProbe() throws Exception {
        manager.resolveFile(URI);
        final ProbeFileSystem cached = provider.last();
        cached.failNext(new FileSystemException("read failed", new InterruptedException()), 0);
        final FileObject file = manager.resolveFile(URI);
        assertEquals(1, cached.aborts.get());
        assertNotSame(cached, file.getFileSystem());
    }

    /**
     * A provider that makes {@link ProbeFileSystem}s and notes the start of the window that each creation got.
     */
    static final class ProbeProvider extends AbstractOriginatingFileProvider {

        final List<ProbeFileSystem> made = new CopyOnWriteArrayList<>();

        final List<Long> windows = new CopyOnWriteArrayList<>();

        private volatile FileSystemException createFailure;

        ProbeProvider() {
            setFileNameParser(new HostFileNameParser(21));
        }

        ProbeFileSystem last() {
            return made.get(made.size() - 1);
        }

        void failNextCreate(final FileSystemException failure) {
            createFailure = failure;
        }

        @Override
        protected FileSystem doCreateFileSystem(final FileName rootName, final FileSystemOptions options)
                throws FileSystemException {
            windows.add(windowStart);
            final FileSystemException failure = createFailure;
            if (failure != null) {
                createFailure = null;
                throw failure;
            }
            final ProbeFileSystem fileSystem = new ProbeFileSystem(rootName, options);
            made.add(fileSystem);
            return fileSystem;
        }

        @Override
        public Collection<Capability> getCapabilities() {
            return Collections.singletonList(Capability.READ_CONTENT);
        }
    }

    /**
     * A file system whose files answer {@code exists()} as the test asks. It notes each abort and its close.
     */
    static final class ProbeFileSystem extends AbstractFileSystem {

        final AtomicInteger aborts = new AtomicInteger();

        final AtomicInteger answers = new AtomicInteger();

        volatile boolean closed;

        volatile long lastAnswerAt;

        private Exception failure;

        private long delayMillis;

        ProbeFileSystem(final FileName rootName, final FileSystemOptions options) {
            super(rootName, null, options);
        }

        synchronized void failNext(final Exception error, final long delay) {
            failure = error;
            delayMillis = delay;
        }

        FileType answer() throws Exception {
            lastAnswerAt = System.nanoTime();
            answers.incrementAndGet();
            final Exception error;
            final long delay;
            synchronized (this) {
                error = failure;
                delay = delayMillis;
                failure = null;
            }
            if (error != null) {
                Thread.sleep(delay);
                throw error;
            }
            return FileType.FILE;
        }

        @Override
        public void abort() {
            aborts.incrementAndGet();
        }

        @Override
        protected void doCloseCommunicationLink() {
            closed = true;
        }

        @Override
        protected FileObject createFile(final AbstractFileName name) {
            return new ProbeFile(name, this);
        }

        @Override
        protected void addCapabilities(final Collection<Capability> caps) {
            caps.add(Capability.READ_CONTENT);
        }
    }

    private static final class ProbeFile extends AbstractFileObject<ProbeFileSystem> {

        ProbeFile(final AbstractFileName name, final ProbeFileSystem fileSystem) {
            super(name, fileSystem);
        }

        @Override
        protected FileType doGetType() throws Exception {
            return getAbstractFileSystem().answer();
        }

        @Override
        protected String[] doListChildren() {
            return null;
        }

        @Override
        protected long doGetContentSize() {
            return 0;
        }
    }
}
