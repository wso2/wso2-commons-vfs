/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.1
 *
 */

package org.apache.commons.vfs2.provider.ftps;

import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.vfs2.SSLConnectionException;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Locale;

public class FTPSClientWithSessionResumption extends FTPSClient {

    public FTPSClientWithSessionResumption() {
        super();
    }

    public FTPSClientWithSessionResumption(boolean isImplicit) {
        super(isImplicit);
    }

    @Override
    protected void _connectAction_() throws SSLConnectionException {
        try {
            super._connectAction_();
            execPBSZ(0);
            execPROT("P");
        } catch (IOException e) {
            throw new SSLConnectionException("Error occurred while connecting to the server");
        }
    }

    @Override
    protected void _prepareDataSocket_(Socket socket) throws SSLConnectionException {
        if (socket instanceof SSLSocket) {
            // Control socket is SSL
            final SSLSession session = ((SSLSocket) _socket_).getSession();
            if (session.isValid()) {
                final SSLSessionContext context = session.getSessionContext();
                try {
                    final Field sessionHostPortCache = context.getClass().getDeclaredField("sessionHostPortCache");
                    sessionHostPortCache.setAccessible(true);
                    final Object cache = sessionHostPortCache.get(context);
                    final Method putMethod = cache.getClass().getDeclaredMethod("put", Object.class, Object.class);
                    putMethod.setAccessible(true);
                    Method getHostMethod;
                    try {
                        getHostMethod = socket.getClass().getMethod("getPeerHost");
                    } catch (NoSuchMethodException e) {
                        // Running in IKVM
                        getHostMethod = socket.getClass().getDeclaredMethod("getHost");
                    }
                    getHostMethod.setAccessible(true);
                    Object peerHost = getHostMethod.invoke(socket);
                    InetAddress iAddr = socket.getInetAddress();
                    int port = socket.getPort();
                    putMethod.invoke(cache, String.format("%s:%s", peerHost, port).toLowerCase(Locale.ROOT), session);
                    putMethod.invoke(cache, String.format("%s:%s", iAddr.getHostName(), port).toLowerCase(Locale.ROOT),
                                     session);
                    putMethod.invoke(cache,
                                     String.format("%s:%s", iAddr.getHostAddress(), port).toLowerCase(Locale.ROOT),
                                     session);
                } catch (NoSuchFieldException | IllegalAccessException | InvocationTargetException |
                         NoSuchMethodException e) {
                    throw new SSLConnectionException("Error occurred while initializing the SSL Socket");
                }
            } else {
                throw new SSLConnectionException("Invalid SSL Session");
            }
        }
    }
}
