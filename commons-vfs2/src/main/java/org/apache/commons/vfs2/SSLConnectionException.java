/*
 * Copyright (c) 2023, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.1
 *
 */

package org.apache.commons.vfs2;

import java.io.IOException;

public class SSLConnectionException extends IOException {

    public SSLConnectionException() {
        super("Connection to server failed. The server may be down or there may be a network problem.");
    }

    public SSLConnectionException(String message) {
        super(message);
    }

    public SSLConnectionException(String message, Throwable cause) {
        super(message, cause);
    }

}
