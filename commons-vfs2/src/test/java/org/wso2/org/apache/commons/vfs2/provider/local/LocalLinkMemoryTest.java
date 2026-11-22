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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.local.LocalFileSystem.LinkMemory;

/**
 * The memory rule of a mount that can not link, part 3 section 6.6, on given times of the monotonic clock.
 */
public class LocalLinkMemoryTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);

    @Test
    public void threeResultsInsideTenSecondsDoNotMark() {
        final LinkMemory memory = new LinkMemory();
        final long start = 1000 * SECOND;
        assertFalse(memory.count(start));
        assertFalse(memory.count(start + 3 * SECOND));
        assertFalse(memory.count(start + 9 * SECOND));
        assertFalse(memory.isMarked(start + 9 * SECOND));
    }

    @Test
    public void thirdResultTenSecondsAfterTheOneBeforeMarksWithOneWarning() {
        final LinkMemory memory = new LinkMemory();
        final long start = 1000 * SECOND;
        assertFalse(memory.count(start));
        // Inside 10 s: not counted.
        assertFalse(memory.count(start + 5 * SECOND));
        assertFalse(memory.count(start + 10 * SECOND));
        assertFalse(memory.isMarked(start + 10 * SECOND));
        assertTrue(memory.count(start + 20 * SECOND));
        assertTrue(memory.isMarked(start + 20 * SECOND));
        assertFalse(memory.isLinked());
    }

    @Test
    public void markEndsAfterTenMinutesAndTheNextFailureMarksAgainWithNoCountAndNoWarning() {
        final LinkMemory memory = marked(0);
        final long markedAt = 20 * SECOND;
        assertTrue(memory.isMarked(markedAt + 10 * MINUTE - 1));
        assertFalse(memory.isMarked(markedAt + 10 * MINUTE));
        assertTrue(memory.isAfterMark());
        memory.mark(markedAt + 10 * MINUTE);
        assertTrue(memory.isMarked(markedAt + 20 * MINUTE - 1));
        // A later result of the test does not log the warning again.
        assertFalse(memory.count(markedAt + 30 * MINUTE));
    }

    @Test
    public void linkThatWorksMarksLinksAndIsNeverTakenBack() {
        final LinkMemory memory = marked(0);
        memory.markLinks();
        assertTrue(memory.isLinked());
        assertFalse(memory.isMarked(30 * SECOND));
        assertFalse(memory.isAfterMark());
        memory.mark(40 * SECOND);
        assertFalse(memory.count(60 * SECOND));
        assertFalse(memory.count(80 * SECOND));
        assertFalse(memory.count(100 * SECOND));
        assertFalse(memory.isMarked(100 * SECOND));
        assertTrue(memory.isLinked());
    }

    @Test
    public void linkClearsTheCount() {
        final LinkMemory memory = new LinkMemory();
        memory.count(0);
        memory.count(10 * SECOND);
        final LinkMemory fresh = new LinkMemory();
        fresh.count(0);
        fresh.count(10 * SECOND);
        assertTrue(fresh.count(20 * SECOND));
        assertFalse(memory.isMarked(15 * SECOND));
    }

    private static LinkMemory marked(final long start) {
        final LinkMemory memory = new LinkMemory();
        memory.count(start);
        memory.count(start + 10 * SECOND);
        assertTrue(memory.count(start + 20 * SECOND));
        return memory;
    }
}
