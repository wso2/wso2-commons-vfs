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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

public class CapabilityClockTest {
    public static void assertPersistent(Callable<Answer> remote) throws Exception {
        final AtomicLong time = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        try (StandardFileSystemManager fs = new StandardFileSystemManager();
                MockedStatic<TestPoints> clock = mockStatic(TestPoints.class, CALLS_REAL_METHODS)) {
            clock.when(() -> TestPoints.nanoTime("linkmark")).thenAnswer(call -> time.get());
            fs.init();
            final FileObject local = fs.resolveFile("file:///capability-clock/control");
            final String folder = local.getName().getParent().getPath();
            final LocalFileSystem.LinkMemory mark = ((LocalFileSystem)local.getFileSystem()).getLinkMemory(folder+"\0"+folder);
            for (int count=0; count<3; count++) {
                time.addAndGet(TimeUnit.SECONDS.toNanos(10));
                mark.count(time.get());
            }
            AtomicOps ops = AtomicOps.of(local);
            assertEquals(Answer.NO,ops.atomicCapabilities(local).getExclusivePublish());
            assertEquals(Answer.NO,remote.call());
            time.addAndGet(TimeUnit.HOURS.toNanos(1));
            assertEquals(Answer.NOT_KNOWN,ops.atomicCapabilities(local).getExclusivePublish());
            assertEquals(Answer.NO,remote.call());
        }
    }

    @Test
    void expiringRemoteAnswerFailsThePersistenceCheck() {
        final long[] mark = {-1};
        assertThrows(AssertionError.class, () -> assertPersistent(() -> {
            long now=TestPoints.nanoTime("linkmark");
            if (mark[0]<0) mark[0]=now;
            return now-mark[0]>=TimeUnit.MINUTES.toNanos(10) ? Answer.NOT_KNOWN : Answer.NO;
        }));
    }
}
