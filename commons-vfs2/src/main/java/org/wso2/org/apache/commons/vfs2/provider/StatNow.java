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
package org.wso2.org.apache.commons.vfs2.provider;

/**
 * The answer of {@link AtomicOps#statNow()}: "not there" by the rule of {@link AtomicOps#existsNow()}, or "there"
 * with the size and the modified time of the file.
 */
public final class StatNow {

    private static final StatNow NOT_THERE = new StatNow(false, -1, -1, false);

    private final boolean there;

    private final long size;

    private final long lastModifiedMillis;

    private final boolean fromListing;

    private StatNow(final boolean there, final long size, final long lastModifiedMillis, final boolean fromListing) {
        this.there = there;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
        this.fromListing = fromListing;
    }

    /**
     * Gets the answer for a name that the server said is not there.
     *
     * @return the answer.
     */
    public static StatNow notThere() {
        return NOT_THERE;
    }

    /**
     * Gets the answer for a name that is there.
     *
     * @param size the size of the file.
     * @param lastModifiedMillis the modified time of the file in milliseconds.
     * @param fromListing true when the values come from a listing of the parent and not from a command by name.
     * @return the answer.
     */
    public static StatNow there(final long size, final long lastModifiedMillis, final boolean fromListing) {
        return new StatNow(true, size, lastModifiedMillis, fromListing);
    }

    /**
     * Tells whether the name is there.
     *
     * @return true when the name is there.
     */
    public boolean isThere() {
        return there;
    }

    /**
     * Gets the size of the file.
     *
     * @return the size, or -1 when the name is not there.
     */
    public long getSize() {
        return size;
    }

    /**
     * Gets the modified time of the file.
     *
     * @return the time in milliseconds, or -1 when the name is not there.
     */
    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    /**
     * Tells where the values came from. Two answers are compared only when both came from the same source.
     *
     * @return true when the values come from a listing of the parent, false when they come from a command by name.
     */
    public boolean isFromListing() {
        return fromListing;
    }

    @Override
    public String toString() {
        return there ? "there size=" + size + " lastModified=" + lastModifiedMillis
                + (fromListing ? " fromListing" : "") : "not there";
    }
}
