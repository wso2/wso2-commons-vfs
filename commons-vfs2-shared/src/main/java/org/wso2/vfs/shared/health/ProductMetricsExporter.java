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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.wso2.vfs.shared.log.NamedLog;

final class ProductMetricsExporter implements MetricsExporter {
    private final Object reporter;
    private final Method setGauge;
    private final Method incrementCount;
    private final Set<Metric> supported = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private ProductMetricsExporter(final Object reporter, final Method setGauge, final Method incrementCount) {
        this.reporter = reporter;
        this.setGauge = setGauge;
        this.incrementCount = incrementCount;
    }

    static ProductMetricsExporter register(final Object reporter, final NamedLog log) {
        try {
            final Class<?> type = reporter.getClass();
            final Method create = type.getMethod("createComponentMetric", String.class, String.class, String.class,
                    String[].class);
            final Method gauge = type.getMethod("setGauge", String.class, String[].class, double.class);
            final ProductMetricsExporter exporter = new ProductMetricsExporter(reporter, gauge,
                    type.getMethod("incrementCount", String.class, String[].class));
            for (final Metric metric : Metric.values()) {
                final String[] labels = metric == Metric.STATE ? new String[]{"kind", "name", "state"}
                        : new String[]{"kind", "name"};
                if (Boolean.TRUE.equals(MetricsExporter.invoke(create, reporter, metric.type, metric.name,
                        metric.help, labels))) {
                    exporter.supported.add(metric);
                }
            }
            return exporter.supported.isEmpty() ? null : exporter;
        } catch (final NoSuchMethodException ex) {
            return null;
        } catch (final RuntimeException | LinkageError ex) {
            EndpointHealth.writeLog(() -> log.warn("File metric export stopped. " + ex));
            return null;
        }
    }

    @Override
    public void setGauge(final EndpointHealth health, final Metric metric, final double value) {
        writeGauge(health, metric, new String[]{health.kind.name(), health.name}, value);
    }

    private void writeGauge(final EndpointHealth health, final Metric metric, final String[] labels,
            final double value) {
        if (!enabled.get() || !supported.contains(metric)) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(MetricsExporter.invoke(setGauge, reporter, metric.name, labels, value))
                    && supported.remove(metric)) {
                EndpointHealth.writeLog(() -> health.log.warn("Gauge stopped accepting values. " + metric.name));
            }
        } catch (final RuntimeException ex) {
            stop(health, ex);
        }
    }

    @Override
    public void addToCounter(final EndpointHealth health, final Metric metric, final long count) {
        if (enabled.get() && supported.contains(metric)) {
            try {
                final String[] labels = {health.kind.name(), health.name};
                for (long event = 0; event < count && enabled.get(); event++) {
                    MetricsExporter.invoke(incrementCount, reporter, metric.name, labels);
                }
            } catch (final RuntimeException ex) {
                stop(health, ex);
            }
        }
    }

    private void stop(final EndpointHealth health, final RuntimeException ex) {
        if (enabled.getAndSet(false)) {
            EndpointHealth.writeLog(() -> health.log.warn("File metric export stopped. " + ex));
        }
    }

    @Override
    public void setState(final EndpointHealth health, final EndpointHealth.State previous,
            final EndpointHealth.State state) {
        for (final EndpointHealth.State label : EndpointHealth.State.values()) {
            if (previous == null || label == previous || label == state) {
                writeGauge(health, Metric.STATE, new String[]{health.kind.name(), health.name, label.name()},
                        label == state ? 1 : 0);
            }
        }
    }
}
