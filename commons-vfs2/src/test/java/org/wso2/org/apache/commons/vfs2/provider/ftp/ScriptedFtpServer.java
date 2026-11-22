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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;

/**
 * An embedded FTP server for the tests of the FTP and FTPS providers. It records every command with the session that
 * sent it, and it answers a command by a rule: a reply of the test, a delay, or a dropped connection.
 */
public final class ScriptedFtpServer implements AutoCloseable {

    public static final String USER = "test";

    public static final String PASSWORD = "test";

    private static final String LISTENER = "default";

    private static final String SERVER_JKS = "org.apache.ftpsserver/ftpserver.jks";

    private final FtpServer server;

    private final int port;

    private final File home;

    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());

    private final List<Rule> rules = new CopyOnWriteArrayList<>();

    private final Map<UUID, Integer> sessions = new ConcurrentHashMap<>();

    private final AtomicInteger connects = new AtomicInteger();

    /**
     * One rule. It answers a command whose argument contains the given text, a given number of times.
     */
    private static final class Rule {
        final String command;
        final String argument;
        final int code;
        final String text;
        final long delayMillis;
        final boolean drop;
        final AtomicInteger left;

        Rule(final String command, final String argument, final int code, final String text, final long delayMillis,
                final boolean drop, final int times) {
            this.command = command;
            this.argument = argument;
            this.code = code;
            this.text = text;
            this.delayMillis = delayMillis;
            this.drop = drop;
            this.left = new AtomicInteger(times);
        }

        boolean take(final String cmd, final String arg) {
            if (!command.equalsIgnoreCase(cmd)) {
                return false;
            }
            if (argument != null && (arg == null || !arg.contains(argument))) {
                return false;
            }
            return left.getAndDecrement() > 0;
        }
    }

    private ScriptedFtpServer(final File home, final boolean tls, final boolean implicit, final String[] protocols)
            throws FtpException {
        this(home, tls, implicit, protocols, false);
    }

    private ScriptedFtpServer(final File home, final boolean tls, final boolean implicit, final String[] protocols,
            final boolean clientCertificate) throws FtpException {
        this.home = home;
        final FtpServerFactory serverFactory = new FtpServerFactory();
        final BaseUser user = new BaseUser();
        user.setName(USER);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(home.getAbsolutePath());
        final List<Authority> authorities = new ArrayList<>();
        authorities.add(new WritePermission());
        authorities.add(new ConcurrentLoginPermission(0, 0));
        user.setEnabled(true);
        user.setAuthorities(authorities);
        serverFactory.setUserManager(new OneUser(user));

        final ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setPort(0);
        final DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1");
        data.setPassiveExternalAddress("127.0.0.1");
        listenerFactory.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        if (tls) {
            final SslConfigurationFactory ssl = new SslConfigurationFactory();
            ssl.setKeystoreFile(serverKeyStore());
            ssl.setKeystorePassword("password");
            if (clientCertificate) {
                // The server trusts the one certificate of its own store, so a client with that store is accepted.
                ssl.setTruststoreFile(serverKeyStore());
                ssl.setTruststorePassword("password");
                ssl.setClientAuthentication("NEED");
            }
            listenerFactory.setSslConfiguration(new Protocols(ssl.createSslConfiguration(), protocols));
            listenerFactory.setImplicitSsl(implicit);
        }
        serverFactory.addListener(LISTENER, listenerFactory.createListener());
        final Map<String, org.apache.ftpserver.ftplet.Ftplet> ftplets = new ConcurrentHashMap<>();
        ftplets.put("script", new Script());
        serverFactory.setFtplets(ftplets);
        server = serverFactory.createServer();
        server.start();
        port = ((DefaultFtpServer) server).getListener(LISTENER).getPort();
    }

    /**
     * Starts a plain FTP server.
     *
     * @param home the home folder of the user.
     * @return the server.
     * @throws FtpException if it does not start.
     */
    public static ScriptedFtpServer plain(final File home) throws FtpException {
        return new ScriptedFtpServer(home, false, false, null);
    }

    /**
     * Starts an FTPS server.
     *
     * @param home the home folder of the user.
     * @param implicit true for implicit FTPS.
     * @param protocols the TLS versions that the server offers, or null for the defaults of the JDK.
     * @return the server.
     * @throws FtpException if it does not start.
     */
    public static ScriptedFtpServer tls(final File home, final boolean implicit, final String... protocols)
            throws FtpException {
        return new ScriptedFtpServer(home, true, implicit, protocols.length == 0 ? null : protocols);
    }

    /**
     * Starts an explicit FTPS server that requires a client certificate signed as its own certificate.
     *
     * @param home the home folder of the user.
     * @return the server.
     * @throws FtpException if it does not start.
     */
    public static ScriptedFtpServer tlsWithClientCertificate(final File home) throws FtpException {
        return new ScriptedFtpServer(home, true, false, null, true);
    }

    /**
     * Gets the key store of the server, which holds its key and its certificate.
     *
     * @return the file.
     */
    public static File serverKeyStore() {
        return new File(ClassLoader.getSystemClassLoader().getResource(SERVER_JKS).getFile());
    }

    public int getPort() {
        return port;
    }

    public File getHome() {
        return home;
    }

    /**
     * Gets the URI of a path with the user and the password.
     *
     * @param scheme ftp or ftps.
     * @param path the path, which starts with a slash.
     * @return the URI.
     */
    public String uri(final String scheme, final String path) {
        return scheme + "://" + USER + ":" + PASSWORD + "@127.0.0.1:" + port + path;
    }

    /**
     * Answers a command with a reply in place of the server, a number of times.
     *
     * @param command the command.
     * @param argument a text that the argument holds, or null for any argument.
     * @param code the reply code.
     * @param times how many times.
     * @return this server.
     */
    public ScriptedFtpServer reply(final String command, final String argument, final int code, final int times) {
        rules.add(new Rule(command, argument, code, "Scripted reply " + code, 0, false, times));
        return this;
    }

    /**
     * Waits before the server runs a command, a number of times.
     *
     * @param command the command.
     * @param millis the delay.
     * @param times how many times.
     * @return this server.
     */
    public ScriptedFtpServer delay(final String command, final long millis, final int times) {
        rules.add(new Rule(command, null, 0, null, millis, false, times));
        return this;
    }

    /**
     * Drops the connection when a command comes, a number of times. The command is not run.
     *
     * @param command the command.
     * @param times how many times.
     * @return this server.
     */
    public ScriptedFtpServer drop(final String command, final int times) {
        rules.add(new Rule(command, null, 0, null, 0, true, times));
        return this;
    }

    /**
     * Gets the commands that came, as "session command argument".
     *
     * @return a copy.
     */
    public List<String> trace() {
        synchronized (trace) {
            return new ArrayList<>(trace);
        }
    }

    /**
     * Counts the commands with this name.
     *
     * @param command the command.
     * @return the count.
     */
    public long count(final String command) {
        return trace().stream().filter(line -> line.split(" ")[1].equalsIgnoreCase(command)).count();
    }

    /**
     * Counts the commands with this name and an argument that holds the text.
     *
     * @param command the command.
     * @param argument the text.
     * @return the count.
     */
    public long count(final String command, final String argument) {
        return trace().stream().filter(line -> {
            final String[] parts = line.split(" ", 3);
            return parts[1].equalsIgnoreCase(command) && parts.length > 2 && parts[2].contains(argument);
        }).count();
    }

    public int getConnects() {
        return connects.get();
    }

    @Override
    public void close() {
        server.stop();
    }

    private final class Script extends DefaultFtplet {

        @Override
        public FtpletResult onConnect(final FtpSession session) throws FtpException, IOException {
            connects.incrementAndGet();
            sessions.putIfAbsent(session.getSessionId(), sessions.size() + 1);
            return super.onConnect(session);
        }

        @Override
        public FtpletResult beforeCommand(final FtpSession session, final FtpRequest request)
                throws FtpException, IOException {
            final String command = request.getCommand();
            final String argument = request.getArgument();
            trace.add(sessions.getOrDefault(session.getSessionId(), 0) + " " + command
                    + (argument == null ? "" : " " + argument));
            for (final Rule rule : rules) {
                if (rule.take(command, argument)) {
                    if (rule.delayMillis > 0) {
                        try {
                            Thread.sleep(rule.delayMillis);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (rule.drop) {
                        return FtpletResult.DISCONNECT;
                    }
                    if (rule.code > 0) {
                        session.write(new DefaultFtpReply(rule.code, rule.text));
                        return FtpletResult.SKIP;
                    }
                    break;
                }
            }
            return super.beforeCommand(session, request);
        }
    }

    // The one user of the test, in memory.
    private static final class OneUser implements UserManager {

        private final BaseUser user;

        OneUser(final BaseUser user) {
            this.user = user;
        }

        @Override
        public User getUserByName(final String name) {
            return USER.equals(name) ? user : null;
        }

        @Override
        public String[] getAllUserNames() {
            return new String[] {USER};
        }

        @Override
        public void delete(final String name) {
            // The user stays.
        }

        @Override
        public void save(final User changed) {
            // The user stays as made.
        }

        @Override
        public boolean doesExist(final String name) {
            return USER.equals(name);
        }

        @Override
        public User authenticate(final Authentication authentication) throws AuthenticationFailedException {
            if (authentication instanceof UsernamePasswordAuthentication) {
                final UsernamePasswordAuthentication login = (UsernamePasswordAuthentication) authentication;
                if (USER.equals(login.getUsername()) && PASSWORD.equals(login.getPassword())) {
                    return user;
                }
            }
            throw new AuthenticationFailedException("Wrong user or password");
        }

        @Override
        public String getAdminName() {
            return "admin";
        }

        @Override
        public boolean isAdmin(final String name) {
            return false;
        }
    }

    // Offers only the given TLS versions, or the defaults of the JDK.
    private static final class Protocols implements SslConfiguration {

        private final SslConfiguration delegate;

        private final String[] protocols;

        Protocols(final SslConfiguration delegate, final String[] protocols) {
            this.delegate = delegate;
            this.protocols = protocols;
        }

        @Override
        public ClientAuth getClientAuth() {
            return delegate.getClientAuth();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return delegate.getEnabledCipherSuites();
        }

        @Override
        public String[] getEnabledProtocols() {
            return protocols;
        }

        @Override
        public SSLSocketFactory getSocketFactory() throws GeneralSecurityException {
            return delegate.getSocketFactory();
        }

        @Override
        public SSLContext getSSLContext() throws GeneralSecurityException {
            return delegate.getSSLContext();
        }

        @Override
        public SSLContext getSSLContext(final String protocol) throws GeneralSecurityException {
            return delegate.getSSLContext(protocol);
        }
    }
}
