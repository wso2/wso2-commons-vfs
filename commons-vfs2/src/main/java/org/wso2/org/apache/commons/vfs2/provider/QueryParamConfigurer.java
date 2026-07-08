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

package org.wso2.org.apache.commons.vfs2.provider;

import org.wso2.org.apache.commons.vfs2.FileSystemOptions;

import java.util.Map;

/**
 * Optional interface for {@link FileProvider} implementations that wish to configure
 * {@link FileSystemOptions} from URI query parameters.
 *
 * <p>When a provider registered with {@link org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager}
 * implements this interface, the manager will call {@link #configure} before resolving the file,
 * passing the parsed query parameters from the URI. Providers that do not implement this interface
 * are unaffected.
 */
public interface QueryParamConfigurer {

    /**
     * Configures the given {@link FileSystemOptions} using the supplied URI query parameters.
     *
     * @param fileSystemOptions the options to populate
     * @param queryParams       the parsed query parameters from the URI
     */
    void configure(FileSystemOptions fileSystemOptions, Map<String, String> queryParams);
}
