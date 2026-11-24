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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;

import org.apache.commons.logging.Log;

public final class NamedLog {
    private static final Set<String> WARNED_CLASSES = ConcurrentHashMap.newKeySet();
    private final Log log;
    private final String prefix;
    private final RepeatLimiter limiter;

    private NamedLog(final Log log, final String kind, final String name, final RepeatLimiter limiter) {
        this.log = log;
        this.prefix = "[" + kind + ":" + UriMask.mask(name).replaceAll("\\R", " ") + "] ";
        this.limiter = limiter;
    }

    public static NamedLog inbound(final Log log, final String name, final RepeatLimiter limiter) {
        return new NamedLog(log, "inbound", name, limiter);
    }

    public static NamedLog connection(final Log log, final String name, final RepeatLimiter limiter) {
        return new NamedLog(log, "connection", name, limiter);
    }

    public boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    public void debug(final String text) {
        if (log.isDebugEnabled()) {
            log.debug(lines(UriMask.mask(text)));
        }
    }

    public void warn(final String text) {
        if (log.isWarnEnabled()) {
            final String safe = limiter.admit(UriMask.mask(text));
            if (safe != null) {
                log.warn(lines(safe));
            }
        }
    }

    /** A definite absence is reported once until the same poll root is seen again. */
    public void missingPollRoot(final String uri) {
        final String key = UriMask.mask(uri);
        final String message = "The poll root is absent. " + key;
        if (limiter.once(key)) {
            if (log.isWarnEnabled()) {
                log.warn(lines(message));
            }
        } else {
            debug(message);
        }
    }

    public void pollRootPresent(final String uri) {
        limiter.clear(UriMask.mask(uri));
    }

    public void warnUnknown(final Class<?> type, final String chain) {
        if (log.isWarnEnabled() && WARNED_CLASSES.add(type.getName())) {
            log.warn(lines(UriMask.mask("Unknown file system error. " + chain)));
        }
    }

    public void error(final String text) {
        if (log.isErrorEnabled()) {
            final String safe = limiter.admit(UriMask.mask(text));
            if (safe != null) {
                log.error(lines(safe));
            }
        }
    }

    private String lines(final String safe) {
        return prefix + safe.replaceAll("\\R", Matcher.quoteReplacement("\n" + prefix));
    }
}
