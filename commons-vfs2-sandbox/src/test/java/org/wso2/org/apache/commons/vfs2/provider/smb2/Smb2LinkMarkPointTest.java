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
package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.share.DiskEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * The mark "no link" of an SMB2 folder pair on the clock linkmark of the test build: 10 minutes and not longer, no
 * call while it holds, and the first call after it links again.
 */
@Tag("testbuild")
public class Smb2LinkMarkPointTest {

    private static final String DIR = "vfs.testpoints.dir";

    @TempDir
    Path base;

    private Path copy;

    @BeforeEach
    public void setUp() {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        TestPoints.wallMillis("setup");
        copy = base.resolve("unit.1");
        assertTrue(Files.exists(copy.resolve("copy")));
    }

    @AfterEach
    public void tearDown() throws Exception {
        clock(0);
        System.clearProperty(DIR);
    }

    private void clock(final long offsetMillis) throws Exception {
        final Path next = copy.resolve("clock.linkmark.mono.next");
        Files.write(next, String.valueOf(offsetMillis).getBytes(StandardCharsets.UTF_8));
        Files.move(next, copy.resolve("clock.linkmark.mono"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    public void markHoldsTenMinutesWithNoCallAndTheFirstCallAfterItLinks() throws Exception {
        final Client client = new Client();
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(new Factory(client.wrapper));
        final DiskEntry entry = mock(DiskEntry.class);
        doReturn(entry).when(client.share).open(eq("l67src\\a.csv"),
                eq(EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES)), any(), eq(SMB2ShareAccess.ALL),
                eq(SMB2CreateDisposition.FILE_OPEN), isNull());
        final SMBApiException denied = new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(),
                SMB2MessageCommandCode.SMB2_SET_INFO, "denied", null);
        doThrow(denied).doNothing().when(entry).createHardlink(anyString(), anyBoolean());
        final Smb2FileObject source = new Smb2FileObject(Smb2Doubles.name("l67src/a.csv"), fileSystem,
                Smb2Doubles.rootName());
        final Smb2FileObject target = new Smb2FileObject(Smb2Doubles.name("l67dst/a.csv"), fileSystem,
                Smb2Doubles.rootName());
        clock(0);
        assertEquals(AtomicOutcome.UNSUPPORTED, source.linkExclusive(target, "p").getOutcome());
        assertEquals(Answer.NO, source.atomicCapabilities(target).getExclusivePublish());
        clock(TimeUnit.MINUTES.toMillis(1));
        assertEquals(AtomicOutcome.UNSUPPORTED, source.linkExclusive(target, "p").getOutcome());
        clock(TimeUnit.MINUTES.toMillis(9));
        assertEquals(AtomicOutcome.UNSUPPORTED, source.linkExclusive(target, "p").getOutcome());
        verify(entry, times(1)).createHardlink(anyString(), anyBoolean());
        clock(TimeUnit.MINUTES.toMillis(10) + 1000);
        assertEquals(Answer.NOT_KNOWN, source.atomicCapabilities(target).getExclusivePublish());
        assertEquals(AtomicOutcome.DONE, source.linkExclusive(target, "p").getOutcome());
        verify(entry, times(2)).createHardlink("l67dst\\a.csv", false);
        assertEquals(Answer.YES, source.atomicCapabilities(target).getExclusivePublish());
        doNothing().when(entry).close();
    }
}
