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
package org.wso2.org.apache.commons.vfs2.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * The two static helpers of AtomicOps and its value types.
 */
public class AtomicOpsTest {

    @Test
    public void providerWithNoCallsGivesNullAndItsLookIsARefreshAndAnExists() throws Exception {
        final StandardFileSystemManager manager = new StandardFileSystemManager();
        manager.init();
        try {
            final FileObject file = manager.resolveFile("ram:///in/a.csv");
            assertNull(AtomicOps.of(file));
            assertFalse(AtomicOps.existsNow(file));
            // A second object of the same name writes it. The look of the first one asks again.
            manager.resolveFile("ram:///in/a.csv").createFile();
            assertTrue(AtomicOps.existsNow(file));
        } finally {
            manager.close();
        }
    }

    @Test
    public void resultKeepsACopyOfItsLeftovers() {
        final List<String> leftovers = new ArrayList<>(Arrays.asList("a.lock"));
        final AtomicResult result = new AtomicResult(AtomicOutcome.EXISTS, "id 4", leftovers, true);
        leftovers.add("b.lock");
        assertEquals(Arrays.asList("a.lock"), result.getLeftovers());
        assertThrows(UnsupportedOperationException.class, () -> result.getLeftovers().add("c.lock"));
        assertEquals(AtomicOutcome.EXISTS, result.getOutcome());
        assertEquals("id 4", result.getDetail());
        assertTrue(result.isConnectionLost());
        assertTrue(new AtomicResult(AtomicOutcome.DONE, null).getLeftovers().isEmpty());
        assertFalse(new AtomicResult(AtomicOutcome.DONE, null).isConnectionLost());
    }

    @Test
    public void statSaysWhereItsValuesCameFrom() {
        assertFalse(StatNow.notThere().isThere());
        final StatNow listed = StatNow.there(10, 20000, true);
        assertTrue(listed.isThere());
        assertEquals(10, listed.getSize());
        assertEquals(20000, listed.getLastModifiedMillis());
        assertTrue(listed.isFromListing());
        assertFalse(StatNow.there(10, 20000, false).isFromListing());
    }
}
