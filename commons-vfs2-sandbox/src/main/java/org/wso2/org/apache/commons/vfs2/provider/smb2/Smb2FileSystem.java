/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import com.hierynomus.smbj.SMBClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.org.apache.commons.vfs2.Capability;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileName;
import org.wso2.org.apache.commons.vfs2.provider.AbstractFileSystem;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Smb2FileSystem extends AbstractFileSystem {

    // making the client thread safe
    private final AtomicReference<SMBClient> client = new AtomicReference<>();

    // Every client of this file system, the idle one and the ones that a call or an open stream holds. A client is in
    // this set from the end of its connect until it is closed or discarded, so an abort always finds it.
    private final Set<Smb2ClientWrapper> clients = ConcurrentHashMap.newKeySet();

    private volatile boolean isClosed = false;
    private static final Log LOG = LogFactory.getLog(Smb2FileSystem.class);

    private final Smb2WrapperClientFactory clientWrapperFactory;

    // How long a folder pair stays "no link" after a refused link.
    static final long NO_LINK_NANOS = TimeUnit.MINUTES.toNanos(10);

    // The value LINKS of a folder pair. Any other value is the end of a "no link" mark on the monotonic clock.
    private static final Long LINKS = Long.MIN_VALUE;

    // The link state per folder pair, the source folder and the target folder. A pair that is not in the map is not
    // known. A pair that linked once keeps LINKS.
    private final ConcurrentMap<String, Long> linkMarks = new ConcurrentHashMap<>();

    // The folder pairs that got their one WARN.
    private final Set<String> warnedPairs = ConcurrentHashMap.newKeySet();

    protected Smb2FileSystem(FileName rootName, FileSystemOptions fileSystemOptions, Smb2WrapperClientFactory wrapperClientFactory) throws FileSystemException {

        super(rootName, null, fileSystemOptions);
        this.clientWrapperFactory = wrapperClientFactory;
        final SMBClient first = this.clientWrapperFactory.create();
        clients.add((Smb2ClientWrapper) first);
        client.set(first);
    }

    @Override
    public void doCloseCommunicationLink() {
        isClosed = true;
        SMBClient smb2FSClient = client.getAndSet(null);
        // Client could be null if the file system has already been closed.
        // Some file objects may be having references to this already closed FS, if they have not been re-resolved after FS closure.
        // Those file objects will attempt to close this again, causing NPE's.
        if (smb2FSClient != null) {
            smb2FSClient.close();
            clients.remove(smb2FSClient);
        } else {
            LOG.debug("Attempting to close already closed client.");
        }
    }

    @Override
    protected FileObject createFile(AbstractFileName name) {

        return new Smb2FileObject(name, this, getRootName());
    }

    @Override
    protected void addCapabilities(Collection<Capability> caps) {

        caps.addAll(Smb2FileProvider.capabilities);
    }

    /**
     * Returns the SMB client thread safe. An idle client that is not alive by local state is aborted and closed, and
     * a new client is made in its place.
     *
     * @return SMBClient
     */
    public synchronized SMBClient getClient() throws FileSystemException {
        SMBClient existingClient = client.getAndSet(null);
        if (existingClient != null) {
            final Smb2ClientWrapper idle = (Smb2ClientWrapper) existingClient;
            final boolean alive = idle.isAliveLocally();
            if (TestPoints.enabled()) {
                TestPoints.event("smb2.getclient", null, "alive=" + alive, "local-port=" + idle.getLocalPorts(),
                        "action=" + (alive ? "handed-out" : "replaced"));
            }
            if (alive) {
                return existingClient;
            }
            closeAborted(idle);
        } else if (TestPoints.enabled()) {
            TestPoints.event("smb2.getclient", null, "alive=none", "local-port=", "action=created");
        }
        existingClient = this.clientWrapperFactory.create();
        clients.add((Smb2ClientWrapper) existingClient);
        return existingClient;
    }

    /**
     * Set the smbclient back to atomic reference. A client that is not alive by local state is aborted and closed,
     * and never kept.
     *
     * @param smbClient
     */
    public void putClient(SMBClient smbClient) {
        final Smb2ClientWrapper wrapper = (Smb2ClientWrapper) smbClient;
        if (!wrapper.isAliveLocally()) {
            closeAborted(wrapper);
        } else if (isClosed || !client.compareAndSet(null, smbClient)) {
            // The file system is closed, or an idle client is already present, so close the connection.
            wrapper.close();
            clients.remove(wrapper);
        } else if (isClosed && client.compareAndSet(smbClient, null)) {
            // The close of the file system came after the check above and found the slot empty. The client is taken
            // back here, so a closed file system keeps no idle client.
            wrapper.close();
            clients.remove(wrapper);
        }
    }

    /**
     * Takes back a client whose call ended with a transport error, and never hands it out again. Its sockets are
     * closed first, so the close of the client waits on nothing.
     *
     * @param smbClient the client.
     */
    public void discardClient(SMBClient smbClient) {
        discardClient(smbClient, null);
    }

    void discardClient(final SMBClient smbClient, final Throwable cause) {
        final Smb2ClientWrapper wrapper = (Smb2ClientWrapper) smbClient;
        if (TestPoints.enabled()) {
            TestPoints.event("smb2.discard", null, "local-port=" + wrapper.getLocalPorts(),
                    "cause=" + (cause == null ? "none" : String.valueOf(cause)));
        }
        wrapper.markBroken();
        closeAborted(wrapper);
    }

    /**
     * Gives a client back after a call. A call that threw a transport error discards the client. Every other end of
     * the call puts it back.
     *
     * @param smbClient the client.
     * @param failure the exception of the call, or null when the call returned.
     */
    void giveBack(final SMBClient smbClient, final Throwable failure) {
        if (failure != null && isTransport((Smb2ClientWrapper) smbClient, failure)) {
            discardClient(smbClient, failure);
        } else {
            putClient(smbClient);
        }
    }

    // The client decides the two rows of the SMB2 table that need its state. An interrupt is a transport error too.
    static boolean isTransport(final Smb2ClientWrapper wrapper, final Throwable failure) {
        return wrapper.faultOf(failure) == Fault.TRANSPORT || Faults.isTransport(failure);
    }

    private void closeAborted(final Smb2ClientWrapper wrapper) {
        wrapper.abort();
        wrapper.close();
        clients.remove(wrapper);
    }

    /**
     * Closes the sockets of the idle client and of every client that is out. It sends no request and takes no
     * monitor, so any thread may call it.
     */
    boolean isLinked(final String pair) {
        return LINKS.equals(linkMarks.get(pair));
    }

    // True while the "no link" mark of the pair holds at the given time.
    boolean isMarkedNoLink(final String pair, final long nowNanos) {
        final Long end = linkMarks.get(pair);
        return end != null && !LINKS.equals(end) && end - nowNanos > 0;
    }

    void markLinks(final String pair) {
        linkMarks.put(pair, LINKS);
    }

    // Marks the pair "no link" for 10 minutes from the given time. A pair that links keeps that state. True only for
    // the first mark of the pair, which logs the one WARN.
    boolean markNoLink(final String pair, final long nowNanos) {
        linkMarks.compute(pair, (key, old) -> LINKS.equals(old) ? old : nowNanos + NO_LINK_NANOS);
        return !isLinked(pair) && warnedPairs.add(pair);
    }

    @Override
    public void abort() {
        for (final Smb2ClientWrapper wrapper : clients) {
            wrapper.abort();
        }
    }

    /**
     * Tells from local state whether the idle client and every client that is out look alive.
     *
     * @return false when one of them is known to be dead.
     */
    @Override
    public boolean isAliveLocally() {
        for (final Smb2ClientWrapper wrapper : clients) {
            if (!wrapper.isAliveLocally()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getHandedOutCount() {
        final int all = clients.size();
        return client.get() != null ? Math.max(0, all - 1) : all;
    }

    @Override
    public int getOpenClientCount() {
        return clients.size();
    }
}
