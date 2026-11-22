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

package org.wso2.org.apache.commons.vfs2.provider.smb2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Client;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2Doubles.Factory;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

/**
 * The two events of the test build in the SMB2 handout: the answer of the local check of the idle client with its
 * local port and what was done, and the discard with its cause.
 */
@Tag("testbuild")
public class Smb2EventsTest {

    private static final String DIR = "vfs.testpoints.dir";

    @TempDir
    Path base;

    private Path copy;

    @BeforeEach
    public void setUp() {
        System.setProperty(DIR, base.toString());
        TestPoints.copy("unit", Test.class);
        TestPoints.wallMillis("setup");
        copy = base.resolve("unit.1");
        assertTrue(Files.exists(copy.resolve("copy")));
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(DIR);
    }

    @Test
    public void getClientTellsTheAnswerOfTheLocalCheckThePortAndWhatWasDone() throws Exception {
        final Client first = new Client();
        final Client second = new Client();
        final Client third = new Client();
        final Factory factory = new Factory(first.wrapper, second.wrapper, third.wrapper);
        final Smb2FileSystem fileSystem = Smb2Doubles.fileSystem(factory);

        fileSystem.getClient();
        fileSystem.putClient(first.wrapper);
        first.socket.close();
        fileSystem.getClient();
        fileSystem.getClient();
        fileSystem.discardClient(second.wrapper, new SMBRuntimeException(new TransportException("read timeout")));

        final List<Map<String, String>> events = events();
        assertEquals(4, events.size(), events.toString());
        assertEvent(events.get(0), "smb2.getclient", "alive", "true", "local-port", "4711", "action", "handed-out");
        assertEvent(events.get(1), "smb2.getclient", "alive", "false", "local-port", "4711", "action", "replaced");
        assertEvent(events.get(2), "smb2.getclient", "alive", "none", "local-port", "", "action", "created");
        assertEquals("smb2.discard", events.get(3).get("point"));
        assertEquals("4711", events.get(3).get("local-port"));
        assertTrue(events.get(3).get("cause").contains("read timeout"), events.get(3).toString());
    }

    private static void assertEvent(final Map<String, String> event, final String point, final String... values) {
        assertEquals(point, event.get("point"), event.toString());
        for (int i = 0; i < values.length; i += 2) {
            assertEquals(values[i + 1], event.get(values[i]), event.toString());
        }
    }

    private List<Map<String, String>> events() throws Exception {
        final List<Map<String, String>> found = new ArrayList<>();
        for (final String line : Files.readAllLines(copy.resolve("events.log"), StandardCharsets.UTF_8)) {
            final Map<String, String> fields = new LinkedHashMap<>();
            for (final String field : line.split("\t")) {
                final int equals = field.indexOf('=');
                fields.put(field.substring(0, equals), field.substring(equals + 1));
            }
            if (fields.get("point").startsWith("smb2.")) {
                found.add(fields);
            }
        }
        return found;
    }
}
