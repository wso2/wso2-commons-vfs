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
package org.wso2.vfs.shared.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.commons.logging.impl.SimpleLog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileProvider;

public class LoggingTest {
    @Test
    public void slashInPassword() {
        assertEquals("sftp://rig:***@host/in", UriMask.mask("sftp://rig:pa/ss@host/in"));
    }

    @Test
    public void whiteSpaceInPassword() {
        assertEquals("sftp://user:***@host/in", UriMask.mask("sftp://user:my pass@host/in"));
        assertEquals("sftp://user:***@host/in", UriMask.mask("sftp://user:pass @host/in"));
        assertEquals("Could not move sftp://u:***@h/a to ftp://x:***@g/b",
                UriMask.mask("Could not move sftp://u:p q@h/a to ftp://x:y@g/b"));
        // An address with no password stays as it is, also when the text after it holds an @.
        assertEquals("sftp://host:22/in held by user@example.com",
                UriMask.mask("sftp://host:22/in held by user@example.com"));
    }

    // Runs the work on a thread with a small stack, so a pattern that recurses once per word fails here.
    private static String onSmallStack(final Callable<String> work) throws InterruptedException {
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread thread = new Thread(null, () -> {
            try {
                result.set(work.call());
            } catch (final Throwable error) {
                result.set(error);
            }
        }, "small-stack", 256 * 1024);
        thread.start();
        thread.join();
        if (result.get() instanceof Throwable) {
            throw new AssertionError("The mask failed on a small stack", (Throwable) result.get());
        }
        return (String) result.get();
    }

    @Test
    public void manyWordsWithNoSlashAfterAnAddressDoNotOverflowASmallStack() throws Exception {
        final StringBuilder text = new StringBuilder("Could not connect to sftp://host:22");
        for (int i = 0; i < 5000; i++) {
            text.append(" word");
        }
        assertEquals(text.toString(), onSmallStack(() -> UriMask.mask(text.toString())));
    }

    // One table for the log mask and the error of a file system. Each row gives the input and the wanted result, and
    // both classes give the same. The rows that stay as they are hold no password.
    private static final String[][] MASK_TABLE = {
        {"sftp://user:my pass@host/in/a.txt", "sftp://user:***@host/in/a.txt"},
        {"sftp://user:pass @host/in", "sftp://user:***@host/in"},
        {"sftp://user: pass@host/in", "sftp://user:***@host/in"},
        {"sftp://user:pa/ss@host/in", "sftp://user:***@host/in"},
        {"sftp://rig:p@ss@host:22/in", "sftp://rig:***@host:22/in"},
        {"sftp://user:p@a/ss@host/in", "sftp://user:***@host/in"},
        {"sftp://user:p@x y@host/in", "sftp://user:***@host/in"},
        {"smb2://John Smith:pw@server/share/in", "smb2://John Smith:***@server/share/in"},
        {"smb2://DOM;John Smith:p w@server/share/in", "smb2://DOM;John Smith:***@server/share/in"},
        {"Could not move sftp://u:p q@h/a to ftp://x:y@g/b", "Could not move sftp://u:***@h/a to ftp://x:***@g/b"},
        {"copy sftp://u:p@host1 to sftp://u2:p2@host2/x", "copy sftp://u:***@host1 to sftp://u2:***@host2/x"},
        {"sftp://user:pw@[fe80::1]/in", "sftp://user:***@[fe80::1]/in"},
        {"sftp://host:22/in held by user@example.com", "sftp://host:22/in held by user@example.com"},
        {"http://host:8080/path and mail a@b.c", "http://host:8080/path and mail a@b.c"},
        {"sftp://user:***@host/in", "sftp://user:***@host/in"},
        // A known limit: a password with white space and a slash together is not masked.
        {"sftp://user:my pa/ss@host/in", "sftp://user:my pa/ss@host/in"},
    };

    @Test
    public void theLogMaskAndTheErrorOfAFileSystemGiveTheSameResult() throws Exception {
        for (final String[] row : MASK_TABLE) {
            assertEquals(row[1], UriMask.mask(row[0]), row[0]);
            assertEquals(row[1], new FileSystemException("vfs.provider/rename.error", row[0]).getInfo()[0], row[0]);
        }
        final StringBuilder text = new StringBuilder("Could not connect to sftp://host:22");
        for (int i = 0; i < 5000; i++) {
            text.append(" word");
        }
        assertEquals(text.toString(), onSmallStack(() -> UriMask.mask(text.toString())));
        assertEquals(text.toString(), onSmallStack(() -> new FileSystemException("vfs.provider/rename.error",
                text.toString()).getInfo()[0]));
    }

    @Test
    public void atInPassword() {
        assertEquals("sftp://rig:***@host:22/in", UriMask.mask("sftp://rig:p@ss@host:22/in"));
    }

    @Test
    public void bothUrisInText() {
        assertEquals("from sftp://rig:***@h1/in to ftp://ftpuser:***@h2/out",
                UriMask.mask("from sftp://rig:a@h1/in to ftp://ftpuser:b@h2/out"));
    }

    @Test
    public void queryOptions() {
        assertEquals("sftp://h/in?transport.vfs.SFTPIdentityPassPhrase=***&proxyPassword=***&Secret=***&apiKey=***&a=1",
                UriMask.mask("sftp://h/in?transport.vfs.SFTPIdentityPassPhrase=x1&proxyPassword=x2&Secret=x3&apiKey=x4&a=1"));
        assertEquals("ftp://h/in?vfs.passive=true&proxyPassword=***&passphrase=***",
                UriMask.mask("ftp://h/in?vfs.passive=true&proxyPassword=x1&passphrase=x2"));
    }

    @Test
    public void ipv6Hosts() {
        assertEquals("sftp://rig:***@[::1]:2222/in smb2://u:***@[fe80::1%25eth0]/share",
                UriMask.mask("sftp://rig:pw@[::1]:2222/in smb2://u:pw@[fe80::1%25eth0]/share"));
    }

    @Test
    public void quotedHostsAndUriDelimitersInsideSecrets() {
        assertEquals("See 'sftp://u:***@[::1]'", UriMask.mask("See 'sftp://u:Synthetic@[::1]'"));
        assertEquals("sftp://u:***@host/path", UriMask.mask("sftp://u:Synthetic://path@host/path"));
        assertEquals("sftp://u@domain:***@host/file", UriMask.mask("sftp://u@domain:Synthetic@host/file"));
        assertEquals("route?to=sftp://host/in?pass=***&ok=1",
                UriMask.mask("route?to=sftp://host/in?pass=Synthetic&ok=1"));
        assertEquals("proxy?pass=***&safe=yes", UriMask.mask("proxy?pass=sftp://other-host/value&safe=yes"));
    }

    @Test
    public void friendlySmbNameKeepsPathAndMasksQuery() throws Exception {
        final FileName name;
        try (DefaultFileSystemManager manager = new DefaultFileSystemManager()) {
            manager.addProvider("smb2", new Smb2FileProvider());
            manager.init();
            name = manager.resolveURI("smb2://user:SyntheticUser@host/share/file?proxyPassword=SyntheticQuery&safe=yes");
        }
        final Lines sink = new Lines();
        NamedLog.connection(sink, "share", new RepeatLimiter()).warn(UriMask.friendly(name));
        assertTrue(sink.lines.get(0).contains("/share/file?proxyPassword=***&safe=yes"));
        assertFalse(sink.lines.get(0).contains("Synthetic"));
    }

    @Test
    public void everyLogLevelMasksAndNamesEveryLine() {
        final Lines sink = new Lines();
        final NamedLog inbound = NamedLog.inbound(sink, "in", new RepeatLimiter());
        final NamedLog connection = NamedLog.connection(sink, "out", new RepeatLimiter());
        final String uri = "sftp://unit:Synthetic/One@Two@host/file?apiKey=SyntheticThree&safe=yes";
        inbound.debug(uri + "\n" + uri);
        inbound.warn(uri);
        inbound.warn(uri);
        connection.error(uri);
        connection.error(uri);
        assertEquals(3, sink.lines.size());
        final String safe = "sftp://unit:***@host/file?apiKey=***&safe=yes";
        assertEquals("DEBUG [inbound:in] " + safe + "\n[inbound:in] " + safe, sink.lines.get(0));
        assertEquals("WARN [inbound:in] " + safe, sink.lines.get(1));
        assertEquals("ERROR [connection:out] " + safe, sink.lines.get(2));
    }

    @Test
    public void missingPathCanWarnAgainAfterRecovery() {
        final RepeatLimiter limiter = new RepeatLimiter();
        assertTrue(limiter.once("a"));
        assertFalse(limiter.once("a"));
        assertTrue(limiter.once("b"));
        limiter.clear("a");
        assertTrue(limiter.once("a"));
        assertFalse(limiter.once("b"));
    }

    @Test
    public void retainedTextsAreBounded() {
        final RepeatLimiter limiter = new RepeatLimiter();
        for (int number = 0; number < 200; number++) {
            assertEquals("text" + number, limiter.admit("text" + number));
        }
        assertNull(limiter.admit("text0"));
        assertNull(limiter.admit("new"));
        assertNull(limiter.admit("text0"));
    }

    @Test
    @Tag("testbuild")
    public void repeatUsesOnlyItsMonotonicClock(@TempDir final Path root) throws Exception {
        final String property = "vfs.testpoints.dir";
        final String previous = System.getProperty(property);
        System.setProperty(property, root.toString());
        try {
            final RepeatLimiter limiter = new RepeatLimiter();
            assertEquals("down", limiter.admit("down"));
            for (int number = 1; number < 200; number++) {
                limiter.admit("other" + number);
            }
            assertNull(limiter.admit("overflow"));
            final Path copy;
            try (Stream<Path> children = Files.list(root)) {
                copy = children.filter(Files::isDirectory).findFirst().get();
            }
            Files.write(copy.resolve("clock.repeat.wall"), "300010".getBytes(StandardCharsets.UTF_8));
            assertNull(limiter.admit("down"));
            assertNull(limiter.admit("down"));
            Files.write(copy.resolve("clock.repeat.mono"), "300010".getBytes(StandardCharsets.UTF_8));
            assertEquals("down (skipped 2)", limiter.admit("down"));
            assertEquals("overflow", limiter.admit("overflow"));
            assertNull(limiter.admit("overflow"));
            assertNull(limiter.admit("down"));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    public static final class Lines extends SimpleLog {
        private static final long serialVersionUID = 1L;
        public final List<String> lines = new ArrayList<>();

        public Lines() {
            super("test");
            setLevel(LOG_LEVEL_DEBUG);
        }

        @Override
        protected void log(final int level, final Object message, final Throwable cause) {
            assertNull(cause);
            final String[] levels = {"ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"};
            lines.add(levels[level] + " " + message);
        }
    }
}
