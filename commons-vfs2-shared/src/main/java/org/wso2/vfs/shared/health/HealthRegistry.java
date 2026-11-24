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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

public final class HealthRegistry {
    public enum Kind { INBOUND, CONNECTION }

    interface ReporterLookup {
        Object get() throws ReflectiveOperationException;
    }

    public static final class ConnectionRegistration implements AutoCloseable, Supplier<Map<String, Object>> {
        private final HealthRegistry copy;
        private final String connector;
        private final EndpointHealth health;
        private final boolean explicit;
        private boolean closed;

        private ConnectionRegistration(final HealthRegistry copy, final String connector,
                final EndpointHealth health, final boolean explicit) {
            this.copy = copy;
            this.connector = connector;
            this.health = health;
            this.explicit = explicit;
        }

        public EndpointHealth health() {
            return health;
        }

        @Override
        public Map<String, Object> get() {
            final Map<String, Object> result = health.snapshot();
            result.put("status", EndpointHealth.State.valueOf((String) result.get("state")).status());
            return result;
        }

        @Override
        public void close() {
            copy.remove(Kind.CONNECTION, connector, health.name, this);
        }
    }

    private static final String CONNECTIONS = "org.wso2.carbon.connector.core.ConnectionHealthRegistry";
    private static final String UTILS = "org.wso2.micro.integrator.observability.util.MetricUtils";
    private static final HealthRegistry COPY = new HealthRegistry(
            () -> MetricsExporter.invoke(Class.forName(UTILS).getMethod("getMetricReporter"), null),
            HealthRegistry.class.getClassLoader(), LogFactory.getLog(EndpointHealth.class));

    private final ReporterLookup lookup;
    private final ClassLoader loader;
    private final Log logger;
    private final Map<List<String>, EndpointHealth> entries = new ConcurrentHashMap<>();
    private final Map<List<String>, EndpointHealth> retiring = new ConcurrentHashMap<>();
    private final AtomicBoolean serviceEnabled = new AtomicBoolean(true);
    private final AtomicBoolean lookupEnabled = new AtomicBoolean(true);
    private final Map<List<String>, ConnectionRegistration> owners = new HashMap<>();
    private final AtomicReference<Thread> ticker = new AtomicReference<>();
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(10);
    private boolean registryChecked;
    private Method registerConnection;
    private Method unregisterConnection;
    private boolean registered;
    private ProductMetricsExporter product;

    HealthRegistry(final ReporterLookup lookup, final ClassLoader loader, final Log logger) {
        this.lookup = lookup;
        this.loader = loader;
        this.logger = logger;
    }

    public static EndpointHealth register(final Kind kind, final String name, final long scanIntervalMillis) {
        return COPY.add(kind, "file", name, scanIntervalMillis);
    }

    public static void unregister(final Kind kind, final String name) {
        COPY.remove(kind, "file", name, null);
    }

    public static ConnectionRegistration registerConnection(final String connector, final String name) {
        return COPY.connection(connector, name);
    }

    public static ConnectionRegistration registerConnection(final String connector, final String name,
            final EndpointHealth.RuntimeMetrics metrics) {
        return COPY.connection(connector, name, metrics);
    }

    ConnectionRegistration connection(final String identity, final String name) {
        return connection(identity, name, null);
    }

    private ConnectionRegistration connection(final String identity, final String name,
            final EndpointHealth.RuntimeMetrics metrics) {
        final String connector = "{org.wso2.carbon.connector}file".equals(identity) ? "file" : identity;
        final NamedLog log = namedLog(Kind.CONNECTION, name);
        final Object reporter = reporter(Kind.CONNECTION, connector, name, log);
        synchronized (this) {
            final List<String> key = Arrays.asList(Kind.CONNECTION.name(), connector, name);
            final EndpointHealth health = entry(key, Kind.CONNECTION, name, 0, log, reporter);
            health.observeRuntime(metrics);
            return attach(key, connector, health, true);
        }
    }

    private ConnectionRegistration attach(final List<String> key, final String connector,
            final EndpointHealth health, final boolean explicit) {
        if (!registryChecked) {
            registryChecked = true;
            try {
                final Class<?> type = loader.loadClass(CONNECTIONS);
                final Method add = type.getMethod("register", String.class, String.class, Supplier.class);
                unregisterConnection = type.getMethod("unregister", String.class, String.class, Supplier.class);
                registerConnection = add;
            } catch (final ClassNotFoundException | NoSuchMethodException ex) {
                EndpointHealth.writeLog(() -> health.log.debug("Connection health registry is unavailable."));
            } catch (final LinkageError | RuntimeException ex) {
                EndpointHealth.writeLog(() -> health.log.warn("Connection health registry lookup failed. " + ex));
            }
        }
        final ConnectionRegistration owner = new ConnectionRegistration(this, connector, health, explicit);
        owners.put(key, owner);
        registryCall(registerConnection, owner);
        return owner;
    }

    private static void registryCall(final Method method, final ConnectionRegistration owner) {
        if (method != null) {
            try {
                MetricsExporter.invoke(method, null, owner.connector, owner.health.name, owner);
            } catch (final RuntimeException | LinkageError ex) {
                EndpointHealth.writeLog(() -> owner.health.log.warn("Connection health registry call failed. " + ex));
            }
        }
    }

    synchronized void remove(final Kind kind, final String connector, final String name,
            final ConnectionRegistration expected) {
        final List<String> key = Arrays.asList(kind.name(), connector, name);
        final ConnectionRegistration owner = owners.get(key);
        if (expected != null) {
            if (expected.closed) {
                return;
            }
            expected.closed = true;
            registryCall(unregisterConnection, expected);
            if (owner != expected) {
                return;
            }
        } else if (owner != null) {
            if (owner.explicit) {
                return;
            }
            registryCall(unregisterConnection, owner);
        }
        owners.remove(key);
        final EndpointHealth old = entries.remove(key);
        if (old != null) {
            retiring.put(key, old);
            old.retire(() -> retiring.remove(key, old));
        }
        updateTicker();
    }

    private NamedLog namedLog(final Kind kind, final String name) {
        final RepeatLimiter limiter = new RepeatLimiter();
        return kind == Kind.INBOUND ? NamedLog.inbound(logger, name, limiter)
                : NamedLog.connection(logger, name, limiter);
    }

    private Object reporter(final Kind kind, final String connector, final String name, final NamedLog log) {
        if (!lookupEnabled.get()) {
            return null;
        }
        try {
            if (TestPoints.enabled()) {
                final boolean inbound = kind == Kind.INBOUND;
                final String key = inbound ? name : connector + ":" + name;
                TestPoints.hold("metrics.before-get-reporter", key, (inbound ? "endpoint=" : "connection=") + key);
            }
            final Object reporter = lookup.get();
            EndpointHealth.writeLog(() -> log.debug("Metric reporter identity " + System.identityHashCode(reporter)));
            return reporter;
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (lookupEnabled.getAndSet(false)) {
                EndpointHealth.writeLog(() -> log.warn("Metric reporter lookup stopped. " + ex));
            }
            return null;
        }
    }

    private void select(final EndpointHealth health, final Object reporter, final String connector) {
        if (reporter == null) {
            return;
        }
        if (!registered) {
            registered = true;
            if (TestPoints.enabled()) {
                final String who = health.kind == Kind.INBOUND ? "endpoint=" + health.name
                        : "connection=" + connector + ":" + health.name;
                TestPoints.hold("metrics.before-first-registration", null, who);
            }
            product = ProductMetricsExporter.register(reporter, health.log);
        }
        ServiceUpExporter service = null;
        if (serviceEnabled.get()) {
            try {
                service = new ServiceUpExporter(reporter, serviceEnabled);
            } catch (final NoSuchMethodException | RuntimeException | LinkageError ex) {
                if (serviceEnabled.getAndSet(false)) {
                    EndpointHealth.writeLog(() -> health.log.warn("Service health export stopped. " + ex));
                }
            }
        }
        if (service == null) {
            if (product != null) {
                health.exporters(product);
            }
        } else if (product == null) {
            health.exporters(service);
        } else {
            health.exporters(service, product);
        }
        if (product != null) {
            product.initCounters(health);
        }
    }

    EndpointHealth add(final Kind kind, final String connector, final String name, final long interval) {
        final NamedLog log = namedLog(kind, name);
        final Object reporter = reporter(kind, connector, name, log);
        synchronized (this) {
            final List<String> key = Arrays.asList(kind.name(), connector, name);
            final EndpointHealth health = entry(key, kind, name, interval, log, reporter);
            if (kind == Kind.CONNECTION && !owners.containsKey(key)) {
                attach(key, connector, health, false);
            }
            return health;
        }
    }

    private boolean active() {
        return entries.values().stream().anyMatch(EndpointHealth::active);
    }

    private void updateTicker() {
        final Thread current = ticker.get();
        if (current != null) {
            LockSupport.unpark(current);
        } else if (active()) {
            final Thread next = new Thread(this::runTicker, "File health ticker");
            next.setDaemon(true);
            if (ticker.compareAndSet(null, next)) {
                next.start();
            }
        }
    }

    private void runTicker() {
        try {
            long due = System.nanoTime() + TICK_NANOS;
            while (true) {
                final boolean running = active();
                entries.values().forEach(EndpointHealth::publishPause);
                if (!running) {
                    break;
                }
                final long remaining = due - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(this, remaining);
                } else {
                    tick();
                    due = System.nanoTime() + TICK_NANOS;
                }
            }
        } finally {
            ticker.set(null);
            updateTicker();
        }
    }

    void tick() {
        entries.values().forEach(entry -> entry.refresh(true));
    }

    Thread ticker() {
        return ticker.get();
    }

    private EndpointHealth entry(final List<String> key, final Kind kind, final String name,
            final long interval, final NamedLog log, final Object reporter) {
        final EndpointHealth old = entries.get(key);
        if (old != null) {
            old.scanInterval(interval);
            return old;
        }
        final EndpointHealth pending = retiring.remove(key);
        final EndpointHealth.Publication publication = pending == null
                ? new EndpointHealth.Publication() : pending.publication;
        final EndpointHealth health = new EndpointHealth(kind, name, interval, log,
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), System::currentTimeMillis,
                this::updateTicker, publication);
        select(health, reporter, key.get(1));
        entries.put(key, health);
        health.refresh(false);
        updateTicker();
        return health;
    }
}
