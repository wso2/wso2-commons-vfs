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
package org.wso2.vfs.shared.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

public class TempNamesTest {
    @Test
    public void timeTargetAndOwnershipRoundTrip() {
        for (final String target : new String[] {"a.b.csv", ".hidden", "vfsprobe", "a.b.fail"}) {
            final long before = Instant.now().getEpochSecond();
            final String suffix = target.endsWith(".fail") ? ".next" : ".part";
            final TempName attempt = TempNames.begin(target, suffix);
            try {
                final TempName parsed = TempNames.parse(attempt.name(), suffix);
                assertEquals(target, parsed.targetName());
                assertEquals(attempt.createdSeconds(), parsed.createdSeconds());
                assertTrue(parsed.createdSeconds() >= before && parsed.createdSeconds() <= Instant.now().getEpochSecond());
                assertTrue(attempt.name().startsWith(target + "."));
                assertTrue(TempNames.inUse(attempt.name()));
                parsed.end();
                assertTrue(TempNames.inUse(attempt.name()));
            } finally {
                attempt.end();
            }
            assertFalse(TempNames.inUse(attempt.name()));
        }
    }

    // A name whose creation second is older than the floor is a foreign file, even when its shape fits.
    @Test
    public void nameWithACreationSecondBelowTheFloorIsNotOurs() {
        assertNull(TempNames.parse("x.0t85rzzaaaaaaa.part", ".part"));
        assertNull(TempNames.parse("x.0000000aaaaaaa.part", ".part"));
        assertNotNull(TempNames.parse("x.0t85s00aaaaaaa.part", ".part"));
    }

    @Test
    public void parsesOnlyFullAsciiTokens() {
        assertEquals(TempNames.FLOOR_SECONDS, TempNames.parse("x.0t85s00aaaaaaa.part", ".part").createdSeconds());
        assertEquals(78364164095L, TempNames.parse("x.zzzzzzzaaaaaaa.part", ".part").createdSeconds());
        for (final String name : new String[] {"a.part", "x.abcd.part", ".0000000aaaaaaa.part",
                "x.0000000Aaaaaaa.part", "x.0000000aaaaaa!.part", "x.0000000aaaaaaa.next",
                "x.0000000aaaaaa\uFF10.part", "x.0000000aaaaaaaa.part"}) {
            assertNull(TempNames.parse(name, ".part"));
        }
    }

    @Test
    public void simultaneousAttemptsHaveIndependentNames() {
        final Set<TempName> attempts = IntStream.range(0, 128).parallel()
                .mapToObj(number -> TempNames.begin("same", ".partial")).collect(Collectors.toSet());
        final Set<String> names = new HashSet<>();
        try {
            for (final TempName attempt : attempts) {
                assertTrue(names.add(attempt.name()));
                assertTrue(TempNames.inUse(attempt.name()));
            }
            assertEquals(128, names.size());
        } finally {
            attempts.forEach(TempName::end);
        }
        assertFalse(names.stream().anyMatch(TempNames::inUse));
    }
}
