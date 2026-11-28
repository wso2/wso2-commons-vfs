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
package org.wso2.vfs.shared.lock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.wso2.vfs.shared.health.EndpointHealth;
import org.wso2.vfs.shared.health.HealthRegistry;
import org.wso2.vfs.shared.log.LoggingTest.Lines;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

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

    // VFS takes the user info up to the first @ as typed. A password may hold a raw # or a character that a URI
    // does not allow there. The key holds no user info, and each file keeps its own key.
    private static final List<String> SECRETS = Arrays.asList("Synthetic#One", "Synthetic One", "Synthetic{One}",
            "Synthetic|One", "Synthetic\"One", "Synthetic^One`", "Synthetic#");

    @Test
    void userInfoStaysOutOfTheKeyAsVfsReadsIt() {
        for (String secret : SECRETS) {
            String user = "sftp://unit:" + secret;
            assertEquals("sftp://host:22/data/a.txt", PathRegistry.key(user + "@HOST:22/data/a.txt"));
            assertEquals("sftp://host:22/data/b.txt", PathRegistry.key(user + "@host:22/data/b.txt"));
            assertEquals("sftp://host:22/data", PathRegistry.key(user + "@host:22/data/"));
            assertEquals(user + "@host:22/data", PathRegistry.dataUri(user + "@host:22/data//"));
            assertEquals("smb2://host:445/share/a.txt",
                    PathRegistry.key("smb2://domain;unit:" + secret + "@host:445/share/a.txt"));
        }
        assertEquals(PathRegistry.key("sftp://unit:Synthetic%23One@host:22/data/a.txt"),
                PathRegistry.key("sftp://unit:Synthetic#One@host:22/data/a.txt"));
        assertEquals("ftp://host/data/a.txt", PathRegistry.key("ftp://un#it:Synthetic@host/data/a.txt"));
        assertEquals("sftp://host:22/data/a.txt", PathRegistry.key("sftp://unit:Synthetic@One@host:22/data/a.txt"));
        assertEquals("sftp://host:22/data/a@b.txt", PathRegistry.key("sftp://host:22/data/a@b.txt"));
    }

    @Test
    void aLockWarningNamesNoUserInfo() {
        long[] later = {0};
        String name = UUID.randomUUID().toString();
        EndpointHealth health = HealthRegistry.register(HealthRegistry.Kind.INBOUND, name, 5000);
        Lines sink = new Lines();
        ShareLock locks = new ShareLock(name, true, 900000, health, NamedLog.inbound(sink, name, new RepeatLimiter()),
                System::currentTimeMillis, () -> System.nanoTime() + later[0]);
        String folder = "/data/" + UUID.randomUUID() + "/";
        List<HeldLock> held = new ArrayList<>();
        try {
            for (String secret : SECRETS) {
                LockOutcome outcome = locks.enterLocalOnly("sftp://unit:" + secret + "@host:22" + folder
                        + held.size() + ".txt", 1, 0);
                assertEquals(LockOutcome.Kind.ACQUIRED, outcome.kind, "file " + held.size());
                held.add(outcome.handle);
            }
            later[0] = TimeUnit.MINUTES.toNanos(8);
            assertEquals(SECRETS.size(), locks.checkHeldAges());
            assertEquals(SECRETS.size(), sink.lines.size());
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < held.size(); i++) {
                expected.add("Lock held for more than half the stale age at sftp://host:22" + folder + i + ".txt");
            }
            for (String line : sink.lines) {
                assertFalse(line.contains("Synthetic") || line.contains("unit"), line);
                assertTrue(expected.removeIf(line::endsWith), line);
            }
            assertTrue(expected.isEmpty(), expected.toString());
        } finally {
            held.forEach(HeldLock::abandon);
        }
    }
}
