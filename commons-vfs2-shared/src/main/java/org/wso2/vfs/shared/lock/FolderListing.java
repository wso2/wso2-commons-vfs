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
package org.wso2.vfs.shared.lock;

import java.net.URI;
import org.wso2.org.apache.commons.vfs2.provider.UriParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A poll owns this immutable listing. Every negative result still requires a fresh look. */
public final class FolderListing {
    public static final class Metadata {
        public final long size;
        public final long modified;

        public Metadata(final long size, final long modified) {
            this.size = size;
            this.modified = modified;
        }
    }

    public final String folder;
    private final Map<String, Metadata> names;
    private final Map<String, String> markerBases = new HashMap<>();
    private final Map<String, List<String>> stages = new HashMap<>();

    private FolderListing(final String folder, final Map<String, Metadata> files) {
        this.folder = folder;
        this.names = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        for (final String name : names.keySet()) {
            final String target = ReservedNames.stagingTarget(name);
            if (target != null) {
                stages.computeIfAbsent(target, ignored -> new ArrayList<>()).add(name);
                markerBases.put(PathRegistry.key(child(target)), target);
            } else if (name.endsWith(".fail") && name.length() > 5) {
                final String base = name.substring(0, name.length() - 5);
                markerBases.put(PathRegistry.key(child(base)), base);
            }
        }
    }

    public static FolderListing of(final String folder, final Map<String, Metadata> files) {
        return new FolderListing(folder, files);
    }

    public Map<String, Metadata> names() {
        return names;
    }

    public List<String> stagingNames(final String file) {
        return Collections.unmodifiableList(stages.getOrDefault(file, Collections.emptyList()));
    }

    /** The provider spelling of the data name when this listing contains its canonical or staging marker. */
    public String markedBase(final String dataUri) {
        return markerBases.get(PathRegistry.key(dataUri));
    }

    public String child(final String name) {
        final int query = folder.indexOf('?');
        final String path = query < 0 ? folder : folder.substring(0, query);
        return LockIo.suffix(folder, (path.endsWith("/") ? "" : "/") + PathRegistry.pathUri(name));
    }

    boolean lists(final String uri) {
        final String key = PathRegistry.key(uri);
        final String path = key.startsWith("/") ? key : URI.create(key).getPath();
        final String literal = path.substring(path.lastIndexOf('/') + 1);
        // A VFS base name escapes the literal percent. The remote key also retains URI escapes.
        final String name = names.containsKey(literal) ? literal : UriParser.encode(literal);
        if (names.containsKey(name) && key.equals(PathRegistry.key(child(name)))) {
            return true;
        }
        final String raw = key.substring(key.lastIndexOf('/') + 1);
        return names.containsKey(raw) && key.equals(PathRegistry.key(child(raw)));
    }
}
