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
package org.apache.commons.vfs2.provider.ftps;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.ftp.parser.FTPFileEntryParserFactory;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

/**
 * Create a FtpClient instance.
 *
 * @author <a href="http://commons.apache.org/vfs/team-list.html">Commons VFS team</a>
 * @version $Revision: 480428 $ $Date: 2006-11-29 07:15:24 +0100 (Mi, 29 Nov 2006) $
 * @since 2.0
 */
public final class FtpsClientFactory
{

    protected static String KS_PASSWD;
    protected static String TS_PASSWD;
    protected static String KEY_PASSWD;
    protected static String KEYSTORE;
    protected static String TRUSTSTORE;
    protected static KeyManager clientKeyManager;
    protected static TrustManager clientTrustManager;

    private static final Log log = LogFactory.getLog(FtpsClientFactory.class);

    private FtpsClientFactory()
    {
    }

    /**
     * Creates a new connection to the server.
     * @param hostname The host name.
     * @param port The port.
     * @param username The user name for authentication.
     * @param password The user's password.
     * @param workingDirectory The directory to use.
     * @param fileSystemOptions The FileSystemOptions.
     * @return The FTPSClient.
     * @throws FileSystemException if an error occurs.
     */
    public static FTPSClient createConnection(String hostname, int port, char[] username, char[] password,
                                              String workingDirectory, FileSystemOptions fileSystemOptions)
        throws FileSystemException
    {
        // Determine the username and password to use
        if (username == null)
        {
            username = "anonymous".toCharArray();
        }

        if (password == null)
        {
            password = "anonymous".toCharArray();
        }

        try
        {

            final FTPSClient client;

            if (FtpsFileSystemConfigBuilder.getInstance().getFtpsType(fileSystemOptions).equals("explicit"))
            {
                client = new FTPSClient();
            }
            else if (FtpsFileSystemConfigBuilder.getInstance().getFtpsType(fileSystemOptions).equals("implicit"))
            {
                client = new FTPSClient(true);
            }
            else
            {
                throw new FileSystemException(
                    "Invalid FTPS type of " + FtpsFileSystemConfigBuilder.getInstance().getFtpsType(
                        fileSystemOptions) + " specified. Must be 'implicit' or 'explicit'");
            }

                String key = FtpsFileSystemConfigBuilder.getInstance().getEntryParser(fileSystemOptions);
                if (key != null)
                {
                    FTPClientConfig config = new FTPClientConfig(key);

                    String serverLanguageCode = FtpsFileSystemConfigBuilder.getInstance().getServerLanguageCode(
                        fileSystemOptions);
                    if (serverLanguageCode != null)
                    {
                        config.setServerLanguageCode(serverLanguageCode);
                    }
                    String defaultDateFormat = FtpsFileSystemConfigBuilder.getInstance().getDefaultDateFormat(
                        fileSystemOptions);
                    if (defaultDateFormat != null)
                    {
                        config.setDefaultDateFormatStr(defaultDateFormat);
                    }
                    String recentDateFormat = FtpsFileSystemConfigBuilder.getInstance().getRecentDateFormat(
                        fileSystemOptions);
                    if (recentDateFormat != null)
                    {
                        config.setRecentDateFormatStr(recentDateFormat);
                    }
                    String serverTimeZoneId = FtpsFileSystemConfigBuilder.getInstance().getServerTimeZoneId(
                        fileSystemOptions);
                    if (serverTimeZoneId != null)
                    {
                        config.setServerTimeZoneId(serverTimeZoneId);
                    }
                    String[] shortMonthNames = FtpsFileSystemConfigBuilder.getInstance().getShortMonthNames(
                        fileSystemOptions);
                    if (shortMonthNames != null)
                    {
                        StringBuilder shortMonthNamesStr = new StringBuilder(40);
                        for (int i = 0; i < shortMonthNames.length; i++)
                        {
                            if (shortMonthNamesStr.length() > 0)
                            {
                                shortMonthNamesStr.append("|");
                            }
                            shortMonthNamesStr.append(shortMonthNames[i]);
                        }
                        config.setShortMonthNames(shortMonthNamesStr.toString());
                    }

                    client.configure(config);
                }

                FTPFileEntryParserFactory myFactory = FtpsFileSystemConfigBuilder.getInstance().getEntryParserFactory(
                    fileSystemOptions);
                if (myFactory != null)
                {
                    client.setParserFactory(myFactory);
                }

                try
                {
                    addSSLParameters(client, fileSystemOptions);
                    client.connect(hostname, port);
                    log.info("Successfully connected to the FTP server");

                    int reply = client.getReplyCode();
                    if (!FTPReply.isPositiveCompletion(reply))
                    {
                        throw new FileSystemException("vfs.provider.ftp/connect-rejected.error", hostname);
                    }

                    // Login
                    if (!client.login(
                        UserAuthenticatorUtils.toString(username),
                        UserAuthenticatorUtils.toString(password)))
                    {
                        throw new FileSystemException("vfs.provider.ftp/login.error",
                            new Object[]{hostname, UserAuthenticatorUtils.toString(username)}, null);
                    }

                    // Set binary mode
                    if (!client.setFileType(FTP.BINARY_FILE_TYPE))
                    {
                        throw new FileSystemException("vfs.provider.ftp/set-binary.error", hostname);
                    }

                    // Set dataTimeout value
                    Integer dataTimeout = FtpsFileSystemConfigBuilder.getInstance().getDataTimeout(fileSystemOptions);
                    if (dataTimeout != null)
                    {
                        client.setDataTimeout(dataTimeout.intValue());
                    }

                    // Change to root by default
                    // All file operations a relative to the filesystem-root
                    // String root = getRoot().getName().getPath();

                    Boolean userDirIsRoot = FtpsFileSystemConfigBuilder.getInstance().getUserDirIsRoot(
                        fileSystemOptions);
                    if (workingDirectory != null && (userDirIsRoot == null || !userDirIsRoot.booleanValue()))
                    {
                        if (!client.changeWorkingDirectory(workingDirectory))
                        {
                            throw new FileSystemException("vfs.provider.ftp/change-work-directory.error",
                                workingDirectory);
                        }
                    }

                    Boolean passiveMode = FtpsFileSystemConfigBuilder.getInstance().getPassiveMode(fileSystemOptions);
                    if (passiveMode != null && passiveMode.booleanValue())
                    {
                        client.enterLocalPassiveMode();
                    }
                }
                catch (final IOException e)
                {
                    if (client.isConnected())
                    {
                        client.disconnect();
                    }
                    throw e;
                }

                return client;
            }
            catch (final Exception exc)
            {
                log.error(exc);
                throw new FileSystemException("vfs.provider.ftp/connect.error", new Object[]{hostname}, exc);
            }
        }
    private static void addSSLParameters(FTPSClient ftpsClient, FileSystemOptions fileSystemOptions) throws Exception{

        KEYSTORE=FtpsFileSystemConfigBuilder.getInstance().getKeyStore(fileSystemOptions);
        TRUSTSTORE=FtpsFileSystemConfigBuilder.getInstance().getTrustStore(fileSystemOptions);
        KS_PASSWD=FtpsFileSystemConfigBuilder.getInstance().getKeyStorePW(fileSystemOptions);
        TS_PASSWD=FtpsFileSystemConfigBuilder.getInstance().getTrustStorePW(fileSystemOptions);
        KEY_PASSWD=FtpsFileSystemConfigBuilder.getInstance().getKeyPW(fileSystemOptions);

        KeyManagerFactory keyManagerFactory = null;
        TrustManagerFactory trustManagerFactory = null;
        try {
            FileInputStream keystorePath = new FileInputStream(new File(KEYSTORE));
            FileInputStream truststorePath = new FileInputStream(new File(TRUSTSTORE));
            KeyStore keyStore = KeyStore.getInstance("jks");
            KeyStore trustStore = KeyStore.getInstance("jks");

            //load keystore and trust store.
            keyStore.load(keystorePath, KS_PASSWD.toCharArray());
            trustStore.load(truststorePath,TS_PASSWD.toCharArray());
            keystorePath.close();
            truststorePath.close();

            // initialize key manager factory
            keyManagerFactory = KeyManagerFactory
                    .getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, KEY_PASSWD.toCharArray());

            // initialize trust manager factory
            trustManagerFactory = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());

            trustManagerFactory.init(trustStore);
        } catch (KeyStoreException e) {
            log.error("Error occurred when initializing keystores",e);
            throw e;
        } catch (IOException e) {
            log.error("Error occurred while retrieving the keystore paths",e);
            throw e;
        } catch (NoSuchAlgorithmException e) {
            log.error("Error when getting the default algorithm",e);
            throw e;
        } catch (CertificateException e) {
            log.error("Certificate exception occurred when loading the KeyStores ",e);
            throw e;
        } catch (UnrecoverableKeyException e) {
            log.error("Unrecoverable Key exception occurred",e);
            throw e;
        }

        clientKeyManager = keyManagerFactory.getKeyManagers()[0];
        clientTrustManager = trustManagerFactory.getTrustManagers()[0];

        ftpsClient.setKeyManager(clientKeyManager);
        ftpsClient.setTrustManager(clientTrustManager);

        log.info("SSL parameters added to the FTPS client");
    }

    }
