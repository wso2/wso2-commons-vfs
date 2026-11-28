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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * The memory rule of a mount on the clock linkmark of the test build, and the point publish.after-link on a local
 * path. A folder pair across two file systems can not link. A target folder that is a symbolic link is pointed to
 * the file system of the source when a test needs the link to work again.
 */
@Tag("testbuild")
public class LocalLinkPointsTest {

    private static final String DIR = "vfs.testpoints.dir";

    private static final long TEN_SECONDS = TimeUnit.SECONDS.toMillis(10);

    private static final long TEN_MINUTES = TimeUnit.MINUTES.toMillis(10);

    @TempDir
    Path base;

    @TempDir
    Path disk;

    private Path copy;

    private Path shm;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        TestPoints.event("link-test", null);
        try (Stream<Path> folders = Files.list(base)) {
            copy = folders.filter(folder -> Files.exists(folder.resolve("copy"))).findFirst().get();
        }
        manager = new StandardFileSystemManager();
        manager.init();
        final Path devShm = Paths.get("/dev/shm");
        shm = Files.isDirectory(devShm) && !Files.getFileStore(devShm).equals(Files.getFileStore(disk))
                ? Files.createTempDirectory(devShm, "linktest") : null;
    }

    @AfterEach
    public void stop() throws Exception {
        manager.close();
        clock(0);
        System.clearProperty(DIR);
        if (shm != null) {
            try (Stream<Path> files = Files.walk(shm)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private void clock(final long offsetMillis) throws Exception {
        final Path next = copy.resolve("clock.linkmark.mono.next");
        Files.write(next, String.valueOf(offsetMillis).getBytes(StandardCharsets.UTF_8));
        Files.move(next, copy.resolve("clock.linkmark.mono"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private AtomicResult link(final Path source, final Path target) throws Exception {
        Files.write(source, "v".getBytes(StandardCharsets.UTF_8));
        return AtomicOps.of(manager.resolveFile(source.toUri())).linkExclusive(manager.resolveFile(target.toUri()),
                "p.part");
    }

    private Answer pair(final Path source, final Path target) throws Exception {
        return AtomicOps.of(manager.resolveFile(source.toUri())).atomicCapabilities(manager.resolveFile(target.toUri()))
                .getExclusivePublish();
    }

    @Test
    public void markComesAtTheThirdResultTenSecondsApartLastsTenMinutesAndEndsWithALinkThatWorks()
            throws Exception {
        assumeTrue(shm != null, "the test needs a second file system");
        final Path sameFileSystem = Files.createDirectories(shm.resolve("same"));
        final Path otherFileSystem = Files.createDirectories(disk.resolve("other"));
        final Path out = Files.createSymbolicLink(disk.resolve("out"), otherFileSystem);
        final Path source = shm.resolve("a.tmp");

        // Three results inside 10 s mark nothing.
        clock(0);
        assertEquals(AtomicOutcome.UNSUPPORTED, link(source, out.resolve("a1")).getOutcome());
        clock(3000);
        assertEquals(AtomicOutcome.UNSUPPORTED, link(source, out.resolve("a2")).getOutcome());
        clock(6000);
        assertEquals(AtomicOutcome.UNSUPPORTED, link(source, out.resolve("a3")).getOutcome());
        assertEquals(Answer.NOT_KNOWN, pair(source, out.resolve("x")));
        // The second and the third counted result, each at least 10 s after the one before.
        clock(TEN_SECONDS);
        link(source, out.resolve("a4"));
        assertEquals(Answer.NOT_KNOWN, pair(source, out.resolve("x")));
        clock(2 * TEN_SECONDS);
        link(source, out.resolve("a5"));
        assertEquals(Answer.NO, pair(source, out.resolve("x")));
        // While the mark holds the call answers with no call on the mount, also after the mount can link.
        Files.delete(out);
        Files.createSymbolicLink(out, sameFileSystem);
        clock(2 * TEN_SECONDS + TEN_MINUTES - 1000);
        assertEquals(AtomicOutcome.UNSUPPORTED, link(source, out.resolve("a6")).getOutcome());
        assertFalse(Files.exists(out.resolve("a6")));
        // The first call after the mark ended links again.
        clock(2 * TEN_SECONDS + TEN_MINUTES);
        assertEquals(Answer.NOT_KNOWN, pair(source, out.resolve("x")));
        assertEquals(AtomicOutcome.DONE, link(source, out.resolve("a7")).getOutcome());
        assertEquals(Answer.YES, pair(source, out.resolve("x")));
    }

    @Test
    public void failureAfterTheMarkEndedMarksTenMoreMinutesWithNoCount() throws Exception {
        assumeTrue(shm != null, "the test needs a second file system");
        final Path out = Files.createDirectories(disk.resolve("out"));
        final Path source = shm.resolve("a.tmp");
        clock(0);
        link(source, out.resolve("a1"));
        clock(TEN_SECONDS);
        link(source, out.resolve("a2"));
        clock(2 * TEN_SECONDS);
        link(source, out.resolve("a3"));
        assertEquals(Answer.NO, pair(source, out.resolve("x")));
        clock(2 * TEN_SECONDS + TEN_MINUTES);
        assertEquals(Answer.NOT_KNOWN, pair(source, out.resolve("x")));
        assertEquals(AtomicOutcome.UNSUPPORTED, link(source, out.resolve("a4")).getOutcome());
        assertEquals(Answer.NO, pair(source, out.resolve("x")));
        clock(2 * TEN_SECONDS + 2 * TEN_MINUTES - 1000);
        assertEquals(Answer.NO, pair(source, out.resolve("x")));
        clock(2 * TEN_SECONDS + 2 * TEN_MINUTES);
        assertEquals(Answer.NOT_KNOWN, pair(source, out.resolve("x")));
    }

    private static String hash(final String key) throws Exception {
        final StringBuilder hex = new StringBuilder();
        for (final byte b : MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.substring(0, 16);
    }

    @Test
    public void publishPointComesAfterTheLinkAndBeforeTheDeleteOfTheTempFile() throws Exception {
        final Path part = Files.createDirectories(disk.resolve("out/.part"));
        final Path temp = part.resolve("t.bin.x.part");
        Files.write(temp, "v".getBytes(StandardCharsets.UTF_8));
        final FileObject target = manager.resolveFile(disk.resolve("out/t.bin").toUri());
        final String key = target.getName().getURI();
        final Path hold = copy.resolve("publish.after-link@k" + hash(key) + ".hold");
        Files.write(hold, "wait=30\n".getBytes(StandardCharsets.UTF_8));
        final AtomicOps ops = AtomicOps.of(manager.resolveFile(temp.toUri()));
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread caller = new Thread(() -> {
            try {
                result.set(ops.publishExclusive(target, "p.part"));
            } catch (final Exception e) {
                result.set(e);
            }
        }, "publish-caller");
        caller.start();
        final Path reached = copy.resolve("publish.after-link@k" + hash(key) + ".1.reached");
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(reached)) {
            assertTrue(System.nanoTime() < end, "the point was not reached");
            Thread.sleep(10);
        }
        // The target is published and the temp file is still there.
        assertTrue(Files.exists(disk.resolve("out/t.bin")));
        assertTrue(Files.exists(temp));
        Files.delete(hold);
        caller.join(10000);
        assertEquals(AtomicOutcome.DONE, ((AtomicResult) result.get()).getOutcome());
        assertFalse(Files.exists(temp));
    }
}
