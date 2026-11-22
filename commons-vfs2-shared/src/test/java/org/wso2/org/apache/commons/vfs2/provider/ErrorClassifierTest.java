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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.FileNotFolderException;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.vfs.shared.error.Classification;
import org.wso2.vfs.shared.error.ErrorClassifier;
import org.wso2.vfs.shared.error.ErrorClassifier.CallType;
import org.wso2.vfs.shared.error.ErrorKind;
import org.wso2.vfs.shared.log.LoggingTest.Lines;
import org.wso2.vfs.shared.log.NamedLog;
import org.wso2.vfs.shared.log.RepeatLimiter;

import com.jcraft.jsch.SftpException;

public class ErrorClassifierTest {
    private final Lines sink = new Lines();
    private final ErrorClassifier classifier = new ErrorClassifier(NamedLog.connection(sink, "test", new RepeatLimiter()));

    @Test
    public void unclearCarriesPhaseTempAndConfirmedTargetForEveryCall() {
        for (final MovePhase phase : MovePhase.values()) {
            for (final CallType call : CallType.values()) {
                final String temp = "sftp://user:SyntheticPath@host/out/attempt.part";
                final Throwable unclear = new UnclearResultException("vfs.provider/rename.error", new SocketException(),
                        phase, temp, phase == MovePhase.AFTER_DELETE);
                final Classification result = classifier.classify(new Exception(unclear), 4999, call);
                assertEquals(ErrorKind.UNCLEAR, result.getKind());
                assertEquals(phase, result.getPhase());
                assertEquals(temp, result.getTempPath());
                assertEquals(phase == MovePhase.DELETE_SOURCE, result.isCommitConfirmed());
                assertTrue(result.isFast());
            }
        }
    }

    @Test
    public void definiteMoveKeepsKindEvenOnAChangingCall() {
        for (final MovePhase phase : MovePhase.values()) {
            for (final Fault fault : Fault.values()) {
                final String temp = "out/attempt.part";
                final Throwable move = new MoveFailedException("from", "to", new SocketException(), fault,
                        phase, temp, false, false, true);
                final Classification result = classifier.classify(move, 5000, CallType.CHANGE);
                assertEquals(ErrorKind.valueOf(fault.name()), result.getKind());
                assertEquals(phase, result.getPhase());
                assertEquals(temp, result.getTempPath());
                assertEquals(phase == MovePhase.DELETE_SOURCE, result.isCommitConfirmed());
                assertFalse(result.isFast());
                assertSame(move, result.getCause());
            }
        }
    }

    @Test
    public void unknownClassWarnsOnceWithItsMaskedCauseChain() throws Exception {
        final RepeatLimiter limiter = new RepeatLimiter();
        final NamedLog log = NamedLog.connection(sink, "test", limiter);
        final ErrorClassifier full = new ErrorClassifier(log);
        for (int number = 0; number < 200; number++) {
            log.warn("failure " + number);
        }
        assertEquals(200, sink.lines.size());
        sink.lines.clear();
        log.warn("ordinary overflow");
        final String uri = "ftps://user:Synthetic/Folder@host/out?pass=SyntheticOption";
        final Throwable unknown = (Throwable) Class.forName("java.lang.reflect.InaccessibleObjectException")
                .getConstructor(String.class).newInstance("cannot access " + uri);
        final Throwable wrapped = new FileNotFolderException(uri, unknown);
        assertNull(Faults.match(wrapped));
        assertEquals(Fault.APPLICATION, Faults.of(wrapped));
        assertEquals(ErrorKind.APPLICATION, full.classify(wrapped, 1, CallType.READ).getKind());
        assertEquals(ErrorKind.APPLICATION, full.classify(new FileNotFolderException("different", unknown),
                2, CallType.CHANGE).getKind());
        assertEquals(1, sink.lines.size());
        final String line = sink.lines.get(0);
        assertTrue(line.startsWith("WARN [connection:test] "));
        assertTrue(line.contains(FileNotFolderException.class.getName()));
        assertTrue(line.contains(unknown.getClass().getName()));
        assertTrue(line.contains("cannot access ftps://user:***@host/out?pass=***"));
        assertFalse(line.contains("Synthetic"));
    }

    @Test
    public void knownApplicationNeverWarnsAndStatusIsSafe() {
        final Throwable denied = new AccessDeniedException("smb2://u:SyntheticDenied@host/x");
        assertEquals(Fault.APPLICATION, Faults.match(denied));
        final Classification result = classifier.classify(denied, 0, CallType.READ);
        assertEquals(ErrorKind.APPLICATION, result.getKind());
        assertEquals("smb2://u:***@host/x", result.getStatusText());
        assertTrue(sink.lines.isEmpty());
    }

    @Test
    public void wrappedAndDirectServerStatusIsRetainedAndMasked() {
        final SftpException reply = new SftpException(3, "Refused sftp://user:SyntheticReply@host/in");
        final FileSystemException look = new FileSystemException("vfs.provider/look.error", reply, "sftp://host/in");
        look.setFault(Fault.APPLICATION);
        for (final Throwable error : new Throwable[] {look, reply}) {
            final Classification result = classifier.classify(error, 1, CallType.READ);
            assertEquals(ErrorKind.APPLICATION, result.getKind());
            assertSame(error, result.getCause());
            assertTrue(result.getStatusText().contains("Refused sftp://user:***@host/in"));
            assertFalse(result.getStatusText().contains("SyntheticReply"));
        }
        assertTrue(sink.lines.isEmpty());
    }

    @Test
    public void kindAndTimeDriveCallerDecisions() {
        final Throwable socket = new SocketException();
        assertEquals(ErrorKind.TRANSPORT, classifier.classify(socket, 4999, CallType.READ).getKind());
        assertEquals(ErrorKind.UNCLEAR, classifier.classify(socket, 5000, CallType.CHANGE).getKind());
        assertEquals(ErrorKind.MISSING, classifier.classify(new NoSuchFileException("absent"), 0, CallType.CHANGE).getKind());
        final FileSystemException busy = new FileSystemException("busy");
        busy.setFault(Fault.BUSY);
        assertEquals(ErrorKind.BUSY, classifier.classify(busy, 0, CallType.READ).getKind());
        assertFalse(ErrorKind.LOCK_BUSY.isTransport());
        assertTrue(ErrorKind.UNCLEAR.isTransport());
        assertTrue(ErrorKind.TRANSPORT.isTransport());
        assertTrue(sink.lines.isEmpty());
    }
}
