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
package org.wso2.vfs.shared.health;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.wso2.vfs.shared.log.LoggingTest.Lines;

class MetricsExporterTest {
    static final String PREFIX = "wso2_integration_file_";
    final Lines lines = new Lines();

    public static class Plain {
        final List<String> service = new ArrayList<>();
        boolean failService;

        public synchronized void serviceUp(final String name, final String type) {
            service.add("up " + type + " " + name);
            if (failService) {
                throw new IllegalStateException("service failure");
            }
        }

        public synchronized void serviceDown(final String name, final String type) {
            service.add("down " + type + " " + name);
        }

        public void incrementCount(final String name, final String[] labels) {
            throw new AssertionError("A refused metric received a counter event");
        }
    }

    public interface Defaults {
        List<String> calls();

        default boolean createComponentMetric(final String type, final String name, final String help,
                final String[] labels) {
            calls().add("create " + name);
            return false;
        }

        default boolean setGauge(final String name, final String[] labels, final double value) {
            calls().add("set " + name);
            return false;
        }
    }

    public static class OldReporter extends Plain implements Defaults {
        final List<String> calls = new ArrayList<>();

        @Override
        public List<String> calls() {
            return calls;
        }
    }

    public static class Reporter extends Plain {
        final Map<String, List<String>> shapes = new HashMap<>();
        final Map<String, Double> gauges = new HashMap<>();
        final List<String[]> labelsKeptByLibrary = new ArrayList<>();
        final List<String> counters = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        final List<String> registrations = new ArrayList<>();
        String refuseGauge;
        boolean failCounter;
        boolean failCreate;

        public synchronized boolean createComponentMetric(final String type, final String name, final String help,
                final String[] labels) {
            registrations.add(name);
            if (failCreate) {
                throw new IllegalStateException("registration failure");
            }
            final List<String> shape = Arrays.asList(type, Arrays.toString(labels));
            final List<String> existing = shapes.putIfAbsent(name, shape);
            return existing == null || existing.equals(shape);
        }

        public synchronized boolean setGauge(final String name, final String[] labels, final double value) {
            writes.add(name);
            if (name.equals(refuseGauge)) {
                return false;
            }
            gauges.put(name + Arrays.toString(labels), value);
            return true;
        }

        @Override
        public synchronized void incrementCount(final String name, final String[] labels) {
            counters.add(name);
            labelsKeptByLibrary.add(labels);
            if (failCounter) {
                throw new IllegalStateException("counter failure");
            }
        }
    }

    HealthRegistry copy(final Plain reporter) {
        return new HealthRegistry(() -> reporter, getClass().getClassLoader(), lines);
    }

    @Test
    void plainAndInheritedDefaultsKeepOnlyTheServiceGauge() {
        for (final Plain reporter : new Plain[]{new Plain(), new OldReporter()}) {
            final HealthRegistry registry = copy(reporter);
            final EndpointHealth health = registry.add(HealthRegistry.Kind.CONNECTION, "file", "output", 0);
            try {
                assertEquals(Arrays.asList("up file-connection output"), reporter.service);
                health.failure(EndpointHealthTest.transport(health.log));
                registry.tick();
                assertEquals(EndpointHealth.State.DOWN, health.state());
                health.breakerState(EndpointHealth.Breaker.OPEN);
                registry.tick();
                assertEquals(EndpointHealth.State.BREAKER_OPEN, health.state());
                health.breakerState(EndpointHealth.Breaker.HALF_OPEN);
                registry.tick();
                assertEquals(EndpointHealth.State.DOWN, health.state());
                health.success();
                health.breakerState(EndpointHealth.Breaker.CLOSED);
                registry.tick();
                assertEquals(EndpointHealth.State.OK, health.state());
                health.flag(EndpointHealth.Flag.LEGACY_MARKER, "file:///legacy");
                registry.tick();
                assertEquals(EndpointHealth.State.DEGRADED, health.state());
                assertEquals(Arrays.asList("up file-connection output", "down file-connection output",
                        "up file-connection output"), reporter.service);
                if (reporter instanceof OldReporter) {
                    final List<String> calls = ((OldReporter) reporter).calls;
                    assertEquals(9, calls.size());
                    assertTrue(calls.stream().allMatch(call -> call.startsWith("create " + PREFIX)));
                }
                assertEquals(1L, health.snapshot().get("filesDone"));
                assertEquals(1L, health.snapshot().get("filesFailed"));
                assertTrue(lines.lines.stream().noneMatch(line -> line.contains("export stopped")));
            } finally {
                registry.remove(HealthRegistry.Kind.CONNECTION, "file", "output", null);
            }
        }
    }

    @Test
    void oneCopyRegistersOnceAndARedeployAddsToTheSameShapes() {
        final Reporter reporter = new Reporter();
        final HealthRegistry registry = copy(reporter);
        final EndpointHealth first = registry.add(HealthRegistry.Kind.INBOUND, "file", "first", 10);
        final EndpointHealth second = registry.add(HealthRegistry.Kind.INBOUND, "file", "second", 10);
        first.fileDone();
        second.fileDone();
        first.refresh(true);
        second.refresh(true);
        assertEquals(9, reporter.registrations.size());
        final HealthRegistry redeploy = copy(reporter);
        redeploy.add(HealthRegistry.Kind.INBOUND, "file", "first", 10).fileDone();
        redeploy.tick();
        assertEquals(18, reporter.registrations.size());
        assertEquals(9, reporter.shapes.size());
        for (final MetricsExporter.Metric metric : MetricsExporter.Metric.values()) {
            final boolean counter = Arrays.asList("files_total", "failures_total", "transport_faults_total",
                    "lock_contention_total").contains(metric.name.substring(PREFIX.length()));
            final String labels = metric == MetricsExporter.Metric.STATE ? "[kind, name, state]" : "[kind, name]";
            assertEquals(Arrays.asList(counter ? "Counter" : "Gauge", labels), reporter.shapes.get(metric.name));
        }
        assertEquals(Arrays.asList(PREFIX + "files_total", PREFIX + "files_total", PREFIX + "files_total"),
                reporter.counters);
        assertNotSame(reporter.labelsKeptByLibrary.get(0), reporter.labelsKeptByLibrary.get(2));
        assertArrayEquals(new String[]{"INBOUND", "first"}, reporter.labelsKeptByLibrary.get(0));
        assertArrayEquals(new String[]{"INBOUND", "second"}, reporter.labelsKeptByLibrary.get(1));
        first.pollStarted();
        try {
            first.openSessions(4);
            first.failStatesPending(3);
            first.flag(EndpointHealth.Flag.FAIL_STATE, "file:///data");
            first.lockContention();
            first.fileFailed(EndpointHealthTest.transport(first.log));
            first.transportFault(EndpointHealthTest.transport(first.log));
            first.pollFinished(true, null);
            registry.tick();
            assertEquals(4.0, reporter.gauges.get(PREFIX + "open_sessions[INBOUND, first]"));
            assertEquals(3.0, reporter.gauges.get(PREFIX + "fail_states_pending[INBOUND, first]"));
            assertEquals(1.0, reporter.gauges.get(PREFIX + "fail_states_flagged[INBOUND, first]"));
            assertEquals(((Number) first.snapshot().get("lastSuccess")).longValue() / 1000.0,
                    reporter.gauges.get(PREFIX + "last_success_seconds[INBOUND, first]"));
            for (final EndpointHealth.State state : EndpointHealth.State.values()) {
                assertEquals(state == EndpointHealth.State.DEGRADED ? 1.0 : 0.0,
                        reporter.gauges.get(PREFIX + "state[INBOUND, first, " + state + "]"));
            }
            assertEquals(6, reporter.counters.size());
            assertTrue(reporter.counters.containsAll(Arrays.asList(PREFIX + "lock_contention_total",
                    PREFIX + "failures_total", PREFIX + "transport_faults_total")));
        } finally {
            first.paused();
        }
    }

    @Test
    void shapeClashAndLaterFalseTurnOffOnlyThatNameForTheCopy() {
        final Reporter reporter = new Reporter();
        reporter.shapes.put(PREFIX + "files_total", Arrays.asList("Counter", "[other]"));
        final HealthRegistry copy = copy(reporter);
        final EndpointHealth first = copy.add(HealthRegistry.Kind.INBOUND, "file", "one", 10);
        final EndpointHealth second = copy.add(HealthRegistry.Kind.INBOUND, "file", "two", 10);
        first.fileDone();
        first.lockContention();
        copy.tick();
        assertEquals(Arrays.asList(PREFIX + "lock_contention_total"), reporter.counters);
        reporter.writes.clear();
        reporter.refuseGauge = PREFIX + "open_sessions";
        first.openSessions(3);
        first.refresh(true);
        second.openSessions(5);
        second.refresh(true);
        assertEquals(1, reporter.writes.stream().filter(reporter.refuseGauge::equals).count());
        assertEquals(0.0, reporter.gauges.get(PREFIX + "open_sessions[INBOUND, one]"));
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("WARN ") &&
                line.contains("Gauge stopped") && line.contains("open_sessions")).count());
        second.failStatesPending(7);
        second.refresh(true);
        assertEquals(7.0, reporter.gauges.get(PREFIX + "fail_states_pending[INBOUND, two]"));
        assertEquals(2, reporter.service.size());
    }

    @Test
    void exporterExceptionsLeaveHealthAndTheOtherExporterRunning() {
        final Reporter reporter = new Reporter();
        final HealthRegistry registry = copy(reporter);
        final EndpointHealth first = registry.add(HealthRegistry.Kind.INBOUND, "file", "one", 10);
        final EndpointHealth second = registry.add(HealthRegistry.Kind.INBOUND, "file", "two", 10);
        reporter.failCounter = true;
        first.fileDone();
        second.fileDone();
        first.lockContention();
        registry.tick();
        assertEquals(1, reporter.counters.size());
        assertEquals(1L, second.snapshot().get("filesDone"));
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("WARN ") &&
                line.contains("counter failure")).count());
        first.pollStarted();
        try {
            first.pollFinished(false, EndpointHealthTest.transport(first.log));
            registry.tick();
            assertTrue(reporter.service.contains("down file-inbound one"));
            assertEquals(EndpointHealth.State.DOWN, first.state());
        } finally {
            first.paused();
        }
        final Reporter brokenService = new Reporter();
        brokenService.failService = true;
        final HealthRegistry other = copy(brokenService);
        other.add(HealthRegistry.Kind.INBOUND, "file", "one", 10).fileDone();
        other.add(HealthRegistry.Kind.INBOUND, "file", "two", 10).fileDone();
        other.tick();
        assertEquals(1, brokenService.service.size());
        assertEquals(2, brokenService.counters.size());
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("WARN ") &&
                line.contains("service failure")).count());
    }

    @Test
    void registrationExceptionIsReportedOnceAndThePlainGaugeSurvives() {
        final Reporter reporter = new Reporter();
        reporter.failCreate = true;
        final HealthRegistry registry = copy(reporter);
        registry.add(HealthRegistry.Kind.INBOUND, "file", "one", 10);
        registry.add(HealthRegistry.Kind.INBOUND, "file", "two", 10);
        assertEquals(1, reporter.registrations.size());
        assertEquals(2, reporter.service.size());
        assertTrue(reporter.writes.isEmpty());
        assertTrue(reporter.counters.isEmpty());
        assertEquals(1, lines.lines.stream().filter(line -> line.startsWith("WARN ") &&
                line.contains("registration failure")).count());
        assertFalse(lines.lines.stream().anyMatch(line -> line.startsWith("ERROR")));
    }
}
