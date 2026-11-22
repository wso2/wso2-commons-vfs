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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;

/**
 * The handout of SMB2 clients: a dead client never sits in the idle slot, a client with a transport error is discarded,
 * and the abort and the counts read the set of clients.
 */
public class Smb2FileSystemClientsTest {

    @Test
    public void aliveIdleClientIsHandedOutAndPutBack() throws Exception {
        final Client first = new Client();
        final Factory factory = new Factory(first.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        assertEquals(1, fileSystem.getOpenClientCount());
        assertEquals(0, fileSystem.getHandedOutCount());

        assertSame(first.wrapper, fileSystem.getClient());
        assertEquals(1, factory.creates.get());
        assertEquals(1, fileSystem.getHandedOutCount());
        assertEquals(1, fileSystem.getOpenClientCount());

        fileSystem.putClient(first.wrapper);
        assertEquals(0, fileSystem.getHandedOutCount());
        assertSame(first.wrapper, fileSystem.getClient());
        assertEquals(0, first.closes.get());
        assertFalse(first.group.isAborted());
    }

    @Test
    public void deadIdleClientIsAbortedClosedAndReplacedBeforeAnyRequest() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client dead = new Client(null, order);
        final Client fresh = new Client();
        final Factory factory = new Factory(dead.wrapper, fresh.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        dead.socket.close();

        assertSame(fresh.wrapper, fileSystem.getClient());
        assertEquals(Arrays.asList("client-close aborted=true"), order);
        assertEquals(1, fileSystem.getOpenClientCount());
        assertEquals(1, fileSystem.getHandedOutCount());
    }

    @Test
    public void deadClientIsNeverPutBackInTheIdleSlot() throws Exception {
        final Client first = new Client();
        final Client second = new Client();
        final Factory factory = new Factory(first.wrapper, second.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        when(first.share.isConnected()).thenReturn(false);
        fileSystem.putClient(first.wrapper);
        assertTrue(first.group.isAborted());
        assertEquals(1, first.closes.get());
        assertEquals(0, fileSystem.getOpenClientCount());
        assertSame(second.wrapper, fileSystem.getClient());
    }

    @Test
    public void surplusAliveClientIsClosedAsToday() throws Exception {
        final Client first = new Client();
        final Client second = new Client();
        final Factory factory = new Factory(first.wrapper, second.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        fileSystem.getClient();
        fileSystem.putClient(first.wrapper);
        fileSystem.putClient(second.wrapper);
        assertEquals(1, second.closes.get());
        assertFalse(second.group.isAborted());
        assertEquals(1, fileSystem.getOpenClientCount());
    }

    @Test
    public void discardMarksTheClientBrokenAbortsFirstAndNeverKeepsIt() throws Exception {
        final List<String> order = new ArrayList<>();
        final Client first = new Client(null, order);
        final Client second = new Client();
        final Factory factory = new Factory(first.wrapper, second.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        fileSystem.discardClient(first.wrapper);
        assertEquals(Arrays.asList("client-close aborted=true"), order);
        assertFalse(first.wrapper.isAliveLocally());
        assertEquals(0, fileSystem.getOpenClientCount());
        assertSame(second.wrapper, fileSystem.getClient());
        assertEquals(2, factory.creates.get());
    }

    @Test
    public void giveBackDiscardsAfterATransportErrorAndPutsBackAfterAnyOther() throws Exception {
        final Client first = new Client();
        final Client second = new Client();
        final Factory factory = new Factory(first.wrapper, second.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);

        fileSystem.getClient();
        fileSystem.giveBack(first.wrapper, new SMBApiException(NtStatus.STATUS_ACCESS_DENIED.getValue(),
                SMB2MessageCommandCode.SMB2_CREATE, "denied", null));
        assertFalse(first.group.isAborted());
        assertSame(first.wrapper, fileSystem.getClient());

        fileSystem.giveBack(first.wrapper, new SMBRuntimeException(new TransportException("timeout")));
        assertTrue(first.group.isAborted());
        assertEquals(1, first.closes.get());
        assertSame(second.wrapper, fileSystem.getClient());

        // An interrupt is a transport error too.
        fileSystem.giveBack(second.wrapper, new SMBRuntimeException(new InterruptedException()));
        assertTrue(second.group.isAborted());
        assertEquals(0, fileSystem.getOpenClientCount());
    }

    @Test
    public void closedShareAtACallSiteDiscardsTheClient() throws Exception {
        final Client first = new Client();
        final Factory factory = new Factory(first.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        when(first.share.isConnected()).thenReturn(false);
        fileSystem.giveBack(first.wrapper, new SMBRuntimeException("DiskShare has already been closed"));
        assertTrue(first.group.isAborted());
        assertFalse(first.wrapper.isAliveLocally());
    }

    @Test
    public void abortReachesTheIdleClientAndEveryClientThatIsOutWhileTheMonitorIsHeld() throws Exception {
        final Client idle = new Client();
        final Client out = new Client();
        final Factory factory = new Factory(out.wrapper, idle.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        fileSystem.getClient();
        fileSystem.putClient(idle.wrapper);
        final Socket idleSocket = new Socket();
        final Socket outSocket = new Socket();
        idle.group.add(idleSocket);
        out.group.add(outSocket);

        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> {
            synchronized (fileSystem) {
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS));
            final long start = System.nanoTime();
            fileSystem.abort();
            fileSystem.isAliveLocally();
            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 1000, "abort took " + millis + " ms");
        } finally {
            release.countDown();
            holder.join(10000);
        }
        assertTrue(idleSocket.isClosed());
        assertTrue(outSocket.isClosed());
        assertTrue(idle.group.isAborted());
        assertTrue(out.group.isAborted());
    }

    @Test
    public void localCheckOfTheFileSystemIsFalseWhenOneClientIsDead() throws Exception {
        final Client idle = new Client();
        final Client out = new Client();
        final Factory factory = new Factory(out.wrapper, idle.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        fileSystem.getClient();
        fileSystem.putClient(idle.wrapper);
        assertTrue(fileSystem.isAliveLocally());
        out.socket.close();
        assertFalse(fileSystem.isAliveLocally());
    }

    @Test
    public void clientThatComesBackAfterTheCloseOfTheFileSystemIsClosed() throws Exception {
        final Client first = new Client();
        final Factory factory = new Factory(first.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);
        fileSystem.getClient();
        fileSystem.doCloseCommunicationLink();
        fileSystem.putClient(first.wrapper);
        assertEquals(1, first.closes.get());
        assertEquals(0, fileSystem.getOpenClientCount());
    }
}
