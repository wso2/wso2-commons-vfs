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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.EffectiveBounds;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.SocketGroup;

/**
 * The FTP client rules of part 3 sections 5.4 and 5.5, the child map of section 11.4, the faults that each command
 * site sets by the reply of the server (section 5.7), and the abort of section 5.1.
 */
public class FtpClientHygieneTest {

    private static final byte[] SMALL = "small file".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        Files.write(new File(home, "small.txt").toPath(), SMALL);
        Files.write(new File(home, "a.txt").toPath(), SMALL);
        new File(home, "d").mkdir();
        Files.write(new File(home, "d/f.txt").toPath(), SMALL);
        new File(home, "e").mkdir();
        server = ScriptedFtpServer.plain(home);
        manager = new StandardFileSystemManager();
        manager.init();
    }

    @AfterEach
    public void stop() {
        manager.close();
        server.close();
    }

    private FileObject resolve(final String path) throws FileSystemException {
        return resolve(path, new FileSystemOptions());
    }

    private FileObject resolve(final String path, final FileSystemOptions options) throws FileSystemException {
        return manager.resolveFile(server.uri("ftp", path), options);
    }

    private static FtpFileSystem fileSystem(final FileObject file) {
        return (FtpFileSystem) file.getFileSystem();
    }

    private static boolean messageHolds(final Throwable error, final String text) {
        for (Throwable link = error; link != null; link = link.getCause()) {
            if (String.valueOf(link.getMessage()).contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void refreshOfAPreviouslyListedFileListsItsParentOncePerRefresh() throws Exception {
        final FileObject file = resolve("/d/f.txt");
        assertTrue(file.exists());
        assertEquals(SMALL.length, file.getContent().getSize());
        final long before = server.count("LIST");
        Files.write(new File(home, "d/f.txt").toPath(), new byte[43]);
        file.refresh();
        assertEquals(before, server.count("LIST"));
        assertEquals(43, file.getContent().getSize());
        assertTrue(file.exists());
        assertEquals(before + 1, server.count("LIST"));
        Files.delete(new File(home, "d/f.txt").toPath());
        file.refresh();
        assertFalse(file.exists());
        assertFalse(file.exists());
        assertEquals(before + 2, server.count("LIST"));
    }

    @Test
    public void renameWhoseReplyIsLostIsSentOnce() throws Exception {
        final FileObject source = resolve("/a.txt");
        final FileObject target = resolve("/b.txt");
        assertTrue(source.exists());
        server.drop("RNTO", 1);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> source.moveTo(target));
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertEquals(1, server.count("RNTO"));
        assertEquals(0, fileSystem(source).getHandedOutCount());
    }

    @Test
    public void deleteWhoseReplyIsLostIsSentOnce() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        server.drop("DELE", 1);
        final FileSystemException error = assertThrows(FileSystemException.class, file::delete);
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertEquals(1, server.count("DELE"));
        assertTrue(new File(home, "a.txt").exists());
    }

    @Test
    public void readThatFailsFastIsRepeatedOnceOnANewClient() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertTrue(file.exists());
        server.drop("RETR", 1);
        assertArrayEquals(SMALL, file.getContent().getByteArray());
        assertEquals(2, server.count("RETR"));
        assertEquals(2, server.count("USER"));
    }

    @Test
    public void readThatFailsSlowIsNotRepeated() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setSoTimeout(options, Duration.ofMillis(5500));
        final FileObject file = resolve("/small.txt", options);
        assertTrue(file.exists());
        server.delay("RETR", 8000, 1);
        final long start = System.nanoTime();
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> file.getContent().getByteArray());
        final long millis = (System.nanoTime() - start) / 1000000;
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertTrue(millis >= 5000 && millis < 7500, "took " + millis + " ms");
        assertEquals(1, server.count("RETR"));
        assertEquals(1, server.count("USER"));
    }

    @Test
    public void listingThatEndsWithATransportReplyIsNotFollowedByTheSecondForm() throws Exception {
        final FileObject folder = resolve("/d");
        assertTrue(folder.exists());
        server.reply("LIST", "d", FTPReply.CANNOT_OPEN_DATA_CONNECTION, 10);
        final FileSystemException error = assertThrows(FileSystemException.class, folder::getChildren);
        assertEquals(Fault.TRANSPORT, Faults.of(error));
        assertTrue(messageHolds(error, "425"), String.valueOf(error));
        assertEquals(0, server.count("CWD"));
        // The listing is a read, so it is made once more on a new client inside the fast window.
        assertEquals(2, server.count("LIST", "d"));
        assertEquals(0, fileSystem(folder).getHandedOutCount());
    }

    @Test
    public void listingRefusalRunsTheSecondFormOnTheSameClient() throws Exception {
        final FileObject folder = resolve("/d");
        assertTrue(folder.exists());
        server.reply("LIST", "d", FTPReply.FILE_UNAVAILABLE, 1);
        final FileObject[] children = folder.getChildren();
        assertEquals(1, children.length);
        assertEquals(1, server.count("CWD", "d"));
        assertEquals(1, server.count("USER"));
    }

    @Test
    public void disconnectOfAWrapperWithNoClientOpensNoConnection() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertArrayEquals(SMALL, file.getContent().getByteArray());
        final FtpFileSystem fileSystem = fileSystem(file);
        final FtpClient wrapper = fileSystem.getClient();
        fileSystem.putClient(wrapper);
        manager.closeFileSystem(fileSystem);
        final int connects = server.getConnects();
        wrapper.disconnect();
        wrapper.abort();
        assertEquals(connects, server.getConnects());
        assertFalse(wrapper.isConnected());
    }

    @Test
    public void timeReadsWithMdtmGiveTheirClientBack() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setMdtmLastModifiedTime(options, true);
        final FileObject file = resolve("/small.txt", options);
        assertArrayEquals(SMALL, file.getContent().getByteArray());
        for (int i = 0; i < 100; i++) {
            file.refresh();
            assertTrue(file.getContent().getLastModifiedTime() > 0);
        }
        final FtpFileSystem fileSystem = fileSystem(file);
        assertEquals(0, fileSystem.getHandedOutCount());
        assertEquals(1, fileSystem.getOpenClientCount());
        assertEquals(100, server.count("MDTM"));
        assertEquals(1, server.count("USER"));
    }

    @Test
    public void optionsOfTheCallerStayAsTheyWere() throws Exception {
        for (int i = 0; i < 200; i++) {
            final FileSystemOptions options = new FileSystemOptions();
            final FileSystemOptions copy = (FileSystemOptions) options.clone();
            final FileObject folder = resolve("/d?vfs.passive=true&transport.vfs.ConnectTimeout=30000", options);
            assertEquals(1, folder.getChildren().length);
            assertEquals(copy, options);
        }
        assertEquals(1, server.count("USER"));
    }

    @Test
    public void nullOptionsWithTheUriTimeoutDoNotThrow() throws Exception {
        final FileObject folder = manager.resolveFile(server.uri("ftp", "/d?transport.vfs.ConnectTimeout=30000"),
                (FileSystemOptions) null);
        assertEquals(1, folder.getChildren().length);
        final EffectiveBounds bounds = EffectiveBounds.of("ftp", fileSystem(folder).getClientOptions());
        assertEquals(30000, bounds.getConnectTimeout().getValue());
        assertEquals(30000, bounds.getSocketTimeout().getValue());
    }

    @Test
    public void childMapIsNeverNullAndNeverReplaced() throws Exception {
        final FtpFileObject folder = ftp(resolve("/d"));
        assertEquals(1, folder.getChildren().length);
        final Field field = FtpFileObject.class.getDeclaredField("childMap");
        field.setAccessible(true);
        final Object map = field.get(folder);
        assertNotNull(map);
        folder.refresh();
        assertSame(map, field.get(folder));
        final FileObject created = folder.resolveFile("new.txt");
        created.createFile();
        assertSame(map, field.get(folder));
        created.delete();
        assertSame(map, field.get(folder));
        assertEquals(1, folder.getChildren().length);
        assertSame(map, field.get(folder));

        // An empty map also means not listed yet, so a look into an empty folder lists it again.
        final FileObject empty = resolve("/e");
        assertEquals(0, empty.getChildren().length);
        final long lists = server.count("LIST", "e");
        assertFalse(empty.resolveFile("x.txt").exists());
        assertEquals(lists + 1, server.count("LIST", "e"));
        assertNotNull(field.get(ftp(empty)));
    }

    @Test
    public void refusedDeleteCarriesTheReplyAndKeepsTheClient() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        final int connects = server.getConnects();
        server.reply("DELE", null, FTPReply.FILE_UNAVAILABLE, 1);
        final FileSystemException error = assertThrows(FileSystemException.class, file::delete);
        assertEquals(Fault.APPLICATION, Faults.of(error));
        assertTrue(messageHolds(error, "550"), String.valueOf(error));
        assertEquals(0, fileSystem(file).getHandedOutCount());
        assertEquals(1, fileSystem(file).getOpenClientCount());
        assertTrue(file.exists());
        assertEquals(connects, server.getConnects());
    }

    @Test
    public void refusedCommandsCarryTheFaultOfTheirReply() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());

        server.reply("DELE", null, FTPReply.FILE_ACTION_NOT_TAKEN, 1);
        assertEquals(Fault.BUSY, Faults.of(assertThrows(FileSystemException.class, file::delete)));

        final FileObject folder = resolve("/m");
        server.reply("MKD", null, FTPReply.FILE_UNAVAILABLE, 1);
        final FileSystemException mkd = assertThrows(FileSystemException.class, folder::createFolder);
        assertEquals(Fault.APPLICATION, Faults.of(mkd));
        assertTrue(messageHolds(mkd, "550"), String.valueOf(mkd));

        final FileObject target = resolve("/c.txt");
        server.reply("RNTO", null, 553, 1);
        final FileSystemException rename = assertThrows(FileSystemException.class, () -> ftp(file).doRename(target));
        assertEquals(Fault.APPLICATION, Faults.of(rename));
        assertTrue(messageHolds(rename, "553"), String.valueOf(rename));

        final FileObject read = resolve("/small.txt");
        assertTrue(read.exists());
        server.reply("RETR", null, FTPReply.FILE_UNAVAILABLE, 1);
        final FileSystemException missing = assertThrows(FileSystemException.class,
                () -> read.getContent().getInputStream());
        assertEquals(Fault.MISSING, Faults.of(missing));
        server.reply("RETR", null, FTPReply.FILE_ACTION_NOT_TAKEN, 1);
        final FileSystemException busy = assertThrows(FileSystemException.class,
                () -> read.getContent().getInputStream());
        assertEquals(Fault.BUSY, Faults.of(busy));
        assertTrue(messageHolds(busy, "450"), String.valueOf(busy));

        final FileObject write = resolve("/w.txt");
        server.reply("STOR", null, 552, 1);
        final FileSystemException store = assertThrows(FileSystemException.class,
                () -> write.getContent().getOutputStream());
        assertEquals(Fault.APPLICATION, Faults.of(store));
        assertTrue(messageHolds(store, "552"), String.valueOf(store));
        server.reply("STOR", null, FTPReply.FILE_UNAVAILABLE, 1);
        assertEquals(Fault.APPLICATION, Faults.of(assertThrows(FileSystemException.class,
                () -> write.getContent().getOutputStream())));

        // A refusal is a reply, so no client was discarded and no new connection was made.
        assertEquals(1, server.count("USER"));
    }

    @Test
    public void everyReplyRowGivesItsFault() {
        assertEquals(Fault.TRANSPORT, FtpFileObject.faultOf(421, true));
        assertEquals(Fault.TRANSPORT, FtpFileObject.faultOf(425, false));
        assertEquals(Fault.TRANSPORT, FtpFileObject.faultOf(426, true));
        assertEquals(Fault.BUSY, FtpFileObject.faultOf(450, false));
        assertEquals(Fault.MISSING, FtpFileObject.faultOf(550, true));
        assertEquals(Fault.APPLICATION, FtpFileObject.faultOf(550, false));
        for (final int reply : new int[] {530, 532, 451, 452, 552, 553, 500, 502}) {
            assertEquals(Fault.APPLICATION, FtpFileObject.faultOf(reply, true), "reply " + reply);
            assertEquals(Fault.APPLICATION, FtpFileObject.faultOf(reply, false), "reply " + reply);
        }
    }

    @Test
    public void abortFromAnotherThreadEndsABlockedRead() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertTrue(file.exists());
        final FtpFileSystem fileSystem = fileSystem(file);
        server.delay("RETR", 30000, 1);
        final CompletableFuture<Throwable> reader = CompletableFuture.supplyAsync(() -> {
            try {
                file.getContent().getByteArray();
                return null;
            } catch (final Throwable e) {
                return e;
            }
        });
        waitFor(() -> server.count("RETR") > 0);
        // After the fast window of the call a failure is not repeated, so the blocked read itself must end.
        Thread.sleep(5500);
        final long start = System.nanoTime();
        fileSystem.abort();
        assertTrue((System.nanoTime() - start) / 1000000 < 1000);
        assertFalse(fileSystem.isAliveLocally());
        final Throwable error = reader.get(1500, TimeUnit.MILLISECONDS);
        assertNotNull(error);
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        // The next call runs on a new client.
        file.refresh();
        assertArrayEquals(SMALL, file.getContent().getByteArray());
    }

    @Test
    public void abortAllOfTheManagerReachesAnFtpSocket() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertTrue(file.exists());
        server.delay("RETR", 30000, 1);
        final CompletableFuture<Throwable> reader = CompletableFuture.supplyAsync(() -> {
            try {
                file.getContent().getByteArray();
                return null;
            } catch (final Throwable e) {
                return e;
            }
        });
        waitFor(() -> server.count("RETR") > 0);
        Thread.sleep(300);
        manager.abortAll();
        final Throwable error = reader.get(1500, TimeUnit.MILLISECONDS);
        assertNotNull(error);
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertFalse(manager.isAliveLocally());
    }

    @Test
    public void abortEndsAWaitInTheAcceptOfAnActiveDataConnection() throws Exception {
        // The server answers 150 and never opens the data connection, so the client waits in the accept of its
        // server socket. That socket comes from the factory of the group.
        server.reply("RETR", null, 150, 1);
        final SocketGroup group = new SocketGroup();
        final FTPClient client = FtpClientFactory.createConnection("127.0.0.1", server.getPort(),
                ScriptedFtpServer.USER.toCharArray(), ScriptedFtpServer.PASSWORD.toCharArray(), null,
                new FileSystemOptions(), null, null, null, null, null, null, group, System.nanoTime());
        final CompletableFuture<Throwable> reader = CompletableFuture.supplyAsync(() -> {
            try {
                client.retrieveFileStream("small.txt");
                return null;
            } catch (final Throwable e) {
                return e;
            }
        });
        waitFor(() -> server.count("RETR") > 0);
        Thread.sleep(500);
        assertFalse(reader.isDone());
        final long start = System.nanoTime();
        group.abort("test");
        final Throwable error = reader.get(1000, TimeUnit.MILLISECONDS);
        assertNotNull(error);
        assertTrue((System.nanoTime() - start) / 1000000 < 1000);
        assertTrue(Faults.isTransport(error), String.valueOf(error));
    }

    @Test
    public void countsFollowTheClientsThatAreOut() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertTrue(file.exists());
        final FtpFileSystem fileSystem = fileSystem(file);
        assertEquals(0, fileSystem.getHandedOutCount());
        try (InputStream in = file.getContent().getInputStream()) {
            assertEquals(1, fileSystem.getHandedOutCount());
            assertEquals(1, fileSystem.getOpenClientCount());
            in.read();
        }
        assertEquals(0, fileSystem.getHandedOutCount());
        assertEquals(1, fileSystem.getOpenClientCount());
        final FileObject out = resolve("/out.txt");
        try (OutputStream stream = out.getContent().getOutputStream()) {
            stream.write(SMALL);
            assertEquals(1, fileSystem.getHandedOutCount());
        }
        assertEquals(0, fileSystem.getHandedOutCount());
        assertTrue(fileSystem.isAliveLocally());
        assertArrayEquals(SMALL, Files.readAllBytes(new File(home, "out.txt").toPath()));
    }

    @Test
    public void deleteOfAFileWhoseInfoWasClearedLooksItUpAndSendsOneDele() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        // A listing on another thread refreshes the file object after the delete chose it, so its info is null when
        // the delete runs.
        file.refresh();
        final long lists = server.count("LIST");
        ftp(file).doDelete();
        assertEquals(1, server.count("DELE"));
        assertEquals(lists + 1, server.count("LIST"));
        assertFalse(new File(home, "a.txt").exists());
        assertEquals(1, fileSystem(file).getOpenClientCount());
    }

    @Test
    public void deleteOfAFolderWhoseInfoWasClearedSendsOneRmd() throws Exception {
        final FileObject folder = resolve("/e");
        assertTrue(folder.exists());
        folder.refresh();
        ftp(folder).doDelete();
        assertEquals(1, server.count("RMD"));
        assertEquals(0, server.count("DELE"));
        assertFalse(new File(home, "e").exists());
    }

    @Test
    public void deleteWhoseNewListingDoesNotShowTheEntryIsAnErrorWithNoDele() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        file.refresh();
        assertTrue(new File(home, "a.txt").delete());
        final int connects = server.getConnects();
        final FileSystemException error = assertThrows(FileSystemException.class, () -> ftp(file).doDelete());
        assertEquals(Fault.MISSING, Faults.of(error));
        assertEquals(0, server.count("DELE"));
        assertEquals(connects, server.getConnects());
        assertEquals(1, fileSystem(file).getOpenClientCount());
    }

    @Test
    public void deleteWhoseListingIsRefusedIsAnErrorWithNoDele() throws Exception {
        final FileObject file = resolve("/d/f.txt");
        assertTrue(file.exists());
        file.refresh();
        // The listing of the folder is refused, and so is the change into it of the second form.
        server.reply("LIST", null, FTPReply.FILE_UNAVAILABLE, 2);
        server.reply("CWD", null, FTPReply.FILE_UNAVAILABLE, 1);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> ftp(file).doDelete());
        assertTrue(messageHolds(error, "550"), String.valueOf(error));
        assertEquals(0, server.count("DELE"));
        assertTrue(new File(home, "d/f.txt").exists());
        assertEquals(1, fileSystem(file).getOpenClientCount());
    }

    @Test
    public void deleteWhoseListingEndsWithATransportReplyIsATransportErrorWithNoDele() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        file.refresh();
        server.reply("LIST", null, FTPReply.CANNOT_OPEN_DATA_CONNECTION, 2);
        final FileSystemException error = assertThrows(FileSystemException.class, () -> ftp(file).doDelete());
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertEquals(0, server.count("DELE"));
        assertTrue(new File(home, "a.txt").exists());
        assertEquals(0, fileSystem(file).getOpenClientCount());
    }

    @Test
    public void deleteWithItsInfoInPlaceSendsNoListing() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        final long lists = server.count("LIST");
        assertTrue(file.delete());
        assertEquals(lists, server.count("LIST"));
        assertEquals(1, server.count("DELE"));
        assertFalse(new File(home, "a.txt").exists());
    }

    @Test
    @Tag("testbuild")
    public void failedStreamCloseDiscardsTheFtpClient() throws Exception {
        final String property = "vfs.testpoints.dir";
        final String previous = System.getProperty(property);
        final Path points = Files.createDirectory(home.toPath().resolve("points"));
        System.setProperty(property, points.toString());
        try {
            TestPoints.copy("close", getClass());
            TestPoints.event("open", null);
            final Path fault;
            try (java.util.stream.Stream<Path> copies = Files.list(points)) {
                fault = copies.filter(folder -> Files.isRegularFile(folder.resolve("copy")))
                        .findFirst().get().resolve("stream.close.fault");
            }
            final FileObject file = resolve("/small.txt");
            try (InputStream input = file.getContent().getInputStream()) {
                assertTrue(input.read() >= 0);
                Files.write(fault, "close-fails".getBytes(StandardCharsets.UTF_8));
                assertThrows(IOException.class, input::close);
            }
            assertEquals(0, fileSystem(file).getHandedOutCount());
            assertEquals(0, fileSystem(file).getOpenClientCount());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    public void aListingEntryWithoutATimestampReturnsZero() throws Exception {
        final FileObject file = resolve("/small.txt");
        assertTrue(file.exists());
        final Field info = FtpFileObject.class.getDeclaredField("ftpFile");
        info.setAccessible(true);
        ((FTPFile) info.get(ftp(file))).setTimestamp(null);
        assertEquals(0, file.getContent().getLastModifiedTime());
    }

    @Test
    public void mdtmGivesTheTimeOfTheFile() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setMdtmLastModifiedTime(options, true);
        final FileObject file = resolve("/small.txt", options);
        final long onDisk = new File(home, "small.txt").lastModified();
        assertTrue(Math.abs(onDisk - file.getContent().getLastModifiedTime()) < 1000);
        assertEquals(1, server.count("MDTM"));
    }

    @Test
    public void mdtmWithNoReply213FallsBackToTheTimeOfTheListing() throws Exception {
        final long listed = resolve("/small.txt").getContent().getLastModifiedTime();
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setMdtmLastModifiedTime(options, true);
        final FileObject file = resolve("/small.txt", options);
        assertTrue(file.exists());
        for (final int code : new int[] {FTPReply.FILE_UNAVAILABLE, FTPReply.FILE_ACTION_NOT_TAKEN}) {
            file.refresh();
            final long mdtms = server.count("MDTM");
            final int connects = server.getConnects();
            server.reply("MDTM", null, code, 1);
            assertEquals(listed, file.getContent().getLastModifiedTime(), "reply " + code);
            assertEquals(mdtms + 1, server.count("MDTM"), "reply " + code);
            assertEquals(connects, server.getConnects(), "reply " + code);
            assertEquals(0, fileSystem(file).getHandedOutCount());
            assertEquals(1, fileSystem(file).getOpenClientCount());
        }
    }

    @Test
    public void mdtmWithATransportReplyIsATransportErrorAfterOneRepeat() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setMdtmLastModifiedTime(options, true);
        final FileObject file = resolve("/small.txt", options);
        assertTrue(file.exists());
        final int connects = server.getConnects();
        server.reply("MDTM", null, FTPReply.CANNOT_OPEN_DATA_CONNECTION, 2);
        final FileSystemException error = assertThrows(FileSystemException.class,
                () -> file.getContent().getLastModifiedTime());
        assertTrue(Faults.isTransport(error), String.valueOf(error));
        assertTrue(messageHolds(error, "425"), String.valueOf(error));
        for (Throwable link = error; link != null; link = link.getCause()) {
            assertFalse(link instanceof NullPointerException, String.valueOf(error));
        }
        assertEquals(2, server.count("MDTM"));
        assertEquals(connects + 1, server.getConnects());
        assertEquals(0, fileSystem(file).getOpenClientCount());
    }

    @Test
    public void publicMoveStagesAfterDefiniteRenameRefusal() throws Exception {
        final FileObject source = resolve("/small.txt");
        final FileObject target = resolve("/moved.txt");
        server.reply("RNTO", null, 553, 1);
        FileObjectUtils.getAbstractFileObject(source).moveTo(target, new MoveOptions(true, null, null, null, null));
        assertArrayEquals(SMALL, Files.readAllBytes(new File(home, "moved.txt").toPath()));
        assertFalse(new File(home, "small.txt").exists());
        assertEquals(1, server.count("RETR", "small.txt"));
        assertEquals(1, server.count("STOR"));
        assertTrue(server.trace().stream().anyMatch(line -> line.matches("[0-9]+ STOR /?moved[.]txt[.][0-9a-z]{14}[.]part")));
        assertEquals(1, server.count("DELE", "small.txt"));
    }

    @Test
    public void transportReplyToACommandDiscardsTheClientAndIsNotSentAgain() throws Exception {
        final FileObject file = resolve("/a.txt");
        assertTrue(file.exists());
        assertTransportReplyDiscards("DELE", file::delete);
        assertTransportReplyDiscards("MKD", () -> resolve("/m").createFolder());
        final FileObject source = resolve("/small.txt");
        assertTrue(source.exists());
        assertTransportReplyDiscards("RNTO",
                () -> FileObjectUtils.getAbstractFileObject(source).moveTo(resolve("/moved.txt"), new MoveOptions(true, null, null, null, null)));
        final FileObject read = resolve("/d/f.txt");
        assertTrue(read.exists());
        assertTransportReplyDiscards("RETR", () -> read.getContent().getInputStream().close());
        assertTransportReplyDiscards("STOR", () -> resolve("/out.txt").getContent().getOutputStream().close());
    }

    // The command gets 425 once. The error is a transport error with the reply, the command is not sent again, and
    // the next command runs on a new login, because the client of the refused command was not given back.
    private void assertTransportReplyDiscards(final String command, final Executable action) throws Exception {
        final long sent = server.count(command);
        server.reply(command, null, FTPReply.CANNOT_OPEN_DATA_CONNECTION, 1);
        final FileSystemException error = assertThrows(FileSystemException.class, action, command);
        if ("RNTO".equals(command)) {
            assertTrue(error instanceof MoveFailedException);
            final MoveFailedException failure = (MoveFailedException) error;
            assertEquals(MovePhase.RENAME, failure.getPhase());
            assertTrue(failure.isConnectionLost());
            assertFalse(failure.isCommitConfirmed());
        }
        assertTrue(Faults.isTransport(error), command + " " + error);
        assertTrue(messageHolds(error, "425"), command + " " + error);
        assertEquals(sent + 1, server.count(command), command);
        final int connects = server.getConnects();
        final FileObject root = resolve("/");
        root.refresh();
        root.getChildren();
        assertEquals(connects + 1, server.getConnects(), command);
    }

    @Test
    public void abortBeforeTheHandoutReadsTheStateOfTheClientClosesItsSocket() throws Exception {
        abortDuringTheHandout(false);
    }

    @Test
    public void abortAfterTheHandoutReadsTheStateOfTheClientClosesItsSocket() throws Exception {
        abortDuringTheHandout(true);
    }

    // The idle slot holds a view of the real client whose state check waits for the test. The abort comes while a
    // second thread takes the client out of the slot, before or after that check. The original socket must be closed,
    // and the next command must run on a new login.
    private void abortDuringTheHandout(final boolean afterCheck) throws Exception {
        final FtpFileSystem fs = fileSystem(resolve("/"));
        final FtpClient real = fs.getClient();
        final CountDownLatch atCheck = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final FtpClient paused = (FtpClient) Proxy.newProxyInstance(FtpClient.class.getClassLoader(),
                new Class<?>[] {FtpClient.class}, (view, method, args) -> {
                    switch (method.getName()) {
                    case "hashCode":
                        return System.identityHashCode(view);
                    case "equals":
                        return view == args[0];
                    case "isConnected":
                        final boolean before = afterCheck && real.isConnected();
                        atCheck.countDown();
                        assertTrue(resume.await(5, TimeUnit.SECONDS));
                        return afterCheck ? before : real.isConnected();
                    default:
                        try {
                            return method.invoke(real, args);
                        } catch (final InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
        fs.putClient(paused);
        final FutureTask<FtpClient> next = new FutureTask<>(fs::getClient);
        new Thread(next).start();
        assertTrue(atCheck.await(5, TimeUnit.SECONDS));
        final int connects = server.getConnects();
        fs.abort();
        resume.countDown();
        final FtpClient acquired = next.get(5, TimeUnit.SECONDS);
        assertFalse(real.isAliveLocally());
        final long mkd = server.count("MKD");
        assertTrue(acquired.makeDirectory("after-abort"));
        assertEquals(mkd + 1, server.count("MKD"));
        assertEquals(connects + 1, server.getConnects());
        fs.putClient(acquired);
    }

    @Test
    public void clientGivenBackAfterAnAbortIsNotUsedAgain() throws Exception {
        final FtpFileSystem fs = fileSystem(resolve("/"));
        final FtpClient out = fs.getClient();
        fs.abort();
        fs.putClient(out);
        final int connects = server.getConnects();
        final FtpClient next = fs.getClient();
        assertNotSame(out, next);
        assertFalse(out.isAliveLocally());
        assertTrue(next.makeDirectory("after-return"));
        assertEquals(connects + 1, server.getConnects());
        fs.putClient(next);
        assertEquals(1, fs.getOpenClientCount());
    }

    @Test
    public void clientGivenBackAfterTheCloseIsNotKeptInTheIdleSlot() throws Exception {
        final FtpFileSystem fs = fileSystem(resolve("/"));
        final FtpClient out = fs.getClient();
        manager.closeFileSystem(fs);
        fs.putClient(out);
        assertFalse(out.isConnected());
        assertEquals(0, fs.getOpenClientCount());
    }

    private static FtpFileObject ftp(final FileObject file) throws FileSystemException {
        return (FtpFileObject) FileObjectUtils.getAbstractFileObject(file);
    }

    private static void waitFor(final BooleanSupplier condition) throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("The condition did not hold in 10 s");
            }
            Thread.sleep(20);
        }
    }

    @Test
    public void timeoutViewsIdentifyTheSameFtpEntryWithoutAnotherCommand() throws Exception {
        final byte[] previous = "previous target".getBytes(StandardCharsets.UTF_8);
        Files.write(home.toPath().resolve("a.txt"), previous);
        final FileSystemOptions changed = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setSoTimeout(changed, Duration.ofSeconds(6));
        final FtpFileObject source = (FtpFileObject) FileObjectUtils.getAbstractFileObject(resolve("/small.txt"));
        final FileObject alias = resolve("/small.txt", changed);
        final FileObject destination = resolve("/a.txt", changed);
        final int baseline = server.trace().size();
        assertEquals("SAME", String.valueOf(source.entryRelation(alias)));
        assertEquals("DISTINCT", String.valueOf(source.entryRelation(destination)));
        assertEquals(baseline, server.trace().size());
        source.moveTo(alias, new MoveOptions(true, null, null, null, null));
        assertTrue(assertThrows(MoveFailedException.class,
                () -> source.moveTo(alias, new MoveOptions(false, null, null, null, null))).isTargetExists());
        assertThrows(MoveFailedException.class, () -> source.moveTo(destination, alias));
        assertThrows(MoveFailedException.class, () -> source.moveTo(destination, destination));
        assertArrayEquals(SMALL, Files.readAllBytes(home.toPath().resolve("small.txt")));
        assertArrayEquals(previous, Files.readAllBytes(home.toPath().resolve("a.txt")));
    }
}
