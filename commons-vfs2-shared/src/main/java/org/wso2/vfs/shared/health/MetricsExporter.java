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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

interface MetricsExporter {
    enum Metric {
        STATE("Gauge", "state", "Current file service state"),
        LAST_SUCCESS("Gauge", "last_success_seconds", "Time of last successful poll or operation in seconds"),
        FILES("Counter", "files_total", "Files or operations completed"),
        FAILURES("Counter", "failures_total", "Files or operations failed"),
        TRANSPORT_FAULTS("Counter", "transport_faults_total", "Transport faults"),
        LOCK_CONTENTION("Counter", "lock_contention_total", "Lock contentions"),
        PENDING("Gauge", "fail_states_pending", "Pending failed file actions"),
        FLAGGED("Gauge", "fail_states_flagged", "Flagged failed file actions"),
        OPEN_SESSIONS("Gauge", "open_sessions", "Open file sessions");

        final String type;
        final String name;
        final String help;

        Metric(final String type, final String suffix, final String help) {
            this.type = type;
            name = "wso2_integration_file_" + suffix;
            this.help = help;
        }
    }

    void setGauge(EndpointHealth health, Metric metric, double value);

    void addToCounter(EndpointHealth health, Metric metric, long count);

    void setState(EndpointHealth health, EndpointHealth.State previous, EndpointHealth.State state);

    static Object invoke(final Method method, final Object target, final Object... args) {
        try {
            return method.invoke(target, args);
        } catch (final InvocationTargetException ex) {
            throw new IllegalStateException(ex.getCause());
        } catch (final IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
