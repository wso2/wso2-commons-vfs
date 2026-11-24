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
package org.wso2.vfs.shared.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.commons.logging.impl.SimpleLog;
import org.junit.jupiter.api.Test;
import org.wso2.vfs.shared.log.LoggingTest.Lines;

class HealthRegistryTest {
    public static class Connections {
        static final Map<String, Supplier<Map<String, Object>>> active = new HashMap<>();
        static Supplier<?> removed;

        public static void register(final String library, final String name, final Supplier<Map<String, Object>> owner) {
            active.put(library + "/" + name, owner);
        }

        public static void unregister(final String library, final String name, final Supplier<Map<String, Object>> owner) {
            removed = owner;
            active.remove(library + "/" + name, owner);
        }
    }

    private final Lines lines = new Lines();
    private final AtomicInteger lookups = new AtomicInteger();

    private HealthRegistry registry(final boolean patched) {
        final ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(final String name) throws ClassNotFoundException {
                if (name.equals("org.wso2.carbon.connector.core.ConnectionHealthRegistry")) {
                    lookups.incrementAndGet();
                    if (patched) {
                        return Connections.class;
                    }
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        final MetricsExporterTest.Plain reporter = new MetricsExporterTest.Plain();
        return new HealthRegistry(() -> reporter, loader, lines);
    }

    @Test
    void staleAndByNameClosePreserveReplacementAndCurrentCloseDoesNotResurrect() {
        final HealthRegistry registry = registry(true);
        for (final boolean oldFirst : new boolean[]{true, false}) {
            final HealthRegistry.ConnectionRegistration old = registry.connection("file", "out");
            final HealthRegistry.ConnectionRegistration current = registry.connection("{org.wso2.carbon.connector}file", "out");
            assertNotSame(old, current);
            assertSame(old.health(), current.health());
            assertSame(current, Connections.active.get("file/out"));
            registry.remove(HealthRegistry.Kind.CONNECTION, "file", "out", null);
            assertSame(current, Connections.active.get("file/out"));
            if (oldFirst) {
                old.close();
                assertSame(old, Connections.removed);
                assertSame(current, Connections.active.get("file/out"));
                current.close();
            } else {
                current.close();
                assertFalse(Connections.active.containsKey("file/out"));
                old.close();
            }
            current.close();
            assertFalse(Connections.active.containsKey("file/out"));
        }
        assertEquals(1, lookups.get());
    }

    @Test
    void deployedLibraryAndEmptyPackageHaveSeparateHealthAndSafeStatusMaps() {
        final HealthRegistry registry = registry(true);
        try (HealthRegistry.ConnectionRegistration standard = registry.connection("file", "out");
             HealthRegistry.ConnectionRegistration version = registry.connection("{v2}file", "out");
             HealthRegistry.ConnectionRegistration empty = registry.connection("{}file", "out")) {
            assertSame(version, Connections.active.get("{v2}file/out"));
            assertSame(empty, Connections.active.get("{}file/out"));
            assertNotSame(standard.health(), version.health());
            assertNotSame(empty.health(), version.health());
            assertEquals("active", standard.get().get("status"));
            version.health().failure(EndpointHealthTest.transport(version.health().log));
            registry.tick();
            assertEquals("failed", version.get().get("status"));
            version.health().breakerState(EndpointHealth.Breaker.OPEN);
            registry.tick();
            assertEquals("breaker_open", version.get().get("status"));
            empty.health().flag(EndpointHealth.Flag.IDENTITY_UNREADABLE, "file:///x");
            registry.tick();
            final Map<String, Object> result = empty.get();
            assertEquals("degraded", result.get("status"));
            assertSafe(result);
            assertSafe(version.get());
            result.clear();
            assertEquals("DEGRADED", empty.get().get("state"));
            assertEquals("active", standard.get().get("status"));
        }
        assertTrue(Connections.active.isEmpty());
    }

    private static void assertSafe(final Object value) {
        if (value instanceof Map) {
            ((Map<?, ?>) value).forEach((key, child) -> {
                assertTrue(key instanceof String);
                assertSafe(child);
            });
        } else if (value instanceof List) {
            ((List<?>) value).forEach(HealthRegistryTest::assertSafe);
        } else if (value instanceof Number) {
            assertTrue(Double.isFinite(((Number) value).doubleValue()));
        } else {
            assertTrue(value instanceof String || value instanceof Boolean);
        }
    }

    @Test
    void missingRegistryGivesOneDebugAndNoOtherDiagnostic() {
        final HealthRegistry registry = registry(false);
        try (HealthRegistry.ConnectionRegistration first = registry.connection("file", "one");
             HealthRegistry.ConnectionRegistration second = registry.connection("file", "two")) {
            first.health().success();
            second.health().success();
            assertEquals(1, lookups.get());
            assertEquals(1, lines.lines.stream().filter(line -> line.equals(
                    "DEBUG [connection:one] Connection health registry is unavailable.")).count());
            assertTrue(lines.lines.stream().noneMatch(line -> line.startsWith("WARN") || line.startsWith("ERROR")));
        }
    }

    @Test
    void simpleRegistrationReusesHealthAndReleasesItsOwnSupplier() {
        final HealthRegistry registry = registry(true);
        final EndpointHealth first = registry.add(HealthRegistry.Kind.CONNECTION, "file", "out", 0);
        final Supplier<?> owner = Connections.active.get("file/out");
        assertNotNull(owner);
        assertSame(first, registry.add(HealthRegistry.Kind.CONNECTION, "file", "out", 0));
        final HealthRegistry.ConnectionRegistration explicit = registry.connection("file", "out");
        registry.remove(HealthRegistry.Kind.CONNECTION, "file", "out", null);
        assertSame(explicit, Connections.active.get("file/out"));
        explicit.close();
        assertNotSame(first, registry.add(HealthRegistry.Kind.CONNECTION, "file", "out", 0));
        final Supplier<?> last = Connections.active.get("file/out");
        registry.remove(HealthRegistry.Kind.CONNECTION, "file", "out", null);
        assertSame(last, Connections.removed);
        assertTrue(Connections.active.isEmpty());
    }

    @Test
    void failingStateLogAndExporterWarningDoNotBreakHealthOrTheOtherSeries() {
        final AtomicInteger dropped = new AtomicInteger();
        final SimpleLog sink = new SimpleLog("health") {
            private static final long serialVersionUID = 1L;

            @Override
            protected void log(final int level, final Object message, final Throwable cause) {
                if (message.toString().contains("Health changed to DOWN")
                        || message.toString().contains("File metric export stopped")) {
                    dropped.incrementAndGet();
                    throw new IllegalStateException("appender unavailable");
                }
            }
        };
        sink.setLevel(SimpleLog.LOG_LEVEL_DEBUG);
        final MetricsExporterTest.Reporter reporter = new MetricsExporterTest.Reporter();
        final HealthRegistry registry = new HealthRegistry(() -> reporter, getClass().getClassLoader(), sink);
        final EndpointHealth source = registry.add(HealthRegistry.Kind.INBOUND, "file", "source", 10000);
        final EndpointHealth peer = registry.add(HealthRegistry.Kind.INBOUND, "file", "peer", 10000);
        source.pollStarted();
        try {
            source.pollFinished(false, EndpointHealthTest.transport(source.log));
            peer.openSessions(9);
            registry.tick();
            assertEquals(EndpointHealth.State.DOWN, source.state());
            assertEquals(0.0, reporter.gauges.get(MetricsExporterTest.PREFIX + "state[INBOUND, source, OK]"));
            assertEquals(9.0, reporter.gauges.get(MetricsExporterTest.PREFIX + "open_sessions[INBOUND, peer]"));
            reporter.failCounter = true;
            source.fileDone();
            peer.fileDone();
            registry.tick();
            assertEquals(1, reporter.counters.size());
            assertEquals(2, dropped.get());
            source.pollStarted();
            source.pollFinished(true, null);
            registry.tick();
            assertEquals(EndpointHealth.State.OK, source.state());
            assertEquals("up file-inbound source", reporter.service.get(reporter.service.size() - 1));
        } finally {
            source.paused();
        }
    }

    @Test
    void firstPollCountersReachTheReporterWhenTheLastEntryPausesBeforeItsFirstTick() throws Exception {
        final MetricsExporterTest.Reporter reporter = new MetricsExporterTest.Reporter();
        final HealthRegistry registry = new HealthRegistry(() -> reporter, getClass().getClassLoader(), lines);
        final EndpointHealth health = registry.add(HealthRegistry.Kind.INBOUND, "file", "short", 10000);
        health.pollStarted();
        final Thread ticker = registry.ticker();
        for (int file = 0; file < 7; file++) {
            health.fileDone();
        }
        health.openSessions(3);
        health.pollFinished(true, null);
        health.paused();
        ticker.join(3000);
        assertFalse(ticker.isAlive());
        assertEquals(7, reporter.counters.size());
        assertEquals(3.0, reporter.gauges.get(MetricsExporterTest.PREFIX + "open_sessions[INBOUND, short]"));
        assertEquals("paused", health.state().status());
    }

    private static long liveTickers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && "File health ticker".equals(thread.getName())).count();
    }

    @Test
    void lastPauseStopsTheDaemonAndRegistrationOrResumeRestartsIt() throws Exception {
        final HealthRegistry registry = registry(false);
        final EndpointHealth first = registry.add(HealthRegistry.Kind.INBOUND, "file", "first", 10000);
        final EndpointHealth second = registry.add(HealthRegistry.Kind.INBOUND, "file", "second", 0);
        try {
            assertEquals(0L, liveTickers(), "[J3.14.7]");
            assertNull(registry.ticker(), "[J3.14.7]");
            first.resumed();
            final Thread ticker = registry.ticker();
            assertNotNull(ticker, "[J3.14.7]");
            assertTrue(ticker.isDaemon(), "[J3.14.7]");
            assertEquals(1L, liveTickers(), "[J3.14.7]");
            second.pollStarted();
            assertEquals(1L, liveTickers(), "[J3.14.7]");
            first.paused();
            assertSame(ticker, registry.ticker(), "[J3.14.7]");
            second.paused();
            ticker.join(2000);
            assertEquals(0L, liveTickers(), "[J3.14.7]");
            assertFalse(ticker.isAlive(), "[J3.14.7]");
            assertNull(registry.ticker(), "[J3.14.7]");
            System.out.println("Observed J3.14.7");
            first.pollStarted();
            final Thread next = registry.ticker();
            assertNotSame(ticker, next);
            registry.remove(HealthRegistry.Kind.INBOUND, "file", "first", null);
            next.join(2000);
            assertFalse(next.isAlive());
            assertNull(registry.ticker());
        } finally {
            first.paused();
            second.paused();
        }
    }

    public static final class HeldReporter extends MetricsExporterTest.Reporter {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean armed = new AtomicBoolean();
        final AtomicBoolean timedOut = new AtomicBoolean();

        @Override
        public void serviceDown(final String name, final String type) {
            if (armed.compareAndSet(true, false)) {
                writing.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        throw new IllegalStateException("missing test release");
                    }
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            super.serviceDown(name, type);
        }
    }

    @Test
    void retiringWriterCannotOverwriteTheReplacementServiceOrComponentState() throws Exception {
        for (final boolean finishBeforeRemoval : new boolean[]{true, false}) {
            final HeldReporter reporter = new HeldReporter();
            final HealthRegistry registry = new HealthRegistry(() -> reporter, getClass().getClassLoader(), lines);
            final HealthRegistry.ConnectionRegistration old = registry.connection("file", "out");
            old.health().failure(EndpointHealthTest.transport(old.health().log));
            reporter.armed.set(true);
            final Thread writer = new Thread(registry::tick);
            writer.start();
            try {
                assertTrue(reporter.writing.await(3, TimeUnit.SECONDS));
                if (finishBeforeRemoval) {
                    reporter.release.countDown();
                    writer.join(3000);
                    assertFalse(writer.isAlive());
                }
                old.close();
                try (HealthRegistry.ConnectionRegistration current = registry.connection("file", "out");
                     HealthRegistry.ConnectionRegistration independent = registry.connection("file", "other")) {
                    assertNotSame(old.health(), current.health());
                    assertEquals("active", current.get().get("status"));
                    assertTrue(reporter.service.contains("up file-connection other"));
                    current.health().success();
                    old.close();
                    reporter.release.countDown();
                    writer.join(3000);
                    assertFalse(writer.isAlive());
                    assertEquals("up file-connection out", reporter.service.stream()
                            .filter(call -> call.endsWith(" out")).reduce((first, last) -> last).get());
                    assertEquals(1.0, reporter.gauges.get(MetricsExporterTest.PREFIX + "state[CONNECTION, out, OK]"));
                    assertEquals(0.0, reporter.gauges.get(MetricsExporterTest.PREFIX + "state[CONNECTION, out, DOWN]"));
                    registry.tick();
                    assertFalse(reporter.timedOut.get());
                    assertEquals(1L, current.health().snapshot().get("filesDone"));
                    assertEquals(1, reporter.counters.stream().filter((MetricsExporterTest.PREFIX + "files_total")::equals).count());
                    assertEquals(1, reporter.counters.stream().filter((MetricsExporterTest.PREFIX + "failures_total")::equals).count());
                }
            } finally {
                reporter.release.countDown();
                writer.join(3000);
                old.close();
            }
        }
    }

    @Test
    void realTickerPublishesTheFaultAndSummaryWithoutAnotherWorkerCall() throws Exception {
        final CountDownLatch down = new CountDownLatch(1);
        final MetricsExporterTest.Plain reporter = new MetricsExporterTest.Plain() {
            @Override
            public void serviceDown(final String name, final String type) {
                super.serviceDown(name, type);
                down.countDown();
            }
        };
        final HealthRegistry registry = new HealthRegistry(() -> reporter, getClass().getClassLoader(), lines);
        final EndpointHealth first = registry.add(HealthRegistry.Kind.INBOUND, "file", "tenant", 10000);
        registry.add(HealthRegistry.Kind.INBOUND, "file", "standby", 10000);
        first.pollStarted();
        final Thread ticker = registry.ticker();
        try {
            first.pollFinished(false, EndpointHealthTest.transport(first.log));
            assertTrue(down.await(15, TimeUnit.SECONDS));
        } finally {
            first.paused();
            ticker.join(3000);
        }
        assertFalse(ticker.isAlive());
        assertTrue(lines.lines.stream().anyMatch(line -> line.startsWith("DEBUG [inbound:standby] Health summary")
                && line.contains("state=PAUSED")));
        assertTrue(lines.lines.stream().anyMatch(line -> line.startsWith("ERROR [inbound:tenant] Health changed to DOWN")));
        assertTrue(lines.lines.stream().anyMatch(line -> line.startsWith("DEBUG [inbound:tenant] Health summary")
                && line.contains("sftp://u:***@host/path")));
    }
}
