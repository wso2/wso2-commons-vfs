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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Field;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileAllInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import org.mockito.MockSettings;
import org.mockito.Mockito;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * Stand ins for the parts of smbj that a unit test can not reach without a server. A client here runs the real code
 * of {@link Smb2ClientWrapper} on a share and a connection that the test controls. Its close only counts.
 */
final class Smb2Doubles {

    static final String ROOT = "smb2://smbuser:smbpass@127.0.0.1:4450/data/";

    // The access of the metadata open of the wrapper, the same as DiskShare.getFileInformation opens with.
    static final Set<AccessMask> METADATA = EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA);

    // Matches the access of every open that is not the metadata open.
    static Set<AccessMask> notMetadata() {
        return argThat(access -> !METADATA.equals(access));
    }

    private Smb2Doubles() {
    }

    /**
     * A client with the real code of the wrapper, made with no connect.
     */
    static final class Client {

        final Smb2ClientWrapper wrapper;

        final DiskShare share;

        final Connection connection;

        // The handle of the metadata query of the wrapper. With no other stub its query answers null, which the
        // wrapper reads as a missing file.
        final DiskEntry metadata;

        final SocketGroup group;

        // The socket of the connection. A test closes it as smbj does when it disconnects a dead connection.
        final Socket socket = new Socket();

        final AtomicInteger closes = new AtomicInteger();

        Client(final FileSystemOptions options, final List<String> order) throws Exception {
            this(options, order, false);
        }

        private Client(final FileSystemOptions options, final List<String> order, final boolean stubOnly)
                throws Exception {
            share = mock(DiskShare.class, settings(stubOnly));
            connection = mock(Connection.class, settings(stubOnly));
            metadata = mock(DiskEntry.class, settings(stubOnly));
            doReturn(metadata).when(share).open(anyString(), eq(METADATA), isNull(), eq(SMB2ShareAccess.ALL),
                    eq(SMB2CreateDisposition.FILE_OPEN), isNull());
            group = new SocketGroup();
            when(share.isConnected()).thenReturn(true);
            when(connection.isConnected()).thenReturn(true);
            wrapper = mock(Smb2ClientWrapper.class, settings(stubOnly).defaultAnswer(CALLS_REAL_METHODS));
            set(wrapper, "diskShare", share);
            set(wrapper, "connection", connection);
            set(wrapper, "socketGroup", group);
            group.add(socket);
            set(wrapper, "connectionSocket", socket);
            set(wrapper, "fileSystemOptions", options == null ? new FileSystemOptions() : options);
            set(wrapper, "localPorts", "4711");
            doAnswer(call -> {
                closes.incrementAndGet();
                if (order != null) {
                    order.add("client-close aborted=" + group.isAborted());
                }
                return null;
            }).when(wrapper).close();
        }

        Client() throws Exception {
            this(null, null);
        }

        /**
         * A client whose doubles keep no record of their calls, for a test that calls them in a tight loop. Mockito
         * keeps every call of a double that records, with its stack frame, and that fills a small heap. A test can
         * not verify the calls of such a client.
         */
        static Client stubOnly() throws Exception {
            return new Client(null, null, true);
        }

        // The metadata query of the wrapper answers with this information.
        void info(final FileAllInformation info) {
            doReturn(info).when(metadata).getFileInformation(FileAllInformation.class);
        }

        // The metadata open fails, as it does for a missing file and for a refusal of the open.
        void metadataOpenFails(final RuntimeException error) {
            doThrow(error).when(share).open(anyString(), eq(METADATA), isNull(), eq(SMB2ShareAccess.ALL),
                    eq(SMB2CreateDisposition.FILE_OPEN), isNull());
        }

        /**
         * Lets the doubles of this client go. Mockito keeps an inline double for as long as an answer of it holds the
         * double, and the answers here hold this client, so a test that makes many clients lets each one go.
         */
        void release() {
            Mockito.framework().clearInlineMock(wrapper);
            Mockito.framework().clearInlineMock(share);
            Mockito.framework().clearInlineMock(connection);
            Mockito.framework().clearInlineMock(metadata);
        }

        private static MockSettings settings(final boolean stubOnly) {
            return stubOnly ? withSettings().stubOnly() : withSettings();
        }
    }

    /**
     * A factory that hands out the clients of a queue, one per call.
     */
    static class Factory extends Smb2WrapperClientFactory {

        final Deque<Smb2ClientWrapper> next = new ArrayDeque<>();

        final AtomicInteger creates = new AtomicInteger();

        Factory(final Smb2ClientWrapper... clients) {
            super(null, null, null);
            for (final Smb2ClientWrapper client : clients) {
                next.add(client);
            }
        }

        @Override
        public SMBClient create() throws FileSystemException {
            creates.incrementAndGet();
            final Smb2ClientWrapper client = next.poll();
            if (client == null) {
                throw new FileSystemException("No client is left in the test factory.");
            }
            return client;
        }
    }

    static FileName rootName() throws FileSystemException {
        return Smb2FileNameParser.getInstance().parseUri(null, null, ROOT);
    }

    static AbstractFileName name(final String path) throws FileSystemException {
        return (AbstractFileName) Smb2FileNameParser.getInstance().parseUri(null, null, ROOT + path);
    }

    static Smb2FileSystem fileSystem(final Factory factory) throws FileSystemException {
        return new Smb2FileSystem(rootName(), new FileSystemOptions(), factory);
    }

    static void set(final Object target, final String field, final Object value) throws Exception {
        final Field declared = Smb2ClientWrapper.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }
}
