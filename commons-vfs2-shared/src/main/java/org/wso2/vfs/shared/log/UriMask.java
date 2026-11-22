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
package org.wso2.vfs.shared.log;

import java.util.regex.Pattern;

import org.wso2.org.apache.commons.vfs2.FileName;

public final class UriMask {
    private static final Pattern PASSWORD = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s/:?#]*:)[^\\s]*"
                    + "(@(?:\\[[^\\]\\s]+\\]|[^\\s/@?:#,;'\"<>()\\[\\]]+))");
    private static final Pattern OPTION = Pattern.compile(
            "([?&][^?&#=\\s]*(?:pass|secret|key)[^?&#=\\s]*=)[^&#\\s]*", Pattern.CASE_INSENSITIVE);

    private UriMask() {
    }

    public static String friendly(final FileName name) {
        return mask(name.getFriendlyURI());
    }

    public static String mask(final String text) {
        if (text == null) {
            return null;
        }
        final String safe = PASSWORD.matcher(text).replaceAll("$1***$2");
        return OPTION.matcher(safe).replaceAll("$1***");
    }
}
