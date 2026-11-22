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
package org.wso2.org.apache.commons.vfs2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Checks that every smbj, JSch and commons net call of the fork exists in the library jars on the class path. The
 * profile pack-libraries puts the jars of a WSO2 Integrator: MI pack there. So a call that the pack does not have
 * fails this build and not a customer.
 */
public class LibraryCallsTest {

    private static final String CONSTRUCTOR = "<init>";

    private static final String SET = "java.util.Set";

    private static final String TIME_UNIT = "java.util.concurrent.TimeUnit";

    static final List<Call> CALLS = Arrays.asList(
            // JSch
            new Call("com.jcraft.jsch.Session", "setTimeout", "int"),
            new Call("com.jcraft.jsch.Session", "setServerAliveCountMax", "int"),
            new Call("com.jcraft.jsch.Session", "connect", "int"),
            new Call("com.jcraft.jsch.Session", "setSocketFactory", "com.jcraft.jsch.SocketFactory"),
            new Call("com.jcraft.jsch.Session", "getTimeout"),
            new Call("com.jcraft.jsch.Session", "getServerAliveCountMax"),
            new Call("com.jcraft.jsch.Session", "disconnect"),
            new Call("com.jcraft.jsch.Session", "isConnected"),
            new Call("com.jcraft.jsch.Session", "setConfig", "java.util.Properties"),
            new Call("com.jcraft.jsch.Channel", "disconnect"),
            new Call("com.jcraft.jsch.Channel", "isConnected"),
            new Call("com.jcraft.jsch.Channel", "isClosed"),
            new Call("com.jcraft.jsch.ChannelSftp", "hardlink", "java.lang.String", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "rename", "java.lang.String", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "lstat", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "put", "java.io.InputStream", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "put", "java.lang.String", "int"),
            new Call("com.jcraft.jsch.ChannelSftp", "rm", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "rmdir", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "get", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "get", "java.lang.String", "com.jcraft.jsch.SftpProgressMonitor",
                    "long"),
            new Call("com.jcraft.jsch.ChannelSftp", "ls", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "cd", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "setStat", "java.lang.String", "com.jcraft.jsch.SftpATTRS"),
            new Call("com.jcraft.jsch.ChannelSftp", "stat", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "mkdir", "java.lang.String"),
            new Call("com.jcraft.jsch.ChannelSftp", "getExtension", "java.lang.String"),
            new Call("com.jcraft.jsch.SftpATTRS", "isDir"),
            new Call("com.jcraft.jsch.SocketFactory", "createSocket", "java.lang.String", "int"),
            new Call("com.jcraft.jsch.SocketFactory", "getInputStream", "java.net.Socket"),
            new Call("com.jcraft.jsch.SocketFactory", "getOutputStream", "java.net.Socket"),
            // commons net
            new Call("org.apache.commons.net.SocketClient", "setConnectTimeout", "int"),
            new Call("org.apache.commons.net.SocketClient", "setDefaultTimeout", "int"),
            new Call("org.apache.commons.net.SocketClient", "setSoTimeout", "int"),
            new Call("org.apache.commons.net.SocketClient", "setSocketFactory", "javax.net.SocketFactory"),
            new Call("org.apache.commons.net.SocketClient", "setServerSocketFactory", "javax.net.ServerSocketFactory"),
            new Call("org.apache.commons.net.ftp.FTP", "rnfr", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTP", "rnto", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTP", "getReplyCode"),
            new Call("org.apache.commons.net.ftp.FTPClient", "getConnectTimeout"),
            new Call("org.apache.commons.net.ftp.FTPClient", "isConnected"),
            new Call("org.apache.commons.net.ftp.FTPClient", "disconnect"),
            new Call("org.apache.commons.net.ftp.FTPClient", "setDataTimeout", "java.time.Duration"),
            new Call("org.apache.commons.net.ftp.FTPClient", "hasFeature", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "getModificationTime", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "getSize", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "listFiles", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "listFiles"),
            new Call("org.apache.commons.net.ftp.FTPClient", "setListHiddenFiles", "boolean"),
            new Call("org.apache.commons.net.ftp.FTPClient", "changeWorkingDirectory", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "printWorkingDirectory"),
            new Call("org.apache.commons.net.ftp.FTPClient", "rename", "java.lang.String", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "mdtmCalendar", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "completePendingCommand"),
            new Call("org.apache.commons.net.ftp.FTPClient", "appendFileStream", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "deleteFile", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "makeDirectory", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "removeDirectory", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "storeFileStream", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "retrieveFileStream", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPClient", "setBufferSize", "int"),
            new Call("org.apache.commons.net.ftp.FTPClient", "setRestartOffset", "long"),
            new Call("org.apache.commons.net.ftp.FTPHTTPClient", "connect", "java.lang.String", "int"),
            new Call("org.apache.commons.net.ftp.FTPSClient", CONSTRUCTOR, "boolean", "javax.net.ssl.SSLContext"),
            new Call("org.apache.commons.net.ftp.FTPSClient", "execPROT", "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPSClient", "execPBSZ", "long"),
            new Call("org.apache.commons.net.ftp.FTPSClient", "_openDataConnection_", "java.lang.String",
                    "java.lang.String"),
            new Call("org.apache.commons.net.ftp.FTPSClient", "_connectAction_"),
            new Call("org.apache.commons.net.util.SSLContextUtils", "createSSLContext", "java.lang.String",
                    "javax.net.ssl.KeyManager[]", "javax.net.ssl.TrustManager[]"),
            // smbj
            new Call("com.hierynomus.smbj.SmbConfig", "builder"),
            new Call("com.hierynomus.smbj.SmbConfig", "getReadTimeout"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withDfsEnabled", "boolean"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withMultiProtocolNegotiate", "boolean"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withEncryptData", "boolean"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withReadTimeout", "long", TIME_UNIT),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withWriteTimeout", "long", TIME_UNIT),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withTransactTimeout", "long", TIME_UNIT),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withSoTimeout", "int"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withSocketFactory", "javax.net.SocketFactory"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withDialects", "com.hierynomus.mssmb2.SMB2Dialect[]"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withSecurityProvider",
                    "com.hierynomus.security.SecurityProvider"),
            new Call("com.hierynomus.smbj.SmbConfig$Builder", "withSigningRequired", "boolean"),
            new Call("com.hierynomus.smbj.SMBClient", CONSTRUCTOR, "com.hierynomus.smbj.SmbConfig"),
            new Call("com.hierynomus.smbj.connection.Connection", "isConnected"),
            new Call("com.hierynomus.smbj.share.Share", "isConnected"),
            new Call("com.hierynomus.smbj.share.DiskShare", "openFile", "java.lang.String", SET, SET, SET,
                    "com.hierynomus.mssmb2.SMB2CreateDisposition", SET),
            new Call("com.hierynomus.smbj.share.DiskShare", "open", "java.lang.String", SET, SET, SET,
                    "com.hierynomus.mssmb2.SMB2CreateDisposition", SET),
            new Call("com.hierynomus.smbj.share.DiskShare", "rm", "java.lang.String"),
            new Call("com.hierynomus.smbj.share.DiskShare", "getFileInformation", "java.lang.String"),
            new Call("com.hierynomus.smbj.share.DiskEntry", "createHardlink", "java.lang.String", "boolean"),
            new Call("com.hierynomus.smbj.share.DiskEntry", "rename", "java.lang.String", "boolean"),
            new Call("com.hierynomus.smbj.share.DiskEntry", "rename", "java.lang.String"),
            new Call("com.hierynomus.smbj.share.DiskEntry", "close"),
            new Call("com.hierynomus.smbj.share.File", "write", "byte[]", "long"),
            new Call("com.hierynomus.msfscc.fileinformation.FileBasicInformation", "getLastWriteTime"),
            new Call("com.hierynomus.mssmb2.SMBApiException", "getStatus"));

    @Test
    public void testEveryCallExists() {
        final List<String> report = missing(CALLS);
        assertTrue(report.isEmpty(), () -> report.size() + " library calls are missing:\n" + String.join("\n", report));
    }

    @Test
    public void testMissingCallNamesTheCallAndTheJar() throws ClassNotFoundException {
        final List<String> report = missing(
                Collections.singletonList(new Call("com.jcraft.jsch.Session", "connect", "java.lang.String")));
        assertEquals(1, report.size());
        final String line = report.get(0);
        assertTrue(line.contains("com.jcraft.jsch.Session.connect(java.lang.String)"), line);
        final String pack = System.getProperty("pack.plugins");
        final String where = pack != null ? pack : source(load("com.jcraft.jsch.Session"));
        assertTrue(line.contains(where), line);
    }

    /**
     * Gives one line for each call that the class path does not have. A line names the call and the jar or the pack
     * where it was looked for.
     */
    static List<String> missing(final List<Call> calls) {
        final List<String> report = new ArrayList<>();
        for (final Call call : calls) {
            final Class<?> owner;
            try {
                owner = load(call.owner);
            } catch (ClassNotFoundException | LinkageError e) {
                report.add(call + " is missing: its class does not load from " + packOrClassPath() + ". " + e);
                continue;
            }
            try {
                if (!call.existsIn(owner)) {
                    report.add(call + " is missing in " + source(owner));
                }
            } catch (ClassNotFoundException | LinkageError e) {
                report.add(call + " can not be checked in " + source(owner) + ". " + e);
            }
        }
        return report;
    }

    private static Class<?> load(final String name) throws ClassNotFoundException {
        return Class.forName(name, false, LibraryCallsTest.class.getClassLoader());
    }

    private static Class<?> type(final String name) throws ClassNotFoundException {
        switch (name) {
        case "boolean":
            return boolean.class;
        case "byte":
            return byte.class;
        case "int":
            return int.class;
        case "long":
            return long.class;
        default:
            if (name.endsWith("[]")) {
                return Array.newInstance(type(name.substring(0, name.length() - 2)), 0).getClass();
            }
            return load(name);
        }
    }

    /**
     * The jar of the pack names the pack. The pack folder is the answer when the class has no code source.
     */
    private static String source(final Class<?> owner) {
        final CodeSource code = owner.getProtectionDomain().getCodeSource();
        return code != null && code.getLocation() != null ? code.getLocation().toString() : packOrClassPath();
    }

    private static String packOrClassPath() {
        return System.getProperty("pack.plugins", "the test class path");
    }

    static final class Call {

        private final String owner;

        private final String name;

        private final String[] params;

        Call(final String owner, final String name, final String... params) {
            this.owner = owner;
            this.name = name;
            this.params = params;
        }

        /**
         * A public method may be inherited. A protected one is one that the fork overrides, so it is looked up in the
         * class and its superclasses.
         */
        boolean existsIn(final Class<?> type) throws ClassNotFoundException {
            final Class<?>[] types = new Class<?>[params.length];
            for (int index = 0; index < params.length; index++) {
                types[index] = type(params[index]);
            }
            if (CONSTRUCTOR.equals(name)) {
                try {
                    final Constructor<?> ctor = type.getDeclaredConstructor(types);
                    return Modifier.isPublic(ctor.getModifiers()) || Modifier.isProtected(ctor.getModifiers());
                } catch (final NoSuchMethodException e) {
                    return false;
                }
            }
            try {
                type.getMethod(name, types);
                return true;
            } catch (final NoSuchMethodException e) {
                for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
                    try {
                        final Method method = cls.getDeclaredMethod(name, types);
                        if (Modifier.isProtected(method.getModifiers())) {
                            return true;
                        }
                    } catch (final NoSuchMethodException ignored) {
                        // look in the superclass
                    }
                }
                return false;
            }
        }

        @Override
        public String toString() {
            return owner + "." + name + "(" + String.join(", ", params) + ")";
        }
    }
}
