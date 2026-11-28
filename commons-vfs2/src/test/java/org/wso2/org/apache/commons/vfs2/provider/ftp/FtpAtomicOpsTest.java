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
package org.wso2.org.apache.commons.vfs2.provider.ftp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.net.ftp.FTPReply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wso2.org.apache.commons.vfs2.provider.local.CapabilityClockTest;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicCapabilities.Answer;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOps;
import org.wso2.org.apache.commons.vfs2.provider.MoveFailedException;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.Fault;
import org.wso2.org.apache.commons.vfs2.provider.Faults;
import org.wso2.org.apache.commons.vfs2.provider.MovePhase;
import org.wso2.org.apache.commons.vfs2.provider.StatNow;
import org.wso2.org.apache.commons.vfs2.provider.UnclearResultException;
import org.wso2.org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;

/**
 * The calls of AtomicOps on FTP and FTPS, on a scripted server with a command trace. No look
 * opens a file or changes into the name of a file.
 */
public class FtpAtomicOpsTest {

    private static final byte[] SMALL = "small file".getBytes(StandardCharsets.UTF_8);

    @TempDir
    File home;

    private ScriptedFtpServer server;

    private StandardFileSystemManager manager;

    @BeforeEach
    public void start() throws Exception {
        Files.write(new File(home, "a.csv").toPath(), SMALL);
        Files.write(new File(home, ".h.csv").toPath(), SMALL);
        new File(home, "d").mkdir();
        Files.write(new File(home, "d/f.txt").toPath(), SMALL);
        new File(home, "e").mkdir();
        new File(home, ".part").mkdir();
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

    private AtomicOps ops(final String path) throws FileSystemException {
        return AtomicOps.of(resolve(path));
    }

    // The commands after a mark of the trace, with no session number and no FEAT, which one client sends once.
    private List<String> since(final int mark) {
        final List<String> all = server.trace();
        return all.subList(mark, all.size()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                .filter(line -> !line.startsWith("FEAT")).collect(Collectors.toList());
    }

    private List<String> commands(final int mark) {
        return since(mark).stream().map(line -> line.split(" ")[0]).collect(Collectors.toList());
    }

    private long count(final int from, final int to, final String command) {
        return server.trace().subList(from, to).stream().filter(line -> line.split(" ")[1].equals(command)).count();
    }

    // The session of the last command with this name and an argument that holds the text.
    private int sessionOf(final String command, final String argument) {
        final List<String> all = server.trace();
        for (int i = all.size() - 1; i >= 0; i--) {
            final String[] parts = all.get(i).split(" ", 3);
            if (parts[1].equals(command) && (argument == null || parts.length > 2 && parts[2].contains(argument))) {
                return Integer.parseInt(parts[0]);
            }
        }
        throw new AssertionError(command + " was not sent");
    }

    // The commands that a session sent after the last command with this name and argument text.
    private List<String> after(final String command, final String argument) {
        final List<String> all = server.trace();
        final int session = sessionOf(command, argument);
        int last = -1;
        for (int i = 0; i < all.size(); i++) {
            final String[] parts = all.get(i).split(" ", 3);
            if (Integer.parseInt(parts[0]) == session && parts[1].equals(command)
                    && (argument == null || parts.length > 2 && parts[2].contains(argument))) {
                last = i;
            }
        }
        final List<String> later = new ArrayList<>();
        for (final String line : all.subList(last + 1, all.size())) {
            if (line.startsWith(session + " ")) {
                later.add(line);
            }
        }
        return later;
    }

    private void noContentRead(final int mark) {
        for (final String line : since(mark)) {
            assertFalse(line.startsWith("RETR"), line);
            assertFalse(line.startsWith("CWD") && (line.endsWith(".csv") || line.endsWith(".txt")), line);
        }
    }

    // The looks.

    @Test
    public void nameThatIsThereIsTrueWithOneMdtmAndNothingElse() throws Exception {
        final AtomicOps file = ops("/a.csv");
        final int mark = server.trace().size();
        assertTrue(file.existsNow());
        assertEquals(java.util.Arrays.asList("MDTM a.csv"), since(mark));
    }

    @Test
    public void mdtmRefusedForAFileThatIsThereIsTrueByTheListing() throws Exception {
        final AtomicOps file = ops("/a.csv");
        for (final int code : new int[] {550, 450, 500}) {
            server.reply("MDTM", "a.csv", code, 1);
            final int mark = server.trace().size();
            assertTrue(file.existsNow(), "MDTM " + code);
            assertEquals(1, commands(mark).stream().filter("MDTM"::equals).count());
            assertEquals(1, commands(mark).stream().filter("LIST"::equals).count());
            assertTrue(since(mark).stream().anyMatch(line -> line.startsWith("LIST -a")), String.valueOf(since(mark)));
            noContentRead(mark);
        }
    }

    @Test
    public void fileThatIsReallyGoneIsFalseWithMdtmAndOneListing() throws Exception {
        final AtomicOps file = ops("/none.csv");
        final int mark = server.trace().size();
        assertFalse(file.existsNow());
        assertEquals(1, commands(mark).stream().filter("MDTM"::equals).count());
        assertEquals(1, commands(mark).stream().filter("LIST"::equals).count());
        assertEquals(0, commands(mark).stream().filter("CWD"::equals).count());
        noContentRead(mark);
    }

    @Test
    public void nameInAnEmptyListingIsFalseOnlyAfterTheControl() throws Exception {
        final AtomicOps file = ops("/e/x.csv");
        // A good reply with no data, as a server gives for a folder that it can not open.
        server.reply("LIST", "e", FTPReply.CLOSING_DATA_CONNECTION, 2);
        final int mark = server.trace().size();
        assertFalse(file.existsNow());
        assertEquals(2, commands(mark).stream().filter("CWD"::equals).count(), String.valueOf(since(mark)));
        assertTrue(since(mark).contains("CWD e"), String.valueOf(since(mark)));
        noContentRead(mark);
    }

    @Test
    public void nameInAReallyEmptyFolderIsFalseAfterTheControlAlsoWhenTheListingShowsTheDotEntries() throws Exception {
        final AtomicOps file = ops("/e/x.csv");
        final int mark = server.trace().size();
        assertFalse(file.existsNow());
        // "." and ".." are not names in the folder, so they do not make the listing complete.
        assertEquals(2, commands(mark).stream().filter("CWD"::equals).count(), String.valueOf(since(mark)));
        noContentRead(mark);
    }

    @Test
    public void refusedListingThrowsAndNeverGivesFalse() throws Exception {
        final AtomicOps file = ops("/d/x.csv");
        server.reply("LIST", "-a", 550, 2);
        final int mark = server.trace().size();
        final FileSystemException error = assertThrows(FileSystemException.class, file::existsNow);
        assertFalse(error instanceof UnclearResultException);
        // The second form ran on the same client: a change into the folder, a listing with no path and the change
        // back.
        assertTrue(since(mark).contains("CWD d"), String.valueOf(since(mark)));
        assertEquals(2, commands(mark).stream().filter("LIST"::equals).count());
        assertEquals(0, commands(mark).stream().filter("USER"::equals).count());
    }

    @Test
    public void listingThatEndsWithATransportReplyOnEveryTryThrowsWithNoChangeOfFolder() throws Exception {
        final AtomicOps file = ops("/d/x.csv");
        server.reply("LIST", "d", FTPReply.TRANSFER_ABORTED, 10);
        final int mark = server.trace().size();
        final FileSystemException error = assertThrows(FileSystemException.class, file::existsNow);
        assertEquals(Fault.TRANSPORT, Faults.of(error));
        assertEquals(0, commands(mark).stream().filter("CWD"::equals).count());
        // One more try on a fresh connection inside the fast window.
        assertEquals(2, commands(mark).stream().filter("LIST"::equals).count());
        assertEquals(1, commands(mark).stream().filter("USER"::equals).count());
    }

    @Test
    public void nameBelowAFolderThatIsNotThereIsFalse() throws Exception {
        final AtomicOps file = ops("/none/x.csv");
        final int mark = server.trace().size();
        assertFalse(file.existsNow());
        noContentRead(mark);
    }

    @Test
    public void parentThatIsThereAndCanNotBeEnteredIsAnApplicationError() throws Exception {
        final AtomicOps file = ops("/d/x.csv");
        server.reply("LIST", "d", 550, 1);
        server.reply("CWD", "d", 550, 1);
        final FileSystemException error = assertThrows(FileSystemException.class, file::existsNow);
        assertEquals(Fault.APPLICATION, error.getFault());
    }

    @Test
    public void nameWithADotInFrontThatTheListingShowsIsTrue() throws Exception {
        final AtomicOps file = ops("/.h.csv");
        server.reply("MDTM", ".h.csv", 550, 1);
        assertTrue(file.existsNow());
    }

    @Test
    public void changeBackThatIsRefusedKeepsTheAnswerAndTheClientIsNotUsedAgain() throws Exception {
        final AtomicOps file = ops("/e/x.csv");
        server.reply("LIST", "e", FTPReply.CLOSING_DATA_CONNECTION, 2);
        server.reply("CWD", "/", 550, 1);
        final int mark = server.trace().size();
        assertFalse(file.existsNow());
        final int second = server.trace().size();
        assertFalse(file.existsNow());
        assertEquals(0, count(mark, second, "USER"));
        assertEquals(1, count(second, server.trace().size(), "USER"), String.valueOf(since(second)));
    }

    // The hidden listing of the second form is empty, and the change back is refused, while the option -a is not
    // trusted yet. The plain listing still decides, on a fresh client.

    // A file resolved from the root. No listing with the option -a has run yet, so the option is not trusted.
    private AtomicOps fromRoot(final String path) throws FileSystemException {
        return AtomicOps.of(resolve("/").resolveFile(path));
    }

    private void emptyHiddenListingAfterAChange(final boolean backRefused) {
        server.reply("LIST", "d", 550, 1);
        server.reply("LIST", "-a", FTPReply.CLOSING_DATA_CONNECTION, 1);
        if (backRefused) {
            server.reply("CWD", "/", 550, 1);
        }
    }

    @Test
    public void emptyHiddenListingWithTheChangeBackRefusedDoesNotHideAVisibleFile() throws Exception {
        final AtomicOps file = fromRoot("d/f.txt");
        server.reply("MDTM", "f.txt", 550, 1);
        emptyHiddenListingAfterAChange(true);
        assertTrue(file.existsNow());
        final int displaced = sessionOf("CWD", "/");
        assertTrue(after("CWD", "/").isEmpty(), String.valueOf(after("CWD", "/")));
        assertTrue(sessionOf("LIST", "d") != displaced);
        assertEquals(sessionOf("LIST", "d") + " LIST d", server.trace().get(server.trace().size() - 1));
        assertTrue(file.existsNow());
    }

    @Test
    public void emptyHiddenListingWithTheChangeBackDoneRunsThePlainListingOnTheSameClient() throws Exception {
        final AtomicOps file = fromRoot("d/f.txt");
        server.reply("MDTM", "f.txt", 550, 1);
        emptyHiddenListingAfterAChange(false);
        final int mark = server.trace().size();
        assertTrue(file.existsNow());
        assertEquals(0, count(mark, server.trace().size(), "USER"), String.valueOf(since(mark)));
        assertEquals(sessionOf("CWD", "/"), sessionOf("LIST", "d"));
    }

    @Test
    public void emptyHiddenListingWithTheChangeBackRefusedDoesNotHideAChild() throws Exception {
        // The hidden listing of the root is empty, so the option is not trusted, and the plain listing finds "d".
        server.reply("LIST", "-a", FTPReply.CLOSING_DATA_CONNECTION, 1);
        final FileObject folder = resolve("/d");
        assertTrue(folder.isFolder());
        emptyHiddenListingAfterAChange(true);
        assertEquals(Arrays.asList("f.txt"), Arrays.stream(folder.getChildren())
                .map(child -> child.getName().getBaseName()).collect(Collectors.toList()));
        assertTrue(after("CWD", "/").isEmpty(), String.valueOf(after("CWD", "/")));
        assertTrue(sessionOf("LIST", "d") != sessionOf("CWD", "/"));
    }

    @Test
    public void folderLookEntersTheFolderAndDoesNotList() throws Exception {
        final AtomicOps part = ops("/.part");
        final int mark = server.trace().size();
        assertTrue(part.folderExistsNow());
        assertEquals(java.util.Arrays.asList("PWD", "CWD", "CWD"), commands(mark));
        assertFalse(ops("/none").folderExistsNow());
        assertEquals(Fault.APPLICATION, assertThrows(FileSystemException.class,
                () -> ops("/a.csv").folderExistsNow()).getFault());
    }

    @Test
    public void confirmedFolderUsesItsOwnCheckedListingAndItsListedSize() throws Exception {
        final FileObject part = resolve("/.part");
        final AtomicOps ops = AtomicOps.of(part);
        assertTrue(ops.folderExistsNow());
        Files.write(new File(home, ".part/t.tmp").toPath(), SMALL);
        final int mark = server.trace().size();
        final Map<FileName, Long> sizes = new HashMap<>();
        final FileObject[] first = ops.listConfirmedFolder(part, sizes);
        assertEquals(1, first.length);
        assertEquals("t.tmp", first[0].getName().getBaseName());
        Files.write(new File(home, ".part/t.tmp").toPath(), new byte[1]);
        assertEquals(SMALL.length, sizes.get(first[0].getName()).longValue());
        final FileObject[] second = ops.listConfirmedFolder(part, sizes);
        assertEquals(1, sizes.get(second[0].getName()).longValue());
        assertEquals(Arrays.asList("LIST", "LIST"), commands(mark).stream()
                .filter(verb -> !verb.equals("PASV") && !verb.equals("PORT")).collect(Collectors.toList()));
        assertTrue(since(mark).stream().filter(c -> c.startsWith("LIST")).allMatch(c -> c.endsWith(".part")));
        server.reply("LIST", ".part", 426, 2);
        final FileSystemException cut = assertThrows(FileSystemException.class, () -> ops.listConfirmedFolder(part, sizes));
        assertEquals(Fault.TRANSPORT, Faults.of(cut));
    }

    @Test
    public void createFolderOnAFolderThatIsThereDoesNotFail() throws Exception {
        final FileObject part = resolve("/.part");
        server.reply("MKD", ".part", 550, 1);
        part.createFolder();
        assertTrue(new File(home, ".part").isDirectory());
    }

    @Test
    public void statReadsSizeAndMdtmAndNoListing() throws Exception {
        final AtomicOps file = ops("/a.csv");
        final int mark = server.trace().size();
        final StatNow found = file.statNow();
        assertTrue(found.isThere());
        assertEquals(SMALL.length, found.getSize());
        assertFalse(found.isFromListing());
        assertEquals(new File(home, "a.csv").lastModified() / 1000, found.getLastModifiedMillis() / 1000);
        assertEquals(java.util.Arrays.asList("SIZE", "MDTM"), commands(mark));
    }

    @Test
    public void statOfANameThatIsNotThereReadsTheListingAndNeverTakesAReplyAsNotThere() throws Exception {
        final AtomicOps file = ops("/none.csv");
        final int mark = server.trace().size();
        assertFalse(file.statNow().isThere());
        assertEquals(1, commands(mark).stream().filter("SIZE"::equals).count());
        assertEquals(1, commands(mark).stream().filter("MDTM"::equals).count());
        assertEquals(1, commands(mark).stream().filter("LIST"::equals).count());
        noContentRead(mark);
    }

    @Test
    public void statInTheTypeAsciiSendsNoSize() throws Exception {
        final FileSystemOptions options = new FileSystemOptions();
        FtpFileSystemConfigBuilder.getInstance().setFileType(options, FtpFileType.ASCII);
        final AtomicOps file = AtomicOps.of(resolve("/a.csv", options));
        final int mark = server.trace().size();
        final StatNow found = file.statNow();
        assertTrue(found.isThere());
        assertTrue(found.isFromListing());
        assertEquals(0, commands(mark).stream().filter("SIZE"::equals).count());
    }

    @Test
    public void nameThatMdtmShowsAndTheListingHidesHasNoSize() throws Exception {
        final AtomicOps file = ops("/a.csv");
        server.reply("SIZE", "a.csv", 550, 2);
        server.reply("LIST", null, FTPReply.CLOSING_DATA_CONNECTION, 4);
        final FileSystemException error = assertThrows(FileSystemException.class, file::statNow);
        assertEquals(Fault.APPLICATION, error.getFault());
        assertThrows(FileSystemException.class, file::statNow);
    }

    @Test
    public void serverWithNoMdtmIsReadFromTheListing() throws Exception {
        final AtomicOps file = ops("/a.csv");
        server.reply("FEAT", null, 211, 10);
        final int mark = server.trace().size();
        assertTrue(file.existsNow());
        assertEquals(0, commands(mark).stream().filter("MDTM"::equals).count());
        assertEquals(1, commands(mark).stream().filter("LIST"::equals).count());
        final StatNow found = file.statNow();
        assertTrue(found.isFromListing());
        assertEquals(SMALL.length, found.getSize());
    }

    // The operations.

    @Test
    public void rootIsItsOwnFolderInBothQueryFormsWithNoCommand() throws Exception {
        final FileObject root = resolve("/");
        final FileObject file = resolve("/a.csv");
        final int mark = server.trace().size();
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(root).atomicCapabilities().getReplacingRename());
        assertEquals(Answer.NO, AtomicOps.of(root).atomicCapabilities(file).getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(root).atomicCapabilities(file).getReplacingRename());
        assertEquals(Answer.NOT_KNOWN, AtomicOps.of(file).atomicCapabilities(root).getReplacingRename());
        assertEquals(mark, server.trace().size());
    }

    @Test
    public void exclusiveStepsAreUnsupportedWithNoCommand() throws Exception {
        final AtomicOps lock = ops("/x.lock");
        final FileObject target = resolve("/y.lock");
        final int mark = server.trace().size();
        assertEquals(AtomicOutcome.UNSUPPORTED, lock.createExclusive(SMALL, "s.lock", "p.lock").getOutcome());
        assertEquals(AtomicOutcome.UNSUPPORTED, lock.linkExclusive(target, "p.lock").getOutcome());
        assertEquals(AtomicOutcome.UNSUPPORTED, lock.publishExclusive(target, "p.lock").getOutcome());
        assertEquals(mark, server.trace().size());
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusiveCreate());
        assertEquals(Answer.NO, lock.atomicCapabilities().getExclusivePublish());
        assertEquals(Answer.NOT_KNOWN, lock.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void renameOntoAFreeNameIsDone() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        assertEquals(AtomicOutcome.DONE, ops("/t.tmp").renameReplacing(resolve("/t.csv"), null, null).getOutcome());
        assertArrayEquals(SMALL, Files.readAllBytes(new File(home, "t.csv").toPath()));
    }

    @Test
    public void refusedRnfrSendsNoRntoAndNoTest() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNFR", "t.tmp", 550, 1);
        final int mark = server.trace().size();
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertEquals(0, commands(mark).stream().filter(c -> c.equals("RNTO") || c.equals("STOR") || c.equals("DELE"))
                .count(), String.valueOf(since(mark)));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(home, "t.csv").toPath()));
        assertEquals(Answer.NOT_KNOWN, temp.atomicCapabilities().getReplacingRename());
    }

    @Test
    public void refusedRnfrOfASourceThatIsGoneIsGone() throws Exception {
        final AtomicOps gone = ops("/none.tmp");
        server.reply("RNFR", "none.tmp", 550, 1);
        assertEquals(AtomicOutcome.GONE, gone.renameReplacing(resolve("/t.csv"), null, null).getOutcome());
    }

    @Test
    public void serverThatAcceptsRnfrForAMissingNameRefusesAtRntoAndTheOutcomeIsRefused() throws Exception {
        // Apache FtpServer answers RNFR with 350 for any name and refuses only at RNTO. RNTO was sent, so the source
        // is not looked at, and a target that is not there can not be the reason.
        final AtomicOps gone = ops("/none.tmp");
        final int mark = server.trace().size();
        assertEquals(AtomicOutcome.REFUSED, gone.renameReplacing(resolve("/t.csv"), null, null).getOutcome());
        assertEquals(1, commands(mark).stream().filter("RNTO"::equals).count());
        assertEquals(0, commands(mark).stream().filter("STOR"::equals).count());
    }

    @Test
    public void refusedRntoWithNoTargetIsRefusedAndRunsNoTest() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNTO", "t.csv", 553, 1);
        final int mark = server.trace().size();
        assertEquals(AtomicOutcome.REFUSED, temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb")).getOutcome());
        assertEquals(0, commands(mark).stream().filter("STOR"::equals).count());
    }

    @Test
    public void refusedRntoWithTheTargetThereOnThisServerRunsTheTestOnceAndIsUnsupported() throws Exception {
        // Apache FtpServer refuses a rename onto an existing name, so the test finds a server that does not replace.
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        final int mark = server.trace().size();
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.UNSUPPORTED, result.getOutcome());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(home, "t.csv").toPath()));
        assertTrue(Files.exists(new File(home, "t.tmp").toPath()));
        assertEquals(1, server.count("STOR", "part/pa"));
        assertEquals(1, server.count("STOR", "part/pb"));
        assertEquals(0, server.trace().subList(mark, server.trace().size()).stream()
                .filter(line -> line.endsWith("DELE t.csv")).count());
        assertEquals(Answer.NO, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
        assertFalse(new File(home, ".part/pa").exists());
        assertFalse(new File(home, ".part/pb").exists());
        assertTrue(result.getLeftovers().isEmpty());
    }

    @Test
    public void serverThatReplacesIsRememberedAndTheOutcomeIsRefused() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNTO", "t.csv", 553, 1);
        // The rename of the test is answered as a server that replaces answers it.
        server.reply("RNTO", "pb", 250, 1);
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.getDetail().contains("553"), result.getDetail());
        assertEquals(Answer.YES, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(home, "t.csv").toPath()));
        assertEquals(0, server.count("STOR", "t.csv"));
    }

    @Test
    public void serverThatRefusesARenameOntoAnExistingNameIsUnsupportedAfterTheTestAndThenFromMemory()
            throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNTO", "t.csv", 553, 2);
        server.reply("RNTO", "pb", 553, 1);
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.UNSUPPORTED, result.getOutcome());
        assertEquals(Answer.NO, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
        final int mark = server.trace().size();
        assertEquals(AtomicOutcome.UNSUPPORTED, temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb")).getOutcome());
        assertEquals(0, commands(mark).stream().filter("STOR"::equals).count());
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(home, "t.csv").toPath()));
    }

    @Test
    public void connectionThatDiesInTheTestDecidesNothing() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNTO", "t.csv", 553, 1);
        server.drop("STOR", 1);
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertEquals(2, result.getLeftovers().size());
        assertEquals(Answer.NOT_KNOWN, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
    }

    @Test
    public void lostRntoReplyThrowsAndIsNotSentAgain() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        final AtomicOps temp = ops("/t.tmp");
        server.drop("RNTO", 1);
        // The rename may have worked. The call throws UnclearResultException in the phase COMMIT with the path of the
        // receiver, keeps the cause and sends nothing more.
        final UnclearResultException error = assertThrows(UnclearResultException.class,
                () -> temp.renameReplacing(resolve("/t.csv"), null, null));
        assertEquals(Fault.TRANSPORT, error.getFault());
        assertEquals(MovePhase.COMMIT, error.getPhase());
        assertEquals("/t.tmp", error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertTrue(error.getCause() != null);
        assertEquals(1, server.count("RNTO"));
        assertTrue(after("RNTO", "t.csv").isEmpty());
    }

    @Test
    public void lostReplyOfATakeAwayIsUnclearInThePhaseRename() throws Exception {
        Files.write(new File(home, "a.lock").toPath(), SMALL);
        final AtomicOps lock = ops("/a.lock");
        server.drop("RNTO", 1);
        final UnclearResultException error = assertThrows(UnclearResultException.class,
                () -> lock.takeAway(resolve("/a.x1.lock")));
        assertEquals(MovePhase.RENAME, error.getPhase());
        assertEquals(null, error.getTempPath());
        assertFalse(error.isOldTargetDeleted());
        assertTrue(error.getCause() != null);
        assertEquals(1, server.count("RNTO"));
    }

    // A transport reply to a step of a rename. The command did not run, so the outcome is REFUSED. The client is
    // retired, it gets no further command, and the next look takes a fresh client. commons net throws when it reads a
    // 421, and that reply still decides the step.

    @Test
    public void definiteRenameRefusalKeepsItsFaultAfterLostLook() throws Exception {
        final FileObject source = resolve("/a.csv");
        final FileObject target = resolve("/d/final.csv");
        assertTrue(source.exists());
        source.getParent().isWriteable();
        server.reply("RNTO", null, 553, 1);
        server.reply("LIST", "d", 425, 2);
        final MoveFailedException failure = assertThrows(MoveFailedException.class,
                () -> FileObjectUtils.getAbstractFileObject(source).moveTo(target, new MoveOptions(true, null, null, null, null)),
                () -> commands(0).toString());
        assertEquals(Fault.APPLICATION, Faults.of(failure));
        assertTrue(failure.isConnectionLost());
        assertTrue(failure.getCause().getMessage().contains("553"));
        assertEquals(1, server.count("RNTO"));
        assertEquals(0, server.count("STOR"));
        assertArrayEquals(SMALL, Files.readAllBytes(new File(home,"a.csv").toPath()));
        assertFalse(new File(home,"d/final.csv").exists());
    }

    @Test
    public void transportReplyToRnfrOrRntoRetiresTheClient() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "a.lock").toPath(), SMALL);
        for (final int code : new int[] {421, 425, 426}) {
            for (final String verb : new String[] {"RNFR", "RNTO"}) {
                server.reply(verb, null, code, 1);
                final AtomicResult rename = ops("/t.tmp").renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                        resolve("/.part/pb"));
                assertEquals(AtomicOutcome.REFUSED, rename.getOutcome(), verb + " " + code);
                assertTrue(rename.isConnectionLost(), verb + " " + code);
                assertEquals(Fault.TRANSPORT, rename.getFault());
                assertTrue(rename.getDetail().contains(String.valueOf(code)), rename.getDetail());
                final int lost = sessionOf(verb, null);
                assertTrue(after(verb, null).isEmpty(), verb + " " + code + " " + after(verb, null));
                assertTrue(ops("/a.csv").existsNow());
                assertTrue(sessionOf("MDTM", "a.csv") != lost, verb + " " + code);

                server.reply(verb, null, code, 1);
                final AtomicResult take = ops("/a.lock").takeAway(resolve("/a.x1.lock"));
                assertEquals(AtomicOutcome.REFUSED, take.getOutcome(), verb + " " + code);
                assertTrue(take.isConnectionLost());
                assertEquals(Fault.TRANSPORT, take.getFault());
                assertTrue(after(verb, null).isEmpty(), verb + " " + code + " " + after(verb, null));
                assertTrue(ops("/a.csv").existsNow());
                assertTrue(sessionOf("MDTM", "a.csv") != sessionOf(verb, null), verb + " " + code);
            }
        }
        assertTrue(new File(home, "t.tmp").exists());
        assertTrue(new File(home, "a.lock").exists());
        assertFalse(new File(home, "t.csv").exists());
        assertFalse(new File(home, "a.x1.lock").exists());
        assertEquals(0, server.count("STOR"));
        // Each rename sent its RNTO once at most: three codes, two calls each, one RNTO step each.
        assertEquals(6, server.count("RNTO"));
    }

    @Test
    public void disconnectWithNoReplyAtEitherStepStaysUnclear() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "a.lock").toPath(), SMALL);
        for (final String verb : new String[] {"RNFR", "RNTO"}) {
            server.drop(verb, 1);
            final UnclearResultException rename = assertThrows(UnclearResultException.class,
                    () -> ops("/t.tmp").renameReplacing(resolve("/t.csv"), null, null));
            assertEquals(MovePhase.COMMIT, rename.getPhase());
            assertTrue(ops("/a.csv").existsNow());
            server.drop(verb, 1);
            final UnclearResultException take = assertThrows(UnclearResultException.class,
                    () -> ops("/a.lock").takeAway(resolve("/a.x1.lock")));
            assertEquals(MovePhase.RENAME, take.getPhase());
            assertTrue(ops("/a.csv").existsNow());
        }
        assertTrue(new File(home, "t.tmp").exists());
        assertTrue(new File(home, "a.lock").exists());
    }

    @Test
    public void transportReplyToTheDeleteOfTheControlRetiresTheClientAndDecidesNothing() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        for (final int code : new int[] {425, 426}) {
            final AtomicOps temp = ops("/t.tmp");
            server.reply("DELE", "pb", code, 1);
            final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                    resolve("/.part/pb"));
            assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
            assertTrue(result.isConnectionLost());
            assertEquals(Arrays.asList(".part/pa", ".part/pb"), result.getLeftovers());
            assertTrue(after("DELE", "pb").isEmpty(), String.valueOf(after("DELE", "pb")));
            assertEquals(Answer.NOT_KNOWN, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
            assertTrue(ops("/a.csv").existsNow());
            assertTrue(sessionOf("MDTM", "a.csv") != sessionOf("DELE", "pb"));
            new File(home, ".part/pa").delete();
            new File(home, ".part/pb").delete();
        }
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(new File(home, "t.csv").toPath()));
    }

    @Test
    public void transportReplyToACleanupDeleteKeepsTheDecidedTest() throws Exception {
        Files.write(new File(home, "t.tmp").toPath(), SMALL);
        Files.write(new File(home, "t.csv").toPath(), new byte[] {1});
        final AtomicOps temp = ops("/t.tmp");
        server.reply("RNTO", "t.csv", 553, 1);
        // The rename of the test is answered as a server that replaces answers it.
        server.reply("RNTO", "pb", 250, 1);
        server.reply("DELE", "pb", 426, 1);
        final AtomicResult result = temp.renameReplacing(resolve("/t.csv"), resolve("/.part/pa"),
                resolve("/.part/pb"));
        assertEquals(AtomicOutcome.REFUSED, result.getOutcome());
        assertTrue(result.isConnectionLost());
        assertEquals(Arrays.asList(".part/pb"), result.getLeftovers());
        assertTrue(after("DELE", "pb").isEmpty(), String.valueOf(after("DELE", "pb")));
        assertEquals(Answer.YES, temp.atomicCapabilities(resolve("/t.csv")).getReplacingRename());
        assertTrue(ops("/a.csv").existsNow());
        assertTrue(sessionOf("MDTM", "a.csv") != sessionOf("DELE", "pb"));
    }

    @Test
    public void takeAwayIsDoneAndOfANameThatIsGoneIsGoneOrRefusedByTheServer() throws Exception {
        Files.write(new File(home, "a.lock").toPath(), SMALL);
        assertEquals(AtomicOutcome.DONE, ops("/a.lock").takeAway(resolve("/a.x1.lock")).getOutcome());
        assertTrue(new File(home, "a.x1.lock").exists());
        // This server accepts RNFR for a name that is gone and refuses at RNTO.
        assertEquals(AtomicOutcome.REFUSED, ops("/a.lock").takeAway(resolve("/a.x2.lock")).getOutcome());
        final AtomicOps gone = ops("/a.lock");
        server.reply("RNFR", "a.lock", 550, 1);
        assertEquals(AtomicOutcome.GONE, gone.takeAway(resolve("/a.x3.lock")).getOutcome());
        assertFalse(new File(home, "a.x2.lock").exists());
        assertFalse(new File(home, "a.x3.lock").exists());
    }

    @Test
    public void twoPartiesThatTakeOneLockAwayGetOneDoneAndTheLoserIsRecorded() throws Exception {
        final StandardFileSystemManager other = new StandardFileSystemManager();
        other.init();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final Map<AtomicOutcome, Integer> losers = new EnumMap<>(AtomicOutcome.class);
        try {
            for (int round = 0; round < 50; round++) {
                Files.write(new File(home, "r" + round + ".lock").toPath(), SMALL);
                final AtomicOps first = AtomicOps.of(manager.resolveFile(server.uri("ftp", "/r" + round + ".lock")));
                final AtomicOps second = AtomicOps.of(other.resolveFile(server.uri("ftp", "/r" + round + ".lock")));
                final FileObject awayA = manager.resolveFile(server.uri("ftp", "/r" + round + ".a.lock"));
                final FileObject awayB = other.resolveFile(server.uri("ftp", "/r" + round + ".b.lock"));
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final Future<AtomicResult> a = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return first.takeAway(awayA);
                });
                final Future<AtomicResult> b = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return second.takeAway(awayB);
                });
                final List<AtomicOutcome> outcomes = new ArrayList<>();
                outcomes.add(a.get(20, TimeUnit.SECONDS).getOutcome());
                outcomes.add(b.get(20, TimeUnit.SECONDS).getOutcome());
                assertEquals(1, outcomes.stream().filter(AtomicOutcome.DONE::equals).count(), "round " + round);
                final AtomicOutcome loser = outcomes.get(0) == AtomicOutcome.DONE ? outcomes.get(1) : outcomes.get(0);
                assertTrue(loser == AtomicOutcome.GONE || loser == AtomicOutcome.REFUSED, "round " + round);
                losers.merge(loser, 1, Integer::sum);
            }
        } finally {
            pool.shutdownNow();
            other.close();
        }
        System.out.println("FTP take away losers on the scripted server: " + losers);
    }

    // The listing of a folder.

    @Test
    public void refusedListingOfAFolderThrowsAndAnEmptyFolderIsOneListing() throws Exception {
        final FileObject root = resolve("/");
        assertTrue(root.getChildren().length > 0);
        final FileObject empty = resolve("/e");
        final int mark = server.trace().size();
        assertEquals(0, empty.getChildren().length);
        assertEquals(1, commands(mark).stream().filter("LIST"::equals).count());
        final FileObject folder = resolve("/d");
        server.reply("LIST", "-a", 550, 2);
        final FileSystemException error = assertThrows(FileSystemException.class, folder::getChildren);
        assertEquals(Fault.MISSING, Faults.of(error));
    }

    @Test
    public void folderThatCanNotBeEnteredCanNotBeListed() throws Exception {
        final FileObject folder = resolve("/d");
        server.reply("LIST", "d", 550, 1);
        server.reply("CWD", "d", 550, 1);
        assertThrows(FileSystemException.class, folder::getChildren);
    }

    @Test
    public void ftpsListingWithPassiveModeRefusedGivesTheCheckedReplyAndKeepsTheConnection() throws Exception {
        server.close();
        server = ScriptedFtpServer.tls(home, false);
        final FileSystemOptions options = new FileSystemOptions();
        FtpsFileSystemConfigBuilder.getInstance().setPassiveMode(options, true);
        final FileObject folder = manager.resolveFile(server.uri("ftps", "/d"), options);
        assertTrue(folder.exists());
        final FtpFileSystem fileSystem = (FtpFileSystem) folder.getFileSystem();
        server.reply("PASV", null, 502, 2);
        server.reply("EPSV", null, 502, 2);
        final int users = (int) server.count("USER");
        final FileSystemException error = assertThrows(FileSystemException.class, folder::getChildren);
        boolean replied = false;
        for (Throwable link = error; link != null; link = link.getCause()) {
            replied |= String.valueOf(link.getMessage()).contains("502");
        }
        assertTrue(replied, String.valueOf(error));
        assertEquals(Fault.APPLICATION, Faults.of(error));
        // The client is not counted as broken: it went back, and the next call uses it with no new login.
        assertEquals(0, fileSystem.getHandedOutCount());
        assertEquals(1, fileSystem.getOpenClientCount());
        assertTrue(AtomicOps.of(manager.resolveFile(server.uri("ftps", "/a.csv"), options)).existsNow());
        assertEquals(users, server.count("USER"));
    }

    @Test
    public void noReplacePersistsAfterAnHour() throws Exception {
        final FileObject file = resolve("/a.csv");
        final FtpFileSystem fs = (FtpFileSystem)file.getFileSystem();
        fs.markNoReplace(file.getName().getParent().getPath());
        final AtomicOps ops = AtomicOps.of(file);
        final int calls = server.trace().size();
        CapabilityClockTest.assertPersistent(() -> {
            assertEquals(Answer.NO, ops.atomicCapabilities().getExclusivePublish());
            return ops.atomicCapabilities(file).getReplacingRename();
        });
        assertEquals(calls, server.trace().size());
    }
}
