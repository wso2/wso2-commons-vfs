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
package org.wso2.vfs.shared.log;

import java.util.regex.Pattern;

import org.wso2.org.apache.commons.vfs2.FileName;

public final class UriMask {
    // The password runs to the last @ before the host. It may hold a slash or another @, or white space when it holds
    // no slash, so it never runs into the text after an address. FileSystemException uses the same pattern.
    private static final Pattern PASSWORD = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s/:?#]*:)(?:[^\\s/]*(?:\\s+[^\\s/]+)+|[^\\s]*)"
                    + "(@(?:\\[[^\\]\\s]+\\]|[^\\s/@?:#,;'\"<>()\\[\\]]+))");
    // The same names as the query mask of FileSystemException. The FTP option passive is not a secret.
    private static final Pattern OPTION = Pattern.compile(
            "([?&][^?&#=\\s]*(?:pass(?!ive)|secret|key)[^?&#=\\s]*=)[^&#\\s]*", Pattern.CASE_INSENSITIVE);

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
