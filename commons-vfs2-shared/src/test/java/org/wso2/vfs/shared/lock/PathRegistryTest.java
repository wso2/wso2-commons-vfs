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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PathRegistryTest {
    @Test
    void onlyTheOwnerClearsItsPathAndValue() {
        String key = PathRegistry.key("sftp://user:secret@HOST:22/data?pass=secret");
        assertEquals("sftp://host:22/data", key);
        assertEquals(PathRegistry.key("file:///a/../b"), PathRegistry.key("file:///b"));
        PathRegistry.Entry old = PathRegistry.enter(null, key);
        assertTrue(PathRegistry.held(old.value));
        assertNull(PathRegistry.enter(null, key));
        LockValue before = old.value;
        old.renew();
        assertFalse(PathRegistry.held(before));
        assertTrue(PathRegistry.held(old.value));
        old.clear();
        PathRegistry.Entry replacement = PathRegistry.enter(null, key);
        try {
            old.clear();
            assertNull(PathRegistry.enter(null, key));
            assertTrue(PathRegistry.held(replacement.value));
        } finally {
            replacement.clear();
        }
    }

    @Test
    void remoteKeysFoldOnlyTheHexDigitsOfAnEscape() {
        String root = "smb2://host:445/data/";
        assertEquals(PathRegistry.key(root + "invoice%5b1%5d"), PathRegistry.key(root + "invoice%5B1%5D"));
        assertNotEquals(PathRegistry.key(root + "invoice%255B1%255D"), PathRegistry.key(root + "invoice%5B1%5D"));
        assertNotEquals(PathRegistry.key(root + "invoice%255b"), PathRegistry.key(root + "invoice%255B"));
        assertNotEquals(PathRegistry.key(root + "invoice%2f1"), PathRegistry.key(root + "invoice/1"));
        assertNotEquals(PathRegistry.key(root + "Invoice"), PathRegistry.key(root + "invoice"));
        assertNotEquals(PathRegistry.key(root + "invoice%5b2%5d"), PathRegistry.key(root + "invoice%5b1%5d"));
    }
}
