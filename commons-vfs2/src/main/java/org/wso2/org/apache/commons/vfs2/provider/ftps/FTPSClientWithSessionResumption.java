/*
 *  Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.org.apache.commons.vfs2.provider.ftps;

import java.io.IOException;
import java.net.Socket;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.apache.commons.net.ftp.FTPSClient;
import org.wso2.org.apache.commons.vfs2.SSLConnectionException;
import org.wso2.org.apache.commons.vfs2.provider.ftp.ControlSocketView;

/**
 * An FTPS client whose data connections resume the TLS session of the control connection.
 * <p>
 * With the watched factories of the fork the data socket is a plain socket. This client puts the TLS socket on top
 * of a {@link ControlPeerSocket}, which gives the address and the port of the control connection. The JDK then offers
 * the session that it cached for the control connection. No reflection is used. The watchdog closes the plain socket.
 * </p>
 */
public class FTPSClientWithSessionResumption extends FTPSClient implements ControlSocketView {

    private static final String PRIVATE = "P";

    private static final String CLEAR = "C";

    private final SSLContext context;

    private SocketFactory watchedSocketFactory;

    private ServerSocketFactory watchedServerSocketFactory;

    // The protection level that the server accepted last. Before any PROT the data channel is clear.
    private String protection = CLEAR;

    public FTPSClientWithSessionResumption() {
        this(false, null);
    }

    public FTPSClientWithSessionResumption(boolean isImplicit) {
        this(isImplicit, null);
    }

    /**
     * Constructs a client that uses one TLS context for the control connection and the data connections.
     *
     * @param isImplicit true for implicit FTPS.
     * @param context the TLS context.
     */
    public FTPSClientWithSessionResumption(final boolean isImplicit, final SSLContext context) {
        super(isImplicit, context);
        this.context = context;
    }

    /**
     * Sets the plain watched socket factories. The client keeps them, also after a PROT command replaced the
     * factories. A client with these factories needs its own TLS context.
     *
     * @param socketFactory the factory of the control socket and the passive data sockets.
     * @param serverSocketFactory the factory of the server socket of an active data connection.
     */
    void setWatchedFactories(final SocketFactory socketFactory, final ServerSocketFactory serverSocketFactory) {
        this.watchedSocketFactory = socketFactory;
        this.watchedServerSocketFactory = serverSocketFactory;
        setSocketFactory(socketFactory);
        setServerSocketFactory(serverSocketFactory);
    }

    /**
     * Gets the protection level that the server accepted last.
     *
     * @return the level.
     */
    String getProtection() {
        return protection;
    }

    @Override
    protected void _connectAction_() throws SSLConnectionException {
        try {
            super._connectAction_();
            execPBSZ(0);
            execPROT(PRIVATE);
        } catch (IOException e) {
            throw new SSLConnectionException("Error occurred while connecting to the server", e);
        }
    }

    @Override
    public void execPROT(final String prot) throws SSLException, IOException {
        // A refused level throws here and changes nothing.
        super.execPROT(prot);
        protection = prot == null ? CLEAR : prot;
        // The parent put its own factories in. The data sockets stay plain watched sockets.
        if (watchedSocketFactory != null) {
            setSocketFactory(watchedSocketFactory);
            setServerSocketFactory(watchedServerSocketFactory);
        }
    }

    @Override
    protected Socket _openDataConnection_(final String command, final String arg) throws IOException {
        final Socket socket = super._openDataConnection_(command, arg);
        // No data connection: the caller reads the reply. A socket of the parent has its TLS already. Any level but
        // P keeps the plain socket.
        if (socket == null || socket instanceof SSLSocket || !PRIVATE.equals(protection)) {
            return socket;
        }
        return secure(socket);
    }

    private Socket secure(final Socket plain) throws IOException {
        final SSLSocket control = (SSLSocket) _socket_;
        final ControlPeerSocket peer = new ControlPeerSocket(plain, control.getInetAddress(), control.getPort());
        try {
            final SSLSocket data = (SSLSocket) context.getSocketFactory().createSocket(peer,
                    control.getSession().getPeerHost(), control.getPort(), true);
            data.setUseClientMode(control.getUseClientMode());
            data.setEnableSessionCreation(control.getEnableSessionCreation());
            data.setEnabledCipherSuites(control.getEnabledCipherSuites());
            data.setEnabledProtocols(control.getEnabledProtocols());
            data.startHandshake();
            return data;
        } catch (final IOException | RuntimeException e) {
            try {
                plain.close();
            } catch (final IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /**
     * Gets the control socket. After the TLS negotiation it is the TLS socket.
     *
     * @return the socket, or null when the client is not connected.
     */
    @Override
    public Socket controlSocket() {
        return _socket_;
    }
}
