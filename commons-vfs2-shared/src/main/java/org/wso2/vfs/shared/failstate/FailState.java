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
package org.wso2.vfs.shared.failstate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import org.wso2.vfs.shared.log.UriMask;

/** One complete post action record. Only a checked staging record can become canonical. */
public final class FailState {
    public enum Outcome { SUCCESS, FAILURE }
    public enum MoveOrigin { ORDINARY, FALLBACK }
    public enum Action { DELETE, MOVE, MOVE_AFTER_FAILED_MOVE, NONE }
    static final int LIMIT = 8192;
    public final FileIdentity identity;
    public final String name;
    public final long written;
    public final boolean legacy;
    public final Outcome outcome;
    public final Action failed;
    public final MoveOrigin moveOrigin;
    public final Action next;
    public final String failedTarget;
    public final String target;
    public final ActionResult action;
    public final int attempts;
    public final long due;
    public final boolean flagged;
    public final String error;
    final List<String> staging = new ArrayList<>();

    FailState(FileIdentity identity, String name, long written, boolean legacy, Outcome outcome, Action failed,
            Action next, MoveOrigin moveOrigin, String failedTarget, String target, ActionResult action, int attempts, long due,
            boolean flagged, String error) {
        this.identity = identity;
        this.name = name;
        this.written = written;
        this.legacy = legacy;
        this.outcome = outcome;
        this.failed = failed;
        this.moveOrigin = moveOrigin;
        this.next = next;
        this.failedTarget = failedTarget;
        this.target = target;
        this.action = action;
        this.attempts = attempts;
        this.due = due;
        this.flagged = flagged;
        this.error = error;
    }

    private static void line(StringBuilder body, String key, Object value) {
        body.append(key).append('=').append(value).append('\n');
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(ch -> {
            if (ch == '%' || Character.isISOControl(ch)) {
                for (byte b : new String(Character.toChars(ch)).getBytes(StandardCharsets.UTF_8)) {
                    out.append(String.format(Locale.ROOT, "%%%02X", b & 255));
                }
            } else {
                out.appendCodePoint(ch);
            }
        });
        return out.toString();
    }

    private static String unescape(String text) throws CharacterCodingException {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer decoded = ByteBuffer.allocate(raw.length);
        for (int pos = 0; pos < raw.length; pos++) {
            if (raw[pos] == '%') {
                if (pos + 2 >= raw.length) {
                    throw new IllegalArgumentException("Incomplete escape");
                }
                int high = Character.digit(raw[++pos], 16);
                int low = Character.digit(raw[++pos], 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid escape");
                }
                decoded.put((byte) (high * 16 + low));
            } else {
                decoded.put(raw[pos]);
            }
        }
        decoded.flip();
        return StandardCharsets.UTF_8.newDecoder().decode(decoded).toString();
    }

    private static byte[] checked(StringBuilder body) {
        CRC32 crc = new CRC32();
        crc.update(body.toString().getBytes(StandardCharsets.UTF_8));
        return (body.toString() + String.format(Locale.ROOT, "end=%08x\n", crc.getValue()))
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] bytes() throws IOException {
        StringBuilder body = new StringBuilder();
        line(body, "vfsfail", 2);
        line(body, "name", escape(name));
        line(body, "written", written);
        line(body, "size", identity.size);
        line(body, "mtime", identity.mtime);
        line(body, "mtimeSource", identity.mtimeSource);
        line(body, "digestAlg", identity.digest() == null ? "" : "SHA-256");
        line(body, "digest", identity.digest() == null ? "" : identity.digest());
        line(body, "origin", legacy ? "legacy" : "new");
        line(body, "outcome", outcome);
        line(body, "failed", failed);
        if (moveOrigin != null) {
            line(body, "moveOrigin", moveOrigin);
        }
        line(body, "next", next);
        if (failedTarget != null) {
            line(body, "failedTarget", escape(failedTarget));
        }
        if (target != null) {
            line(body, "target", escape(target));
        }
        line(body, "step", action.step);
        line(body, "clear", action.clear);
        if (action.temp != null) {
            line(body, "temp", escape(action.temp));
        }
        line(body, "committed", action.committed);
        line(body, "phase", action.phase);
        line(body, "attempts", attempts);
        line(body, "due", due);
        line(body, "flagged", flagged);
        int core = body.length();
        if (error != null && !error.isEmpty()) {
            String masked = UriMask.mask(error).replaceAll("\\p{Cntrl}", " ");
            line(body, "error", masked.substring(0, Math.min(200, masked.length())));
            byte[] withError = checked(body);
            if (withError.length <= LIMIT) {
                return withError;
            }
            body.setLength(core);
        }
        byte[] bytes = checked(body);
        if (bytes.length > LIMIT) {
            throw new IOException("The fail marker exceeds 8192 bytes.");
        }
        return bytes;
    }

    private static String required(Map<String, String> fields, String key) {
        if (!fields.containsKey(key)) {
            throw new IllegalArgumentException(key);
        }
        return fields.get(key);
    }

    private static boolean bool(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(key);
        }
        return Boolean.parseBoolean(value);
    }

    private static String path(Map<String, String> fields, String key, boolean needed) throws CharacterCodingException {
        String value = needed ? required(fields, key) : fields.get(key);
        if (value == null || value.isEmpty() && !needed) {
            return null;
        }
        value = unescape(value);
        if (value.isEmpty() || value.startsWith("//") || value.matches("^[A-Za-z][A-Za-z0-9+.\\-]*:.*")
                || value.indexOf('\\') >= 0 || java.util.Arrays.asList(value.split("/")).contains("..")) {
            throw new IllegalArgumentException(key);
        }
        return value;
    }

    /** An unrecognised or incomplete record cannot establish ownership. */
    public static FailState parse(byte[] bytes, String uri, String name) {
        if (bytes.length > LIMIT || bytes.length < 23) {
            return null;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            int end = text.length() - 13;
            if (!text.startsWith("vfsfail=2\n") || end <= 0 || text.charAt(end - 1) != '\n'
                    || !text.substring(end).matches("end=[0-9A-Fa-f]{8}\n")) {
                return null;
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bytes.length - 13);
            if (crc.getValue() != Long.parseLong(text.substring(end + 4, end + 12), 16)) {
                return null;
            }
            Map<String, String> fields = new HashMap<>();
            for (String field : text.substring(0, end).split("\n")) {
                int sep = field.indexOf('=');
                if (sep <= 0 || fields.put(field.substring(0, sep), field.substring(sep + 1)) != null) {
                    return null;
                }
            }
            if (!name.equals(unescape(required(fields, "name")))) {
                return null;
            }
            String hash = required(fields, "digest");
            String alg = required(fields, "digestAlg");
            if (!(hash.isEmpty() && alg.isEmpty()) && !("SHA-256".equals(alg) && hash.matches("[0-9a-fA-F]{64}"))) {
                return null;
            }
            long size = Long.parseLong(required(fields, "size"));
            FileIdentity id = new FileIdentity(uri, size, Long.parseLong(required(fields, "mtime")),
                    FileIdentity.TimeSource.valueOf(required(fields, "mtimeSource")),
                    hash.isEmpty() ? null : hash.toLowerCase(Locale.ROOT));
            String origin = required(fields, "origin");
            boolean legacy = "legacy".equals(origin);
            Outcome outcome = Outcome.valueOf(required(fields, "outcome"));
            Action failed = Action.valueOf(required(fields, "failed"));
            Action next = Action.valueOf(required(fields, "next"));
            MoveOrigin moveOrigin = fields.containsKey("moveOrigin") ? MoveOrigin.valueOf(fields.get("moveOrigin")) : null;
            if (moveOrigin != null && (failed != Action.MOVE || legacy)) {
                return null;
            }
            ActionResult.Step step = ActionResult.Step.valueOf(required(fields, "step"));
            String temp = path(fields, "temp", step == ActionResult.Step.COMMIT
                    || step == ActionResult.Step.AFTER_DELETE);
            ActionResult action = new ActionResult(step, bool(fields, "clear"), temp, bool(fields, "committed"),
                    ActionResult.Phase.valueOf(required(fields, "phase")), null);
            int attempts = Integer.parseInt(required(fields, "attempts"));
            if ((!legacy && !"new".equals(origin)) || legacy != (next == Action.NONE) || attempts < 0
                    || failed != Action.MOVE && failed != Action.DELETE || size < 0) {
                return null;
            }
            return new FailState(id, name, Long.parseLong(required(fields, "written")), legacy, outcome, failed, next,
                    moveOrigin, path(fields, "failedTarget", failed == Action.MOVE),
                    path(fields, "target", next == Action.MOVE || next == Action.MOVE_AFTER_FAILED_MOVE), action,
                    attempts, Long.parseLong(required(fields, "due")), bool(fields, "flagged"), fields.get("error"));
        } catch (IllegalArgumentException | CharacterCodingException malformed) {
            return null;
        }
    }
}
