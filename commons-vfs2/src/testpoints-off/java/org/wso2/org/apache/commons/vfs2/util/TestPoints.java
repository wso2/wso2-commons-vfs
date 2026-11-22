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
package org.wso2.org.apache.commons.vfs2.util;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Named test points. This is the release form. It has no field, no file access, no thread and no property read.
 * The test build compiles another form of this class in its place.
 */
public final class TestPoints {

    /**
     * A registered snapshot supplier.
     */
    public interface Handle {

        /**
         * Removes the registration of this handle.
         */
        void remove();
    }

    /**
     * A fault that a test asked for at a point.
     */
    public interface Fault {

        /**
         * @return the token of the fault.
         */
        String token();

        /**
         * @return the arguments of the fault file.
         */
        Map<String, String> arguments();
    }

    private TestPoints() {
    }

    public static boolean enabled() {
        return false;
    }

    public static Map<String, String> hold(final String point, final String key, final String... values) {
        return Collections.emptyMap();
    }

    public static Map<String, String> arguments(final String point, final String key, final String... values) {
        return Collections.emptyMap();
    }

    public static Fault fault(final String point, final String key, final String... values) {
        return null;
    }

    public static void event(final String point, final String key, final String... values) {
    }

    public static Handle snapshot(final String name, final String key, final Supplier<String> supplier) {
        return () -> {
        };
    }

    public static long wallMillis(final String clock) {
        return System.currentTimeMillis();
    }

    public static long nanoTime(final String clock) {
        return System.nanoTime();
    }

    public static long slice(final String clock, final long millis) {
        return millis;
    }

    public static void copy(final String name, final Class<?>... anchors) {
    }
}
