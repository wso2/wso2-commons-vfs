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
package org.wso2.org.apache.commons.vfs2.provider.ftps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

/**
 * A thin wrapper of the plain data socket of FTPS.
 * <p>
 * It answers {@link #getPort()} and {@link #getInetAddress()} with the port and the address of the control
 * connection and passes every other call to the plain socket. The JDK looks up a session to resume by the peer host
 * and the peer port, and a TLS socket on top of another socket takes the peer port from the socket under it. So a TLS
 * data socket on this wrapper finds the session of the control connection. The TLS records still pass through the
 * plain socket, and a close of the plain socket ends a blocked write.
 * </p>
 */
public class ControlPeerSocket extends Socket {

    private final Socket plain;

    private final InetAddress controlAddress;

    private final int controlPort;

    /**
     * Constructs a wrapper.
     *
     * @param plain the connected plain data socket.
     * @param controlAddress the address of the control connection.
     * @param controlPort the port of the control connection.
     */
    public ControlPeerSocket(final Socket plain, final InetAddress controlAddress, final int controlPort) {
        this.plain = plain;
        this.controlAddress = controlAddress;
        this.controlPort = controlPort;
    }

    /**
     * Gets the plain data socket under this wrapper.
     *
     * @return the plain socket.
     */
    public Socket getPlainSocket() {
        return plain;
    }

    @Override
    public InetAddress getInetAddress() {
        return controlAddress;
    }

    @Override
    public int getPort() {
        return controlPort;
    }

    @Override
    public void connect(final SocketAddress endpoint) throws IOException {
        plain.connect(endpoint);
    }

    @Override
    public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
        plain.connect(endpoint, timeout);
    }

    @Override
    public void bind(final SocketAddress bindpoint) throws IOException {
        plain.bind(bindpoint);
    }

    @Override
    public InetAddress getLocalAddress() {
        return plain.getLocalAddress();
    }

    @Override
    public int getLocalPort() {
        return plain.getLocalPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return plain.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return plain.getLocalSocketAddress();
    }

    @Override
    public SocketChannel getChannel() {
        return plain.getChannel();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return plain.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return plain.getOutputStream();
    }

    @Override
    public void setTcpNoDelay(final boolean on) throws SocketException {
        plain.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return plain.getTcpNoDelay();
    }

    @Override
    public void setSoLinger(final boolean on, final int linger) throws SocketException {
        plain.setSoLinger(on, linger);
    }

    @Override
    public int getSoLinger() throws SocketException {
        return plain.getSoLinger();
    }

    @Override
    public void sendUrgentData(final int data) throws IOException {
        plain.sendUrgentData(data);
    }

    @Override
    public void setOOBInline(final boolean on) throws SocketException {
        plain.setOOBInline(on);
    }

    @Override
    public boolean getOOBInline() throws SocketException {
        return plain.getOOBInline();
    }

    @Override
    public void setSoTimeout(final int timeout) throws SocketException {
        plain.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return plain.getSoTimeout();
    }

    @Override
    public void setSendBufferSize(final int size) throws SocketException {
        plain.setSendBufferSize(size);
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return plain.getSendBufferSize();
    }

    @Override
    public void setReceiveBufferSize(final int size) throws SocketException {
        plain.setReceiveBufferSize(size);
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return plain.getReceiveBufferSize();
    }

    @Override
    public void setKeepAlive(final boolean on) throws SocketException {
        plain.setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return plain.getKeepAlive();
    }

    @Override
    public void setTrafficClass(final int tc) throws SocketException {
        plain.setTrafficClass(tc);
    }

    @Override
    public int getTrafficClass() throws SocketException {
        return plain.getTrafficClass();
    }

    @Override
    public void setReuseAddress(final boolean on) throws SocketException {
        plain.setReuseAddress(on);
    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        return plain.getReuseAddress();
    }

    @Override
    public void close() throws IOException {
        plain.close();
    }

    @Override
    public void shutdownInput() throws IOException {
        plain.shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        plain.shutdownOutput();
    }

    @Override
    public boolean isConnected() {
        return plain.isConnected();
    }

    @Override
    public boolean isBound() {
        return plain.isBound();
    }

    @Override
    public boolean isClosed() {
        return plain.isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        return plain.isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        return plain.isOutputShutdown();
    }

    @Override
    public void setPerformancePreferences(final int connectionTime, final int latency, final int bandwidth) {
        plain.setPerformancePreferences(connectionTime, latency, bandwidth);
    }

    @Override
    public String toString() {
        return "ControlPeerSocket[control=" + controlAddress + ":" + controlPort + ", data=" + plain + "]";
    }
}
