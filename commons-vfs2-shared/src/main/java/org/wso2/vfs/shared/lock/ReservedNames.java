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

import org.wso2.vfs.shared.write.TempName;
import org.wso2.vfs.shared.write.TempNames;

public final class ReservedNames {
    private ReservedNames() {
    }

    public static String stagingTarget(final String name) {
        final TempName part = TempNames.parse(name, ".next");
        if (part == null) {
            return null;
        }
        final String marker = part.targetName();
        return marker.endsWith(".fail") && marker.length() > 5 ? marker.substring(0, marker.length() - 5) : null;
    }

    public static boolean isReserved(final String name, final boolean folder, final String tempFolderName,
            final String tempSuffix) {
        return name.endsWith(".lock") || name.endsWith(".fail") || stagingTarget(name) != null
                || (folder ? name.equals(tempFolderName) : TempNames.parse(name, tempSuffix) != null);
    }
}
