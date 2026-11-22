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
package org.wso2.org.apache.commons.vfs2.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Named test points. This is the form of the test build. Files in the folder of the system property
 * {@code vfs.testpoints.dir} drive it. With no property it does nothing, like the release form.
 */
public final class TestPoints {

    /**
     * A registered snapshot supplier.
     */
    public interface Handle {

        /**
         * Removes the registration of this handle.
         */
        void remove();
    }

    /**
     * A fault that a test asked for at a point.
     */
    public interface Fault {

        /**
         * @return the token of the fault.
         */
        String token();

        /**
         * @return the arguments of the fault file.
         */
        Map<String, String> arguments();
    }

    private static final String DIR_PROPERTY = "vfs.testpoints.dir";
    private static final long LOOK_MS = 10;
    private static final long DEFAULT_WAIT_MS = 120_000;
    private static final long SLICE_MAX_MS = 1_000;
    private static final long SNAPSHOT_LOOK_MS = 500;

    private static final Object NAME_LOCK = new Object();
    private static boolean named;
    private static String copyName;
    private static Class<?>[] copyAnchors = new Class<?>[0];
    private static volatile Root root;

    private static final Object FAULT_LOCK = new Object();

    private static final Object SNAPSHOT_LOCK = new Object();
    private static final Map<List<String>, Registration> SUPPLIERS = new LinkedHashMap<>();
    private static Thread snapshotThread;

    private TestPoints() {
    }

    public static boolean enabled() {
        final String dir = System.getProperty(DIR_PROPERTY);
        return dir != null && !dir.isEmpty();
    }

    public static Map<String, String> hold(final String point, final String key, final String... values) {
        final Root current = root();
        if (current == null || Files.exists(current.folder.resolve("release.all"))) {
            return Collections.emptyMap();
        }
        final Control control = current.select(point, key, ".hold", values);
        if (control == null) {
            return Collections.emptyMap();
        }
        final String base = base(point, key);
        final int n = current.reach(point, key, base, values);
        final Path release = current.folder.resolve(base + "." + n + ".release");
        final Path all = current.folder.resolve("release.all");
        final long start = System.nanoTime();
        final long waitNanos = TimeUnit.MILLISECONDS.toNanos(control.waitMs);
        String end;
        while (true) {
            if (!Files.exists(control.file)) {
                end = "released";
                break;
            }
            if (Files.exists(release)) {
                end = "released-one";
                break;
            }
            if (Files.exists(all)) {
                end = "release-all";
                break;
            }
            final long leftNanos = waitNanos - (System.nanoTime() - start);
            if (leftNanos <= 0) {
                end = "timeout";
                break;
            }
            try {
                Thread.sleep(Math.min(LOOK_MS, TimeUnit.NANOSECONDS.toMillis(leftNanos) + 1));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                end = "interrupt";
                break;
            }
        }
        current.append("hold-end", key, "at=" + point, "n=" + n, "end=" + end);
        return control.arguments;
    }

    public static Map<String, String> arguments(final String point, final String key, final String... values) {
        final Root current = root();
        if (current == null || Files.exists(current.folder.resolve("release.all"))) {
            return Collections.emptyMap();
        }
        final Control control = current.select(point, key, ".hold", values);
        return control == null ? Collections.emptyMap() : control.arguments;
    }

    public static Fault fault(final String point, final String key, final String... values) {
        final Root current = root();
        if (current == null) {
            return null;
        }
        // Readers of fault files take turns. So a fault with once goes to one caller, also when a test arms it again.
        synchronized (FAULT_LOCK) {
            final Control control = current.select(point, key, ".fault", values);
            if (control == null || control.token == null) {
                return null;
            }
            if (control.once) {
                try {
                    Files.delete(control.file);
                } catch (final IOException e) {
                    // The file is gone, so no caller gets the fault from it again.
                    return null;
                }
            }
            return new GivenFault(control.token, control.arguments);
        }
    }

    public static void event(final String point, final String key, final String... values) {
        final Root current = root();
        if (current != null) {
            current.append(point, key, values);
        }
    }

    public static Handle snapshot(final String name, final String key, final Supplier<String> supplier) {
        final Root current = root();
        if (current == null) {
            return () -> {
            };
        }
        final Registration registration = new Registration(name, key, supplier);
        synchronized (SNAPSHOT_LOCK) {
            SUPPLIERS.put(registration.id, registration);
            if (snapshotThread == null) {
                snapshotThread = new Thread(TestPoints::answerAsks, "testpoints-snapshot-" + current.name);
                snapshotThread.setDaemon(true);
                snapshotThread.start();
            }
        }
        return () -> {
            synchronized (SNAPSHOT_LOCK) {
                if (SUPPLIERS.get(registration.id) == registration) {
                    SUPPLIERS.remove(registration.id);
                    SNAPSHOT_LOCK.notifyAll();
                }
            }
        };
    }

    public static long wallMillis(final String clock) {
        final long now = System.currentTimeMillis();
        final Root current = root();
        return current == null ? now : now + current.offset(clock, "wall");
    }

    public static long nanoTime(final String clock) {
        final long now = System.nanoTime();
        final Root current = root();
        return current == null ? now : now + TimeUnit.MILLISECONDS.toNanos(current.offset(clock, "mono"));
    }

    public static long slice(final String clock, final long millis) {
        return enabled() ? Math.min(millis, SLICE_MAX_MS) : millis;
    }

    public static void copy(final String name, final Class<?>... anchors) {
        synchronized (NAME_LOCK) {
            if (named) {
                return;
            }
            named = true;
            copyName = name;
            copyAnchors = anchors == null ? new Class<?>[0] : anchors.clone();
        }
        root();
    }

    private static Root root() {
        final String dir = System.getProperty(DIR_PROPERTY);
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        Root current = root;
        if (current == null || !current.dir.equals(dir)) {
            synchronized (NAME_LOCK) {
                current = root;
                if (current == null || !current.dir.equals(dir)) {
                    current = claim(dir);
                    root = current;
                }
            }
        }
        return current.folder == null ? null : current;
    }

    // Called under NAME_LOCK. A copy that nobody named takes the identity hash of its class loader.
    private static Root claim(final String dir) {
        named = true;
        if (copyName == null) {
            copyName = "copy-" + loaderHash();
        }
        try {
            final Path base = Paths.get(dir);
            for (int g = 1;; g++) {
                final Path folder = base.resolve(copyName + "." + g);
                try {
                    Files.createDirectories(folder);
                    Files.createFile(folder.resolve("copy"));
                } catch (final FileAlreadyExistsException e) {
                    continue;
                }
                final Root claimed = new Root(dir, folder, copyName, g, pid());
                claimed.append("copy-started", null, copyStarted());
                return claimed;
            }
        } catch (final IOException | RuntimeException e) {
            // The folder can not be used. The hooks of this copy then do nothing.
            return new Root(dir, null, copyName, 0, null);
        }
    }

    private static String[] copyStarted() {
        final List<String> values = new ArrayList<>();
        values.add("loader=" + loaderHash());
        final List<Class<?>> classes = new ArrayList<>();
        classes.add(TestPoints.class);
        for (final Class<?> anchor : copyAnchors) {
            if (anchor != null) {
                classes.add(anchor);
            }
        }
        for (final Class<?> type : classes) {
            final String[] jar = jarOf(type);
            values.add("jar." + type.getName() + "=" + jar[0]);
            values.add("sha256." + type.getName() + "=" + jar[1]);
        }
        return values.toArray(new String[0]);
    }

    // The path and the SHA256 of the jar that the class came from. A folder or an unknown source has no hash.
    private static String[] jarOf(final Class<?> type) {
        final CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return new String[] {"-", "-"};
        }
        final URL location = source.getLocation();
        try {
            final Path path = Paths.get(location.toURI());
            if (!Files.isRegularFile(path)) {
                return new String[] {path.toString(), "-"};
            }
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                final byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return new String[] {path.toString(), hex(digest.digest())};
        } catch (final Exception e) {
            return new String[] {location.toString(), "-"};
        }
    }

    private static String loaderHash() {
        return Integer.toHexString(System.identityHashCode(TestPoints.class.getClassLoader()));
    }

    private static String pid() {
        final String name = ManagementFactory.getRuntimeMXBean().getName();
        final int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }

    private static String base(final String point, final String key) {
        return key == null ? point : point + "@k" + keyHash(key);
    }

    private static String keyHash(final String key) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder text = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            text.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return text.toString();
    }

    private static String escape(final String value) {
        return String.valueOf(value).replace("%", "%25").replace("\t", "%09").replace("\n", "%0A")
            .replace("\r", "%0D");
    }

    private static Map<String, String> valuesOf(final String[] values) {
        final Map<String, String> given = new HashMap<>();
        if (values != null) {
            for (final String value : values) {
                if (value == null) {
                    continue;
                }
                final int eq = value.indexOf('=');
                final String name = eq < 0 ? value : value.substring(0, eq);
                if (!given.containsKey(name)) {
                    given.put(name, eq < 0 ? "" : value.substring(eq + 1));
                }
            }
        }
        return given;
    }

    private static void answerAsks() {
        try {
            while (true) {
                synchronized (SNAPSHOT_LOCK) {
                    if (SUPPLIERS.isEmpty()) {
                        snapshotThread = null;
                        return;
                    }
                }
                final Root current = root();
                if (current != null) {
                    current.answer();
                }
                synchronized (SNAPSHOT_LOCK) {
                    if (SUPPLIERS.isEmpty()) {
                        snapshotThread = null;
                        return;
                    }
                    SNAPSHOT_LOCK.wait(SNAPSHOT_LOOK_MS);
                }
            }
        } catch (final InterruptedException e) {
            // Nobody interrupts this thread. It ends, and the next registration starts a new one.
        } finally {
            synchronized (SNAPSHOT_LOCK) {
                if (snapshotThread == Thread.currentThread()) {
                    snapshotThread = null;
                }
            }
        }
    }

    private static List<Registration> registered(final String name, final String hash) {
        final List<Registration> found = new ArrayList<>();
        synchronized (SNAPSHOT_LOCK) {
            for (final Registration registration : SUPPLIERS.values()) {
                if (!registration.name.equals(name)) {
                    continue;
                }
                if (hash != null && (registration.key == null || !keyHash(registration.key).equals(hash))) {
                    continue;
                }
                found.add(registration);
            }
        }
        return found;
    }

    /**
     * The state of one copy in one folder. A new value of the property gives a new folder and a new state.
     */
    private static final class Root {

        private final String dir;
        private final Path folder;
        private final String name;
        private final int g;
        private final String pid;
        private final Object lock = new Object();
        private final Map<String, Integer> reached = new HashMap<>();
        private final Map<String, Integer> snaps = new HashMap<>();
        private long seq;

        Root(final String dir, final Path folder, final String name, final int g, final String pid) {
            this.dir = dir;
            this.folder = folder;
            this.name = name;
            this.g = g;
            this.pid = pid;
        }

        void append(final String point, final String key, final String... values) {
            synchronized (lock) {
                write(line(point, key, values));
            }
        }

        // Writes the reached file and the event hold-reached with the same line. Returns the number of the file.
        int reach(final String point, final String key, final String base, final String[] values) {
            synchronized (lock) {
                final Integer last = reached.get(point);
                final int n = last == null ? 1 : last + 1;
                reached.put(point, n);
                final List<String> fields = new ArrayList<>();
                fields.add("at=" + point);
                fields.add("n=" + n);
                if (values != null) {
                    fields.addAll(Arrays.asList(values));
                }
                final String line = line("hold-reached", key, fields.toArray(new String[0]));
                place(base + "." + n + ".reached", line);
                write(line);
                return n;
            }
        }

        Control select(final String point, final String key, final String suffix, final String[] values) {
            final boolean fault = ".fault".equals(suffix);
            if (key != null) {
                final Control keyed = Control.read(folder.resolve(base(point, key) + suffix), fault);
                if (keyed != null && keyed.selects(this, point, key, values)) {
                    return keyed;
                }
            }
            final Control any = Control.read(folder.resolve(point + suffix), fault);
            if (any != null && any.selects(this, point, key, values)) {
                return any;
            }
            return null;
        }

        long offset(final String clock, final String kind) {
            final Path file = folder.resolve("clock." + clock + "." + kind);
            if (!Files.exists(file)) {
                return 0;
            }
            try {
                final String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                return text.isEmpty() ? 0 : Long.parseLong(text);
            } catch (final IOException | NumberFormatException e) {
                // A clock file that can not be read moves nothing.
                return 0;
            }
        }

        void answer() {
            final List<Path> asks = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.ask")) {
                for (final Path ask : stream) {
                    asks.add(ask);
                }
            } catch (final IOException e) {
                return;
            }
            for (final Path ask : asks) {
                final String file = ask.getFileName().toString();
                final String asked = file.substring(0, file.length() - ".ask".length());
                final int at = asked.lastIndexOf("@k");
                final String snapshot = at < 0 ? asked : asked.substring(0, at);
                final String hash = at < 0 ? null : asked.substring(at + 2);
                for (final Registration registration : registered(snapshot, hash)) {
                    snap(registration);
                }
                try {
                    Files.deleteIfExists(ask);
                } catch (final IOException e) {
                    // The ask stays and is answered again at the next look.
                }
            }
        }

        // The supplier runs outside every lock. It starts and its line is written only while its registration is the
        // current one, and the write holds the registry lock. So no line of a supplier comes after its removal returned.
        private void snap(final Registration registration) {
            synchronized (SNAPSHOT_LOCK) {
                if (SUPPLIERS.get(registration.id) != registration) {
                    return;
                }
            }
            String state;
            try {
                state = "state=" + registration.supplier.get();
            } catch (final RuntimeException e) {
                state = "error=" + e.getClass().getName();
            }
            synchronized (SNAPSHOT_LOCK) {
                if (SUPPLIERS.get(registration.id) != registration) {
                    return;
                }
                synchronized (lock) {
                    final Integer last = snaps.get(registration.name);
                    final int n = last == null ? 1 : last + 1;
                    snaps.put(registration.name, n);
                    place(base(registration.name, registration.key) + "." + n + ".snap",
                        line(registration.name, registration.key, state));
                }
            }
        }

        // Called under the lock, so the sequence number and the times of a line follow the order of the lines.
        private String line(final String point, final String key, final String... values) {
            final Thread thread = Thread.currentThread();
            final StringBuilder text = new StringBuilder(160);
            text.append("seq=").append(++seq);
            text.append("\twall=").append(System.currentTimeMillis());
            text.append("\tmono=").append(System.nanoTime());
            text.append("\tpid=").append(escape(pid));
            text.append("\tthread=").append(escape(thread.getName()));
            text.append("\ttid=").append(thread.getId());
            text.append("\tcopy=").append(escape(name));
            text.append("\tg=").append(g);
            text.append("\tpoint=").append(escape(point));
            if (key != null) {
                text.append("\tkey=").append(escape(key));
            }
            if (values != null) {
                for (final String value : values) {
                    text.append('\t').append(escape(value));
                }
            }
            return text.append('\n').toString();
        }

        private void write(final String line) {
            try (FileOutputStream out = new FileOutputStream(folder.resolve("events.log").toFile(), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (final IOException e) {
                // The line is lost. A hook never fails its caller.
            }
        }

        // A reader never sees a part of the file.
        private void place(final String fileName, final String line) {
            final Path target = folder.resolve(fileName);
            final Path temp = folder.resolve("." + fileName + ".tmp");
            try {
                Files.write(temp, line.getBytes(StandardCharsets.UTF_8));
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException e) {
                try {
                    Files.deleteIfExists(temp);
                } catch (final IOException ignored) {
                    // Nothing more to clean.
                }
            }
        }
    }

    /**
     * A hold file or a fault file as it was read.
     */
    private static final class Control {

        private final Path file;
        private final String token;
        private final boolean once;
        private final Map<String, String> arguments;
        private final long waitMs;

        private Control(final Path file, final String token, final boolean once, final Map<String, String> arguments,
            final long waitMs) {
            this.file = file;
            this.token = token;
            this.once = once;
            this.arguments = arguments;
            this.waitMs = waitMs;
        }

        static Control read(final Path file, final boolean fault) {
            if (!Files.exists(file)) {
                return null;
            }
            final List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (final IOException e) {
                return null;
            }
            String token = null;
            boolean once = false;
            final Map<String, String> arguments = new LinkedHashMap<>();
            for (final String line : lines) {
                final String text = line.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (fault && token == null) {
                    token = text;
                } else if ("once".equals(text)) {
                    once = true;
                } else {
                    final int eq = text.indexOf('=');
                    final String name = eq < 0 ? text : text.substring(0, eq);
                    arguments.put(name, eq < 0 ? "" : text.substring(eq + 1));
                }
            }
            long waitMs = DEFAULT_WAIT_MS;
            final String wait = arguments.get("wait");
            if (wait != null) {
                try {
                    waitMs = Math.max(0, Math.round(Double.parseDouble(wait) * 1000));
                } catch (final NumberFormatException e) {
                    // A wait that is not a number counts as not given.
                }
            }
            return new Control(file, token, once, Collections.unmodifiableMap(arguments), waitMs);
        }

        // wait and once control the file and bytes is an argument of the action. Every other argument selects.
        boolean selects(final Root current, final String point, final String key, final String[] values) {
            final Map<String, String> given = valuesOf(values);
            boolean known = true;
            for (final String name : arguments.keySet()) {
                if (!"wait".equals(name) && !"bytes".equals(name) && !given.containsKey(name)) {
                    current.append("unknown-argument", key, "at=" + point, "file=" + file.getFileName(),
                        "argument=" + name);
                    known = false;
                }
            }
            if (!known) {
                return false;
            }
            for (final Map.Entry<String, String> argument : arguments.entrySet()) {
                final String name = argument.getKey();
                if (!"wait".equals(name) && !"bytes".equals(name) && !argument.getValue().equals(given.get(name))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Registration {

        private final List<String> id;
        private final String name;
        private final String key;
        private final Supplier<String> supplier;

        Registration(final String name, final String key, final Supplier<String> supplier) {
            this.id = Arrays.asList(name, key);
            this.name = name;
            this.key = key;
            this.supplier = supplier;
        }
    }

    private static final class GivenFault implements Fault {

        private final String token;
        private final Map<String, String> arguments;

        GivenFault(final String token, final Map<String, String> arguments) {
            this.token = token;
            this.arguments = arguments;
        }

        @Override
        public String token() {
            return token;
        }

        @Override
        public Map<String, String> arguments() {
            return arguments;
        }
    }
}
