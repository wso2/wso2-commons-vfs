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
import java.util.concurrent.atomic.AtomicBoolean;

final class ServiceUpExporter implements MetricsExporter {
    private final Object reporter;
    private final Method up;
    private final Method down;
    private final AtomicBoolean enabled;

    ServiceUpExporter(final Object reporter, final AtomicBoolean enabled) throws NoSuchMethodException {
        this.reporter = reporter;
        this.enabled = enabled;
        up = reporter.getClass().getMethod("serviceUp", String.class, String.class);
        down = reporter.getClass().getMethod("serviceDown", String.class, String.class);
    }

    @Override
    public void setState(final EndpointHealth health, final EndpointHealth.State previous,
            final EndpointHealth.State state) {
        final boolean isDown = down(state);
        if (!enabled.get() || previous != null && down(previous) == isDown) {
            return;
        }
        final String service = health.kind == HealthRegistry.Kind.INBOUND ? "file-inbound" : "file-connection";
        try {
            MetricsExporter.invoke(isDown ? down : up, reporter, health.name, service);
        } catch (final RuntimeException ex) {
            if (enabled.getAndSet(false)) {
                EndpointHealth.writeLog(() -> health.log.warn("Service health export stopped. " + ex));
            }
        }
    }

    private static boolean down(final EndpointHealth.State state) {
        return state == EndpointHealth.State.DOWN || state == EndpointHealth.State.BREAKER_OPEN;
    }

    @Override
    public void setGauge(final EndpointHealth health, final Metric metric, final double value) {
        // The pack only has a service state gauge.
    }

    @Override
    public void addToCounter(final EndpointHealth health, final Metric metric, final long count) {
        // The pack has no file counter.
    }
}
