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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.impl.SimpleLog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

@Tag("testbuild")
class MetricsBarrierTest {
    private static final String GET = "metrics.before-get-reporter";
    private static final String FIRST = "metrics.before-first-registration";

    public static final class Fixture {
        private final HealthRegistry registry;
        private final boolean inbound;

        public Fixture(final String copy, final Supplier<Object> reporter, final Log log) {
            TestPoints.copy(copy, HealthRegistry.class);
            registry = new HealthRegistry(reporter::get, getClass().getClassLoader(), log);
            inbound = "inbound".equals(copy);
        }

        public void register(final String name) {
            if (inbound) {
                final EndpointHealth health = registry.add(HealthRegistry.Kind.INBOUND, "file", name, 10000);
                health.fileDone();
                health.refresh(true);
            } else {
                try (HealthRegistry.ConnectionRegistration owner = registry.connection("file", name)) {
                    owner.health().success();
                }
            }
        }
    }

    private static final class Copy extends URLClassLoader {
        final Object fixture;
        final Path folder;

        Copy(final String name, final Path root, final Supplier<Object> reporter, final Log log) throws Exception {
            super(new URL[]{location(HealthRegistry.class), location(TestPoints.class), location(Fixture.class)},
                    MetricsBarrierTest.class.getClassLoader());
            fixture = loadClass(Fixture.class.getName()).getConstructor(String.class, Supplier.class, Log.class)
                    .newInstance(name, reporter, log);
            try (Stream<Path> paths = Files.list(root)) {
                folder = paths.filter(path -> path.getFileName().toString().startsWith(name + "."))
                        .findFirst().get();
            }
        }

        private static URL location(final Class<?> type) {
            return type.getProtectionDomain().getCodeSource().getLocation();
        }

        @Override
        protected synchronized Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.wso2.vfs.shared.health.") || name.startsWith(TestPoints.class.getName())) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
            return super.loadClass(name, resolve);
        }

        void register(final String name) throws Exception {
            fixture.getClass().getMethod("register", String.class).invoke(fixture, name);
        }

        void hold(final String point) throws Exception {
            Files.write(folder.resolve(point + ".hold"), Arrays.asList("wait=20"), StandardCharsets.UTF_8);
        }

        void release(final String point) throws Exception {
            Files.delete(folder.resolve(point + ".hold"));
        }

        List<String> reached(final String point, final int count) throws Exception {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                final List<Path> records;
                try (Stream<Path> paths = Files.list(folder)) {
                    records = paths.filter(path -> path.getFileName().toString().startsWith(point)
                                    && path.getFileName().toString().endsWith(".reached")).collect(Collectors.toList());
                }
                if (records.size() == count) {
                    final List<String> lines = new ArrayList<>();
                    for (final Path record : records) {
                        lines.addAll(Files.readAllLines(record, StandardCharsets.UTF_8));
                    }
                    return lines;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Missing hold records for " + folder + "/" + point);
        }
    }

    @Test
    void fourLookupsArriveBeforeLookupAndTwoCopiesHoldBeforeRegistration(@TempDir final Path root) throws Exception {
        final String previous = System.getProperty("vfs.testpoints.dir");
        System.setProperty("vfs.testpoints.dir", root.toString());
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final AtomicInteger lookups = new AtomicInteger();
        final MetricsExporterTest.Reporter reporter = new MetricsExporterTest.Reporter();
        final Supplier<Object> lookup = () -> {
            lookups.incrementAndGet();
            return reporter;
        };
        final AtomicInteger identities = new AtomicInteger();
        final SimpleLog log = new SimpleLog("barriers") {
            private static final long serialVersionUID = 1L;

            @Override
            protected void log(final int level, final Object message, final Throwable cause) {
                if (message.toString().endsWith("Metric reporter identity " + System.identityHashCode(reporter))) {
                    identities.incrementAndGet();
                }
            }
        };
        log.setLevel(SimpleLog.LOG_LEVEL_DEBUG);
        try (Copy inbound = new Copy("inbound", root, lookup, log);
             Copy connector = new Copy("connector", root, lookup, log)) {
            final List<Future<?>> calls = new ArrayList<>();
            for (final Copy copy : Arrays.asList(inbound, connector)) {
                copy.hold(GET);
                copy.hold(FIRST);
                for (final String name : Arrays.asList("one", "two")) {
                    calls.add(workers.submit(() -> {
                        copy.register(name);
                        return null;
                    }));
                }
            }
            final List<String> endpoints = inbound.reached(GET, 2);
            final List<String> connections = connector.reached(GET, 2);
            for (final String name : Arrays.asList("one", "two")) {
                assertTrue(endpoints.stream().anyMatch(line -> line.contains("key=" + name + "\t")
                        && line.contains("endpoint=" + name)));
                assertTrue(connections.stream().anyMatch(line -> line.contains("key=file:" + name + "\t")
                        && line.contains("connection=file:" + name)));
            }
            assertEquals(0, lookups.get());
            inbound.release(GET);
            connector.release(GET);
            assertTrue(inbound.reached(FIRST, 1).get(0).contains("endpoint="));
            assertTrue(connector.reached(FIRST, 1).get(0).contains("connection=file:"));
            assertTrue(reporter.registrations.isEmpty());
            inbound.release(FIRST);
            connector.release(FIRST);
            for (final Future<?> call : calls) {
                call.get(8, TimeUnit.SECONDS);
            }
            assertEquals(4, lookups.get());
            assertEquals(4, identities.get());
            assertEquals(18, reporter.registrations.size());
            assertEquals(9, reporter.shapes.size());
            assertEquals(4, reporter.counters.size());
            assertTrue(reporter.service.containsAll(Arrays.asList("up file-inbound one", "up file-inbound two",
                    "up file-connection one", "up file-connection two")));
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
            if (previous == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir", previous);
            }
        }
    }
}
