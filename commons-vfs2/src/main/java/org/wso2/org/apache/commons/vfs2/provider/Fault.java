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
package org.wso2.org.apache.commons.vfs2.provider;

/**
 * What an exception of a file system call means for the connection.
 */
public enum Fault {

    /**
     * The connection is dead, timed out or its session is gone.
     */
    TRANSPORT,

    /**
     * The server said that the object does not exist.
     */
    MISSING,

    /**
     * The server said that the object is in use: a sharing violation or a delete pending.
     */
    BUSY,

    /**
     * Every other reply of the server or of the local file system: access denied, logon failure, quota, a refused
     * name.
     */
    APPLICATION
}
