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
package org.wso2.org.apache.commons.vfs2.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mechanism of the test form of {@link TestPoints}. It runs only in the profile testbuild.
 */
@Tag("testbuild")
public class TestPointsTest {

    private static final String DIR = "vfs.testpoints.dir";
    private static final long RELEASE_BOUND_MS = 50;
    private static final long CLOCK_BOUND_MS = 1_000;
    // Thread.sleep can return a little later than asked. The bound of the clock is the slice of 1 s plus this.
    private static final long SLEEP_SLACK_MS = 50;

    @TempDir
    Path base;

    private Path copy;

    @BeforeEach
    public void setUp() {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        // The first read of a clock claims the copy folder of this test.
        TestPoints.wallMillis("setup");
        copy = base.resolve("unit.1");
        assertTrue(Files.exists(copy.resolve("copy")));
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(DIR);
    }

    @Test
    public void holdIsReachedAndLetGo() throws Exception {
        final Path hold = copy.resolve("p.hold");
        write(hold, "wait=30", "bytes=57");
        final AtomicReference<Map<String, String>> arguments = new AtomicReference<>();
        final Thread caller = start("caller", () -> arguments.set(TestPoints.hold("p", "k1", "v=1")));
        final Path reached = copy.resolve("p@k" + hash("k1") + ".1.reached");
        waitFor(reached);
        assertTrue(caller.isAlive());

        final String line = read(reached);
        final Map<String, String> record = fields(line);
        assertEquals("hold-reached", record.get("point"));
        assertEquals("p", record.get("at"));
        assertEquals("k1", record.get("key"));
        assertEquals("1", record.get("n"));
        assertEquals("1", record.get("v"));
        assertEquals("caller", record.get("thread"));
        assertEquals(String.valueOf(caller.getId()), record.get("tid"));
        assertTrue(lines().contains(line.trim()), "the event hold-reached has the line of the reached file");

        Files.delete(hold);
        final long released = System.nanoTime();
        caller.join(5_000);
        assertFalse(caller.isAlive());
        final Map<String, String> end = last("hold-end");
        assertEquals("released", end.get("end"));
        assertEquals("p", end.get("at"));
        assertEquals("1", end.get("n"));
        assertWithin(released, end, RELEASE_BOUND_MS);
        assertEquals("57", arguments.get().get("bytes"));
        assertEquals("30", arguments.get().get("wait"));
    }

    @Test
    public void holdEndsByItsOwnLimit() throws Exception {
        write(copy.resolve("t.hold"), "wait=0.3");
        final long start = System.nanoTime();
        TestPoints.hold("t", null);
        final long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(tookMs >= 300 && tookMs < 5_000, "took " + tookMs + " ms");
        assertEquals("timeout", last("hold-end").get("end"));
        assertTrue(Files.exists(copy.resolve("t.1.reached")), "a point with no key has no key part in its name");
    }

    @Test
    public void holdThatAnInterruptEnds() throws Exception {
        write(copy.resolve("i.hold"), "wait=30");
        final AtomicBoolean flagAfter = new AtomicBoolean();
        final Thread caller = start("caller", () -> {
            TestPoints.hold("i", "k");
            flagAfter.set(Thread.currentThread().isInterrupted());
        });
        waitFor(copy.resolve("i@k" + hash("k") + ".1.reached"));
        caller.interrupt();
        caller.join(5_000);
        assertFalse(caller.isAlive());
        assertEquals("interrupt", last("hold-end").get("end"));
        assertTrue(flagAfter.get(), "the caller goes on with its interrupt flag set again");
    }

    @Test
    public void twoCallersWithDifferentKeysAtOnePoint() throws Exception {
        final String keyA = "sftp://127.0.0.1:5321/in/a.csv";
        final String keyB = "sftp://127.0.0.1:5321/in/b.csv";
        final Path hold = copy.resolve("w@k" + hash(keyA) + ".hold");
        write(hold, "wait=30");
        final Thread callerA = start("callerA", () -> TestPoints.hold("w", keyA));
        waitFor(copy.resolve("w@k" + hash(keyA) + ".1.reached"));

        final long start = System.nanoTime();
        assertTrue(TestPoints.hold("w", keyB).isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        assertFalse(Files.exists(copy.resolve("w@k" + hash(keyB) + ".2.reached")));
        assertTrue(callerA.isAlive());

        Files.delete(hold);
        callerA.join(5_000);
        assertFalse(callerA.isAlive());
    }

    @Test
    public void twoKeysThatDifferOnlyAfterAPathSeparatorGetTwoFiles() throws Exception {
        final String keyA = "sftp://127.0.0.1:5321/in/x/a";
        final String keyB = "sftp://127.0.0.1:5321/in/x/b";
        write(copy.resolve("s2.hold"), "wait=30");
        final Thread callerA = start("callerA", () -> TestPoints.hold("s2", keyA));
        final Thread callerB = start("callerB", () -> TestPoints.hold("s2", keyB));
        waitFor(copy.resolve("events.log"), () -> count("hold-reached") == 2);

        final String fileA = "s2@k" + hash(keyA) + ".";
        final String fileB = "s2@k" + hash(keyB) + ".";
        final List<String> reached = names(".reached");
        assertEquals(2, reached.size());
        assertTrue(reached.stream().anyMatch(n -> n.startsWith(fileA)));
        assertTrue(reached.stream().anyMatch(n -> n.startsWith(fileB)));
        assertNotEquals(fileA, fileB);

        write(copy.resolve("release.all"));
        callerA.join(5_000);
        callerB.join(5_000);
        assertFalse(callerA.isAlive() || callerB.isAlive());
    }

    @Test
    public void releaseFileLetsOneOfTwoCallersWithOneKeyGo() throws Exception {
        final String key = "file:c6sftp";
        write(copy.resolve("r.hold"), "wait=30");
        final Thread first = start("first", () -> TestPoints.hold("r", key));
        waitFor(copy.resolve("r@k" + hash(key) + ".1.reached"));
        final Thread second = start("second", () -> TestPoints.hold("r", key));
        waitFor(copy.resolve("r@k" + hash(key) + ".2.reached"));

        write(copy.resolve("r@k" + hash(key) + ".1.release"));
        final long released = System.nanoTime();
        first.join(5_000);
        assertFalse(first.isAlive());
        final Map<String, String> end = last("hold-end");
        assertEquals("released-one", end.get("end"));
        assertEquals("1", end.get("n"));
        assertWithin(released, end, RELEASE_BOUND_MS);
        Thread.sleep(200);
        assertTrue(second.isAlive(), "the other caller stays");

        write(copy.resolve("release.all"));
        second.join(5_000);
        assertFalse(second.isAlive());
        assertEquals("release-all", last("hold-end").get("end"));

        assertTrue(TestPoints.hold("r", key).isEmpty(), "no new hold starts after release.all");
        assertFalse(Files.exists(copy.resolve("r@k" + hash(key) + ".3.reached")));
    }

    @Test
    public void eachWayAHoldEndsGivesItsEvent() throws Exception {
        final Path released = copy.resolve("e1.hold");
        write(released, "wait=30");
        final Thread one = start("one", () -> TestPoints.hold("e1", null));
        waitFor(copy.resolve("e1.1.reached"));
        Files.delete(released);
        one.join(5_000);

        write(copy.resolve("e2.hold"), "wait=30");
        final Thread two = start("two", () -> TestPoints.hold("e2", null));
        waitFor(copy.resolve("e2.1.reached"));
        write(copy.resolve("e2.1.release"));
        two.join(5_000);

        write(copy.resolve("e3.hold"), "wait=0.1");
        TestPoints.hold("e3", null);

        write(copy.resolve("e4.hold"), "wait=30");
        final Thread four = start("four", () -> TestPoints.hold("e4", null));
        waitFor(copy.resolve("e4.1.reached"));
        four.interrupt();
        four.join(5_000);

        write(copy.resolve("e5.hold"), "wait=30");
        final Thread five = start("five", () -> TestPoints.hold("e5", null));
        waitFor(copy.resolve("e5.1.reached"));
        write(copy.resolve("release.all"));
        five.join(5_000);

        final List<String> ends = events().stream().filter(e -> "hold-end".equals(e.get("point")))
            .map(e -> e.get("at") + ":" + e.get("end")).collect(Collectors.toList());
        assertEquals(Arrays.asList("e1:released", "e2:released-one", "e3:timeout", "e4:interrupt", "e5:release-all"),
            ends);
    }

    @Test
    public void holdWithAnArgumentIsReachedOnlyAtTheBoundaryItNames() throws Exception {
        final String key = "sftp://127.0.0.1:5321/in/a.csv";
        final Path hold = copy.resolve("failstate.step@k" + hash(key) + ".hold");
        write(hold, "step=4.after", "kind=first", "wait=30");
        assertTrue(TestPoints.hold("failstate.step", key, "step=1.before", "kind=first", "try=1").isEmpty());
        assertTrue(TestPoints.hold("failstate.step", key, "step=4.after", "kind=update", "try=1").isEmpty());
        assertEquals(0, count("hold-reached"));

        final Thread caller = start("caller", () -> TestPoints.hold("failstate.step", key, "step=4.after",
            "kind=first", "try=1"));
        final Path reached = copy.resolve("failstate.step@k" + hash(key) + ".1.reached");
        waitFor(reached);
        assertEquals("4.after", fields(read(reached)).get("step"));
        Files.delete(hold);
        caller.join(5_000);
        assertFalse(caller.isAlive());
    }

    @Test
    public void argumentThatNamesNoValueSelectsNothing() throws Exception {
        write(copy.resolve("u.hold"), "context=7", "wait=30");
        final long start = System.nanoTime();
        assertTrue(TestPoints.hold("u", "file:c1", "connection=file:c1").isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        assertEquals(0, count("hold-reached"));
        final Map<String, String> unknown = last("unknown-argument");
        assertEquals("u", unknown.get("at"));
        assertEquals("u.hold", unknown.get("file"));
        assertEquals("context", unknown.get("argument"));
        assertEquals("file:c1", unknown.get("key"));

        write(copy.resolve("v.fault"), "connection-lost", "pool=3");
        assertNull(TestPoints.fault("v", null, "attempt=1"));
        assertEquals("v.fault", last("unknown-argument").get("file"));
    }

    @Test
    public void faultToken() throws Exception {
        final String key = "sftp://127.0.0.1:5321/in/a.csv";
        final Path once = copy.resolve("f@k" + hash(key) + ".fault");
        write(once, "connection-lost", "step=4.after", "once");
        assertNull(TestPoints.fault("f", key, "step=1.before"));
        assertNull(TestPoints.fault("f", "sftp://127.0.0.1:5321/in/b.csv", "step=4.after"));
        final TestPoints.Fault fault = TestPoints.fault("f", key, "step=4.after", "kind=first");
        assertNotNull(fault);
        assertEquals("connection-lost", fault.token());
        assertEquals("4.after", fault.arguments().get("step"));
        assertFalse(Files.exists(once), "once removes the file after it gave the token one time");
        assertNull(TestPoints.fault("f", key, "step=4.after", "kind=first"));

        write(copy.resolve("c.fault"), "close-fails");
        assertEquals("close-fails", TestPoints.fault("c", "any").token());
        assertEquals("close-fails", TestPoints.fault("c", null).token());
        assertTrue(Files.exists(copy.resolve("c.fault")));
        assertNull(TestPoints.fault("none", null));
    }

    @Test
    public void eventLine() throws Exception {
        TestPoints.event("e.one", "file:c6sftp", "a=1", "b=x\ty%z\nw\rv");
        TestPoints.event("e.two", null, "c=2");
        final List<String> lines = lines();
        final String one = lines.get(lines.size() - 2);
        final String two = lines.get(lines.size() - 1);

        final Map<String, String> first = fields(one);
        assertEquals(Arrays.asList("seq", "wall", "mono", "pid", "thread", "tid", "copy", "g", "point", "key", "a",
            "b"), new ArrayList<>(first.keySet()));
        assertEquals("x%09y%25z%0Aw%0Dv", first.get("b"));
        assertEquals("unit", first.get("copy"));
        assertEquals("1", first.get("g"));
        assertEquals("e.one", first.get("point"));
        assertEquals("file:c6sftp", first.get("key"));
        assertEquals(Thread.currentThread().getName(), first.get("thread"));
        assertEquals(String.valueOf(Thread.currentThread().getId()), first.get("tid"));
        final String jvm = ManagementFactory.getRuntimeMXBean().getName();
        assertEquals(jvm.substring(0, jvm.indexOf('@')), first.get("pid"));
        assertEquals(11, one.chars().filter(c -> c == '\t').count());

        final Map<String, String> second = fields(two);
        assertFalse(second.containsKey("key"), "a point with no key writes no field key");
        assertEquals(Long.parseLong(first.get("seq")) + 1, Long.parseLong(second.get("seq")));

        final Map<String, String> started = events().get(0);
        assertEquals("copy-started", started.get("point"));
        assertEquals("1", started.get("seq"));
        final String self = TestPoints.class.getName();
        assertEquals(Paths.get(TestPoints.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString(), started.get("jar." + self));
        assertEquals("-", started.get("sha256." + self), "a class from a folder has no jar to hash");
        final Path junit = Paths.get(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(junit.toString(), started.get("jar." + Test.class.getName()));
        assertEquals(sha256(junit), started.get("sha256." + Test.class.getName()));
        assertEquals(Integer.toHexString(System.identityHashCode(TestPoints.class.getClassLoader())),
            started.get("loader"));
    }

    @Test
    public void copyHashesRawAndEscapedDeploymentUrls() throws Exception {
        final Path junit = Paths.get(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Path archive = Files.copy(junit, base.resolve("{component} %7B+copy.jar"));
        final String escaped = archive.toUri().toASCIIString();
        final String raw = escaped.replace("%7Bcomponent%7D", "{component}");
        assertFalse(raw.equals(escaped));
        for (final String location : Arrays.asList(raw, escaped)) {
            final Path dir = Files.createTempDirectory(base, "origins");
            System.setProperty(DIR, dir.toString());
            final URL classes = TestPoints.class.getProtectionDomain().getCodeSource().getLocation();
            try (URLClassLoader loader = new URLClassLoader(new URL[] {new URL(location), classes}, null)) {
                final Class<?> anchor = Class.forName(Test.class.getName(), false, loader);
                final Class<?> points = Class.forName(TestPoints.class.getName(), true, loader);
                points.getMethod("copy", String.class, Class[].class).invoke(null, "origin", new Class<?>[] {anchor});
                final Map<String, String> record = fields(Files.readAllLines(dir.resolve("origin.1/events.log")).get(0));
                assertEquals("copy-started", record.get("point"));
                assertEquals(sha256(archive), record.get("sha256." + anchor.getName()));
                assertEquals("-", record.get("sha256." + TestPoints.class.getName()));
            }
        }
    }

    @Test
    public void linesOfTwoThreadsNeverMix() throws Exception {
        final int each = 1_000;
        final CountDownLatch go = new CountDownLatch(1);
        final Thread one = start("one", () -> writeEvents(go, "one", each));
        final Thread two = start("two", () -> writeEvents(go, "two", each));
        go.countDown();
        one.join(30_000);
        two.join(30_000);

        final List<Map<String, String>> events = events();
        assertEquals(2 * each + 1, events.size());
        for (int i = 0; i < events.size(); i++) {
            final Map<String, String> event = events.get(i);
            assertEquals(String.valueOf(i + 1), event.get("seq"), "seq grows by one");
            if (i > 0) {
                assertEquals(10, event.size());
                assertEquals("mix", event.get("point"));
                assertEquals(event.get("thread"), event.get("from"));
            }
        }
        assertEquals(each, events.stream().filter(e -> "one".equals(e.get("from"))).count());
        assertEquals(each, events.stream().filter(e -> "two".equals(e.get("from"))).count());
    }

    @Test
    public void snapshot() throws Exception {
        final TestPoints.Handle first = TestPoints.snapshot("s", "k1", () -> "pool=3\tactive=1");
        assertTrue(snapshotThreadAlive());
        write(copy.resolve("s.ask"));
        final Path snap = copy.resolve("s@k" + hash("k1") + ".1.snap");
        waitFor(snap);
        waitFor(copy.resolve("events.log"), () -> !Files.exists(copy.resolve("s.ask")));
        final Map<String, String> line = fields(read(snap));
        assertEquals("s", line.get("point"));
        assertEquals("k1", line.get("key"));
        assertEquals("pool=3%09active=1", line.get("state"));

        final TestPoints.Handle second = TestPoints.snapshot("s", "k1", () -> "pool=4");
        first.remove();
        write(copy.resolve("s@k" + hash("k1") + ".ask"));
        final Path replaced = copy.resolve("s@k" + hash("k1") + ".2.snap");
        waitFor(replaced);
        assertEquals("pool=4", fields(read(replaced)).get("state"), "the second registration took the place");

        final TestPoints.Handle other = TestPoints.snapshot("s", "k2", () -> "pool=5");
        write(copy.resolve("s@k" + hash("k2") + ".ask"));
        waitFor(copy.resolve("s@k" + hash("k2") + ".3.snap"));
        assertFalse(Files.exists(copy.resolve("s@k" + hash("k1") + ".4.snap")), "an ask for one key");

        other.remove();
        write(copy.resolve("s.ask"));
        waitFor(copy.resolve("s@k" + hash("k1") + ".4.snap"));
        waitFor(copy.resolve("events.log"), () -> !Files.exists(copy.resolve("s.ask")));
        assertFalse(Files.exists(copy.resolve("s@k" + hash("k2") + ".5.snap")), "a removed snapshot gives no line");

        second.remove();
        waitFor(copy.resolve("events.log"), () -> !snapshotThreadAlive());
        write(copy.resolve("s.ask"));
        Thread.sleep(1_200);
        assertTrue(Files.exists(copy.resolve("s.ask")), "no thread answers after the last removal");
        assertEquals(4, names(".snap").size());
    }

    @Test
    public void eachClockAlone() throws Exception {
        write(copy.resolve("clock.store.wall"), "60000");
        assertNear(60_000, TestPoints.wallMillis("store") - System.currentTimeMillis());
        assertNear(0, TimeUnit.NANOSECONDS.toMillis(TestPoints.nanoTime("store") - System.nanoTime()));
        assertNear(0, TestPoints.wallMillis("repeat") - System.currentTimeMillis());

        write(copy.resolve("clock.store.mono"), "-5000");
        assertNear(-5_000, TimeUnit.NANOSECONDS.toMillis(TestPoints.nanoTime("store") - System.nanoTime()));
        assertNear(60_000, TestPoints.wallMillis("store") - System.currentTimeMillis());
        assertNear(0, TimeUnit.NANOSECONDS.toMillis(TestPoints.nanoTime("repeat") - System.nanoTime()));
    }

    @Test
    public void twoClocksMovedApart() throws Exception {
        write(copy.resolve("clock.cleanup.wall"), "3600000");
        write(copy.resolve("clock.cleanup.mono"), "-3600000");
        final long wallMoved = TestPoints.wallMillis("cleanup") - System.currentTimeMillis();
        final long monoMoved = TimeUnit.NANOSECONDS.toMillis(TestPoints.nanoTime("cleanup") - System.nanoTime());
        assertNear(3_600_000, wallMoved);
        assertNear(-3_600_000, monoMoved);
        assertNear(7_200_000, wallMoved - monoMoved);
    }

    @Test
    public void clockMovedPastADeadlineEndsATimedWaitWithinOneSecond() throws Exception {
        final String clock = "pollwait.fileIn1";
        assertEquals(CLOCK_BOUND_MS, TestPoints.slice(clock, 1_000_000));
        assertEquals(200, TestPoints.slice(clock, 200));
        final long deadline = TestPoints.wallMillis(clock) + 1_000_000;
        final AtomicLong ended = new AtomicLong();
        final Thread waiter = start("waiter", () -> {
            try {
                long left;
                while ((left = deadline - TestPoints.wallMillis(clock)) > 0) {
                    Thread.sleep(TestPoints.slice(clock, left));
                }
                ended.set(System.nanoTime());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(300);
        assertTrue(waiter.isAlive());
        write(copy.resolve("clock." + clock + ".wall"), "1000000");
        final long moved = System.nanoTime();
        waiter.join(5_000);
        assertFalse(waiter.isAlive());
        final long tookMs = TimeUnit.NANOSECONDS.toMillis(ended.get() - moved);
        assertTrue(tookMs <= CLOCK_BOUND_MS + SLEEP_SLACK_MS, "took " + tookMs + " ms");
    }

    @Test
    public void twoCopiesWithOneNameGetTwoFolders() throws Exception {
        Files.createDirectories(base.resolve("twin.2"));
        final URL classes = TestPoints.class.getProtectionDomain().getCodeSource().getLocation();
        final ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        try (URLClassLoader one = new URLClassLoader(new URL[] {classes}, parent);
            URLClassLoader two = new URLClassLoader(new URL[] {classes}, parent)) {
            final Class<?> first = one.loadClass(TestPoints.class.getName());
            final Class<?> second = two.loadClass(TestPoints.class.getName());
            assertNotSame(first, second);
            first.getMethod("copy", String.class, Class[].class).invoke(null, "twin", new Class<?>[0]);
            second.getMethod("copy", String.class, Class[].class).invoke(null, "twin", new Class<?>[0]);
        }
        assertTrue(Files.exists(base.resolve("twin.1/copy")));
        assertTrue(Files.exists(base.resolve("twin.2/copy")));
        final Map<String, String> one = fields(Files.readAllLines(base.resolve("twin.1/events.log")).get(0));
        final Map<String, String> two = fields(Files.readAllLines(base.resolve("twin.2/events.log")).get(0));
        assertEquals("copy-started", one.get("point"));
        assertEquals("1", one.get("g"));
        assertEquals("2", two.get("g"));
        assertEquals("twin", two.get("copy"));
        assertNotEquals(one.get("loader"), two.get("loader"));
    }

    @Test
    public void argumentsGivesTheCountBeforeTheHold() throws Exception {
        final String key = "sftp://127.0.0.1:5321/in/a.csv";
        final String point = "failstate.staging.bytes";
        final Path hold = copy.resolve(point + "@k" + hash(key) + ".hold");
        final Path fault = copy.resolve(point + "@k" + hash(key) + ".fault");
        write(hold, "bytes=57", "kind=first", "wait=30");
        write(fault, "connection-lost", "once");

        final Map<String, String> asked = TestPoints.arguments(point, key, "kind=first", "try=1");
        assertEquals("57", asked.get("bytes"));
        assertEquals(0, count("hold-reached"), "the lookup holds nothing");
        assertEquals(0, count("hold-end"));
        assertTrue(names(".reached").isEmpty());
        assertTrue(Files.exists(fault), "the lookup takes no fault");

        final String did = "bytes=" + Integer.parseInt(asked.get("bytes"));
        final Thread caller = start("caller", () -> TestPoints.hold(point, key, "kind=first", "try=1", did));
        final Path reached = copy.resolve(point + "@k" + hash(key) + ".1.reached");
        waitFor(reached);
        assertEquals("57", fields(read(reached)).get("bytes"));
        Files.delete(hold);
        caller.join(5_000);
        assertFalse(caller.isAlive());
        assertEquals("released", last("hold-end").get("end"));
        assertEquals("connection-lost", TestPoints.fault(point, key, "kind=first", "try=1").token());
    }

    @Test
    public void argumentsGivesNoneWhenNoHoldFileSelects() throws Exception {
        final String key = "sftp://127.0.0.1:5321/in/a.csv";
        write(copy.resolve("a@k" + hash(key) + ".hold"), "bytes=57", "kind=first");
        assertTrue(TestPoints.arguments("a", key, "kind=update").isEmpty(), "a selector that does not match");
        assertTrue(TestPoints.arguments("a", "sftp://127.0.0.1:5321/in/b.csv", "kind=first").isEmpty(), "another key");
        assertTrue(TestPoints.arguments("none", key).isEmpty(), "no hold file");

        write(copy.resolve("u.hold"), "context=7", "bytes=3");
        assertTrue(TestPoints.arguments("u", key, "kind=first").isEmpty());
        final Map<String, String> unknown = last("unknown-argument");
        assertEquals("u", unknown.get("at"));
        assertEquals("context", unknown.get("argument"));

        assertEquals("57", TestPoints.arguments("a", key, "kind=first").get("bytes"));
        write(copy.resolve("release.all"));
        assertTrue(TestPoints.arguments("a", key, "kind=first").isEmpty(), "release.all");
        Files.delete(copy.resolve("release.all"));
        System.clearProperty(DIR);
        assertTrue(TestPoints.arguments("a", key, "kind=first").isEmpty(), "no system property");
        System.setProperty(DIR, base.toString());
        assertEquals("57", TestPoints.arguments("a", key, "kind=first").get("bytes"));
        assertEquals(0, count("hold-reached"));
        assertEquals(0, count("hold-end"));
    }

    @Test
    public void onceFaultGoesToOneOfTwoCallers() throws Exception {
        final Path other = copy.resolve("m.fault");
        write(other, "connection-lost", "step=4.after", "once");
        assertNull(TestPoints.fault("m", null, "step=1.before"));
        assertTrue(Files.exists(other), "a file that does not match stays");

        final Path file = copy.resolve("o.fault");
        for (int round = 0; round < 100; round++) {
            final String first = "first-" + round;
            final String second = "second-" + round;
            write(file, first, "once");
            final List<String> got = new CopyOnWriteArrayList<>();
            final CountDownLatch go = new CountDownLatch(1);
            final Thread one = start("one", () -> got.add(take(go)));
            final Thread two = start("two", () -> got.add(take(go)));
            go.countDown();
            waitFor(file, () -> !Files.exists(file));
            // The harness arms again as soon as it sees the first fault taken.
            write(file, second, "once");
            one.join(5_000);
            two.join(5_000);
            got.add(take(new CountDownLatch(0)));
            assertEquals(1, Collections.frequency(got, first), "round " + round + " gave " + got);
            assertEquals(1, Collections.frequency(got, second), "round " + round + " gave " + got);
            assertEquals(1, Collections.frequency(got, "null"), "round " + round + " gave " + got);
        }
    }

    @Test
    public void removedSupplierThatHasNotStartedGivesNoLine() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch allow = new CountDownLatch(1);
        final AtomicInteger retiredCalls = new AtomicInteger();
        final TestPoints.Handle first = TestPoints.snapshot("pool", "first", () -> {
            entered.countDown();
            awaitLatch(allow);
            return "first";
        });
        final TestPoints.Handle retired = TestPoints.snapshot("pool", "second", () -> {
            retiredCalls.incrementAndGet();
            return "RETIRED";
        });
        final Path ask = copy.resolve("pool.ask");
        write(ask);
        awaitLatch(entered);
        retired.remove();
        final TestPoints.Handle replacement = TestPoints.snapshot("pool", "second", () -> "replacement");
        allow.countDown();
        waitFor(ask, () -> !Files.exists(ask));
        assertEquals(Arrays.asList("first"), states());
        assertEquals(0, retiredCalls.get(), "the removed supplier never started");

        final Path second = copy.resolve("pool@k" + hash("second") + ".ask");
        write(second);
        waitFor(second, () -> !Files.exists(second));
        assertEquals(Arrays.asList("first", "replacement"), states());
        retired.remove();
        write(second);
        waitFor(second, () -> !Files.exists(second));
        assertEquals(Arrays.asList("first", "replacement", "replacement"), states(), "the old handle removes nothing");

        first.remove();
        replacement.remove();
        waitFor(ask, () -> !snapshotThreadAlive());
    }

    @Test
    public void supplierRemovedOrReplacedWhileItRunsGivesNoLine() throws Exception {
        final Path ask = copy.resolve("pool.ask");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch allow = new CountDownLatch(1);
        final TestPoints.Handle removed = TestPoints.snapshot("pool", "second", () -> {
            entered.countDown();
            awaitLatch(allow);
            return "RETIRED";
        });
        write(ask);
        awaitLatch(entered);
        removed.remove();
        allow.countDown();
        waitFor(ask, () -> !Files.exists(ask));
        assertEquals(Collections.emptyList(), states(), "a removal while the supplier runs");

        final CountDownLatch enteredAgain = new CountDownLatch(1);
        final CountDownLatch allowAgain = new CountDownLatch(1);
        final TestPoints.Handle old = TestPoints.snapshot("pool", "second", () -> {
            enteredAgain.countDown();
            awaitLatch(allowAgain);
            return "RETIRED";
        });
        write(ask);
        awaitLatch(enteredAgain);
        final TestPoints.Handle replacement = TestPoints.snapshot("pool", "second", () -> "replacement");
        allowAgain.countDown();
        waitFor(ask, () -> !Files.exists(ask));
        assertEquals(Collections.emptyList(), states(), "a replacement while the supplier runs");

        write(ask);
        waitFor(ask, () -> !Files.exists(ask));
        assertEquals(Arrays.asList("replacement"), states());
        old.remove();
        write(ask);
        waitFor(ask, () -> !Files.exists(ask));
        assertEquals(Arrays.asList("replacement", "replacement"), states());

        replacement.remove();
        waitFor(ask, () -> !snapshotThreadAlive());
    }

    @Test
    public void noEffectWithNoSystemProperty() throws Exception {
        write(copy.resolve("p.hold"), "wait=30");
        write(copy.resolve("p.fault"), "connection-lost");
        write(copy.resolve("clock.store.wall"), "3600000");
        write(copy.resolve("s.ask"));
        final Map<String, Long> before = listing();
        System.clearProperty(DIR);

        assertFalse(TestPoints.enabled());
        final long start = System.nanoTime();
        assertTrue(TestPoints.hold("p", "k").isEmpty());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        assertNull(TestPoints.fault("p", "k"));
        TestPoints.event("p", "k", "a=1");
        final TestPoints.Handle handle = TestPoints.snapshot("s", "k", () -> "x=1");
        assertFalse(snapshotThreadAlive());
        handle.remove();
        assertNear(0, TestPoints.wallMillis("store") - System.currentTimeMillis());
        assertNear(0, TimeUnit.NANOSECONDS.toMillis(TestPoints.nanoTime("store") - System.nanoTime()));
        assertEquals(5_000, TestPoints.slice("store", 5_000));
        TestPoints.copy("other");
        Thread.sleep(700);
        assertEquals(before, listing());
    }

    private static String take(final CountDownLatch go) {
        try {
            go.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        final TestPoints.Fault fault = TestPoints.fault("o", null);
        return fault == null ? "null" : fault.token();
    }

    private static void awaitLatch(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("waited 10 s for a latch");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // The states of all snap files, in the order of their numbers.
    private List<String> states() throws IOException {
        final List<String> found = new ArrayList<>();
        final List<String> files = names(".snap");
        files.sort((a, b) -> Integer.compare(snapNumber(a), snapNumber(b)));
        for (final String file : files) {
            found.add(fields(read(copy.resolve(file))).get("state"));
        }
        return found;
    }

    private static int snapNumber(final String file) {
        final String[] parts = file.split("\\.");
        return Integer.parseInt(parts[parts.length - 2]);
    }

    private static void writeEvents(final CountDownLatch go, final String from, final int count) {
        try {
            go.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; i < count; i++) {
            TestPoints.event("mix", null, "from=" + from);
        }
    }

    private static Thread start(final String name, final Runnable body) {
        final Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean snapshotThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(t -> t.getName().startsWith("testpoints-snapshot-") && t.isAlive());
    }

    // A test writes a control file under another name and renames it into place, so no reader sees a part of it.
    private static void write(final Path file, final String... lines) throws IOException {
        final Path temp = file.resolveSibling(".write-" + file.getFileName());
        Files.write(temp, lines.length == 0 ? new byte[0]
            : (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void waitFor(final Path file) throws InterruptedException {
        waitFor(file, () -> Files.exists(file));
    }

    private static void waitFor(final Path file, final Check check) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.holds()) {
            assertTrue(System.nanoTime() < end, "waited 10 s for " + file);
            Thread.sleep(5);
        }
    }

    private interface Check {
        boolean holds();
    }

    private static String read(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<String> lines() throws IOException {
        return Files.readAllLines(copy.resolve("events.log"), StandardCharsets.UTF_8);
    }

    private List<Map<String, String>> events() throws IOException {
        return lines().stream().map(TestPointsTest::fields).collect(Collectors.toList());
    }

    private Map<String, String> last(final String point) throws IOException {
        final List<Map<String, String>> found = events().stream().filter(e -> point.equals(e.get("point")))
            .collect(Collectors.toList());
        assertFalse(found.isEmpty(), "no event " + point);
        return found.get(found.size() - 1);
    }

    private long count(final String point) {
        try {
            return events().stream().filter(e -> point.equals(e.get("point"))).count();
        } catch (final IOException e) {
            return 0;
        }
    }

    private List<String> names(final String suffix) throws IOException {
        try (Stream<Path> files = Files.list(copy)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(suffix))
                .collect(Collectors.toList());
        }
    }

    private Map<String, Long> listing() throws IOException {
        final Map<String, Long> sizes = new HashMap<>();
        try (Stream<Path> files = Files.walk(base)) {
            for (final Path file : (Iterable<Path>) files::iterator) {
                sizes.put(base.relativize(file).toString(), Files.isDirectory(file) ? -1 : Files.size(file));
            }
        }
        return sizes;
    }

    private static Map<String, String> fields(final String line) {
        final Map<String, String> fields = new LinkedHashMap<>();
        for (final String field : line.trim().split("\t")) {
            final int eq = field.indexOf('=');
            fields.put(field.substring(0, eq), field.substring(eq + 1));
        }
        return fields;
    }

    private static void assertWithin(final long sinceNanos, final Map<String, String> event, final long boundMs) {
        final long tookMs = TimeUnit.NANOSECONDS.toMillis(Long.parseLong(event.get("mono")) - sinceNanos);
        assertTrue(tookMs <= boundMs, "the hold ended " + tookMs + " ms after the test ended it");
    }

    private static void assertNear(final long expectedMs, final long actualMs) {
        assertTrue(Math.abs(expectedMs - actualMs) < 1_000, "expected about " + expectedMs + " ms, got " + actualMs);
    }

    private static String hash(final String key) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    private static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder text = new StringBuilder();
        for (final byte b : bytes) {
            text.append(String.format("%02x", b));
        }
        return text.toString();
    }
}
