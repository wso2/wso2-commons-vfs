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
package org.wso2.org.apache.commons.vfs2;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.ArrayUtils;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.util.Messages;

/**
 * Thrown for file system errors.
 */
public class FileSystemException extends IOException {

    /**
     * serialVersionUID format is YYYYMMDD for the date of the last binary change.
     */
    private static final long serialVersionUID = 20101208L;

    /** URL pattern */
    private static final Pattern URL_PATTERN = Pattern.compile("[a-zA-Z0-9]+://.*");

    // The password of the user info runs to the last @ before the host. It may hold a slash or another @, or white
    // space when it holds no slash. UriMask of the shared module uses the same pattern.
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s/:?#]*:)(?:[^\\s/]*(?:\\s+[^\\s/]+)+|[^\\s]*)"
                    + "(@(?:\\[[^\\]\\s]+\\]|[^\\s/@?:#,;'\"<>()\\[\\]]+))");

    // A query value whose name holds pass, secret or key, for example the proxyPassword that FTP reads from the query.
    // The FTP option passive is not a secret and stays readable. UriMask of the shared module uses the same names.
    private static final Pattern QUERY_SECRET_PATTERN = Pattern.compile(
            "([?&][^?&#=\\s]*(?:pass(?!ive)|secret|key)[^?&#=\\s]*=)[^&#\\s]*", Pattern.CASE_INSENSITIVE);

    /**
     * Throws a FileSystemException when the given object is null.
     *
     * @param obj
     *            the object reference to check for null.
     * @param code
     *            message used when {@code
     *                FileSystemException} is thrown
     * @param <T>
     *            the type of the reference
     * @return {@code obj} if not {@code null}
     * @throws FileSystemException
     *             if {@code obj} is {@code null}
     * @since 2.3
     */
    public static <T> T requireNonNull(final T obj, final String code) throws FileSystemException {
        if (obj == null) {
            throw new FileSystemException(code);
        }
        return obj;
    }

    /**
     * Throws a FileSystemException when the given object is null.
     *
     * @param obj
     *            the object reference to check for null.
     * @param code
     *            message used when {@code
     *                FileSystemException} is thrown
     * @param info
     *            one context information.
     * @param <T>
     *            the type of the reference
     * @return {@code obj} if not {@code null}
     * @throws FileSystemException
     *             if {@code obj} is {@code null}
     * @since 2.3
     */
    public static <T> T requireNonNull(final T obj, final String code, final Object... info) throws FileSystemException {
        if (obj == null) {
            throw new FileSystemException(code, info);
        }
        return obj;
    }

    /**
     * Array of complementary info (context).
     */
    private final String[] info;

    /**
     * What this error means for the connection. Null when the site that threw did not decide.
     */
    private Fault fault;

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     */
    public FileSystemException(final String code) {
        this(code, null, (Object[]) null);
    }

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     * @param info one context information.
     */
    public FileSystemException(final String code, final Object info) {
        this(code, null, new Object[] {info});
    }

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     * @param info array of complementary info (context).
     */
    public FileSystemException(final String code, final Object... info) {
        this(code, null, info);
    }

    /**
     * Constructs exception with the specified detail message and cause.
     *
     * @param code the error code of the message.
     * @param info one context information.
     * @param cause the cause.
     */
    public FileSystemException(final String code, final Object info, final Throwable cause) {
        this(code, cause, info);
    }

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     * @param info array of complementary info (context).
     * @param cause the cause.
     * @deprecated Use instead {@link #FileSystemException(String, Throwable, Object[])}. Will be removed in 3.0.
     */
    @Deprecated
    public FileSystemException(final String code, final Object[] info, final Throwable cause) {
        this(code, cause, info);
    }

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     * @param cause the original cause
     */
    public FileSystemException(final String code, final Throwable cause) {
        this(code, cause, (Object[]) null);
    }

    /**
     * Constructs exception with the specified detail message.
     *
     * @param code the error code of the message.
     * @param info array of complementary info (context).
     * @param cause the cause.
     */
    public FileSystemException(final String code, final Throwable cause, final Object... info) {
        super(code, cause);

        if (info == null) {
            this.info = ArrayUtils.EMPTY_STRING_ARRAY;
        } else {
            this.info = new String[info.length];
            for (int i = 0; i < info.length; i++) {
                String value = String.valueOf(info[i]);
                // mask passwords (VFS-169)
                final Matcher urlMatcher = URL_PATTERN.matcher(value);
                if (urlMatcher.find()) {
                    value = PASSWORD_PATTERN.matcher(value).replaceAll("$1***$2");
                    value = QUERY_SECRET_PATTERN.matcher(value).replaceAll("$1***");
                }
                this.info[i] = value;
            }
        }
    }

    /**
     * Constructs wrapper exception.
     *
     * @param cause the root cause to wrap.
     */
    public FileSystemException(final Throwable cause) {
        this(cause.getMessage(), cause, (Object[]) null);
    }

    /**
     * Retrieves error code of the exception. Could be used as key for internationalization.
     *
     * @return the code.
     */
    public String getCode() {
        return super.getMessage();
    }

    /**
     * Gets what this error means for the connection, as the site that threw decided it.
     *
     * @return the fault, or null when the site that threw did not decide.
     */
    public Fault getFault() {
        return fault;
    }

    /**
     * Sets what this error means for the connection. A provider sets it where the library exception is still in hand.
     *
     * @param fault the fault.
     */
    public void setFault(final Fault fault) {
        this.fault = fault;
    }

    /**
     * Retrieves array of complementary info (context). Could be used as parameter for internationalization.
     *
     * @return the context info.
     */
    public String[] getInfo() {
        return info;
    }

    /**
     * Retrieves message from bundle.
     *
     * @return The exception message.
     */
    @Override
    public String getMessage() {
        return Messages.getString(super.getMessage(), (Object[]) getInfo());
    }
}
