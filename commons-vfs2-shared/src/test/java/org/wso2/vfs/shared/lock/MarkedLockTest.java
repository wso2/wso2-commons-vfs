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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.wso2.vfs.shared.health.HealthRegistry;

@Tag("testbuild")
class MarkedLockTest {
    @Test
    void equalNamesSelectOnlyTheRequestedOwnerKind(@TempDir Path root) throws Exception {
        String previous = System.getProperty("vfs.testpoints.dir");
        String name = "sharedName";
        String data = root.resolve("input").toUri().toString();
        System.setProperty("vfs.testpoints.dir", root.toString());
        try {
            TestPoints.copy("locks", ShareLock.class);
            ShareLock inbound = ShareLock.create(name, true, 60000, HealthRegistry.register(HealthRegistry.Kind.INBOUND, name, 5000));
            ShareLock connector = ShareLock.create(name, true, 60000, HealthRegistry.register(HealthRegistry.Kind.CONNECTION, name, 5000));
            Path copy;
            try (Stream<Path> files = Files.list(root)) {
                copy = files.filter(p -> Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            }
            ShareLockTest.Server io = new ShareLockTest.Server() {
                @Override AtomicResult create(String uri, LockValue value, Runnable beforeLink) throws IOException {
                    assertNotNull(beforeLink);
                    beforeLink.run();
                    return super.create(uri, value, beforeLink);
                }
            };
            for (String selector : Arrays.asList("endpoint="+name,"connection="+name)) {
                List<String> points = Arrays.asList("takeover.before-take-away","takeover.after-read",
                        "sharelock.after-look","sharelock.before-exclusive-link","lock.orphan-sweep");
                for (String point : points) {
                    Files.write(copy.resolve(point+".hold"), Arrays.asList(selector,"wait=0"), StandardCharsets.UTF_8);
                }
                Path events = copy.resolve("events.log");
                int first = Files.readAllLines(events,StandardCharsets.UTF_8).size();
                for (ShareLock owner : Arrays.asList(inbound,connector)) {
                    io.files.put(data+".lock","1:1".getBytes(StandardCharsets.UTF_8));
                    HeldLock held = owner.acquire(io,data,ShareLock.Mode.WRITER,null).handle;
                    assertNotNull(held);
                    assertEquals(LockOutcome.Kind.OURS,held.verifyOwner(io).kind);
                    assertEquals(LockOutcome.Kind.RELEASED,held.releaseChecked(io).kind);
                    io.files.put(data+".lock","1:1".getBytes(StandardCharsets.UTF_8));
                    FolderListing listing = FolderListing.of(root.toUri().toString(),
                            java.util.Collections.singletonMap("input.lock",new FolderListing.Metadata(0,0)));
                    assertEquals(1,owner.sweepOrphans(io,listing));
                }
                List<String> all = Files.readAllLines(events,StandardCharsets.UTF_8);
                List<String> observed = all.subList(first,all.size());
                for (String point : points) {
                    long held = observed.stream().filter(line -> line.contains("point=hold-reached")
                            && line.contains("at="+point+"\t") && line.contains("\t"+selector)
                            && line.contains("key="+LockIo.pointKey(data)+"\t")).count();
                    assertEquals(point.startsWith("takeover.") ? 2 : 1,held,point);
                }
                for (String kind : Arrays.asList("endpoint=","connection=")) {
                    for (String point : Arrays.asList("lock.verify-owner","lock.handle-end")) {
                        assertEquals(1,observed.stream().filter(l -> l.contains("point="+point+"\t")
                                && l.contains("\t"+kind+name+"\t") && l.contains("\thandle=")).count(),point);
                    }
                }
            }
        } finally {
            HealthRegistry.unregister(HealthRegistry.Kind.INBOUND,name);
            HealthRegistry.unregister(HealthRegistry.Kind.CONNECTION,name);
            if (previous == null) {
                System.clearProperty("vfs.testpoints.dir");
            } else {
                System.setProperty("vfs.testpoints.dir",previous);
            }
        }
    }
}
