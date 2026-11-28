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

import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileObject;
import org.wso2.org.apache.commons.vfs2.FileSystem;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.FileSystemOptions;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

import java.util.Map;

/**
 * A {@link FileProvider} that handles physical files, such as the files in a local fs, or on an FTP server. An
 * originating file system cannot be layered on top of another file system.
 */
public abstract class AbstractOriginatingFileProvider extends AbstractFileProvider {

    /**
     * This holds client connect timeout for the FileProvider classes which extend from this
     * abstract class. Currently this is only used for ftp and ftps. Timeout should be specified
     * for ftp or ftps urls in milliseconds as shown below.
     * <p/>
     * e.g:  ftp://admin":password@"localhost/in2\?transport.vfs.ConnectTimeout=1000
     */
    protected Integer defaultTimeout = null;

    /**
     * The start of the fast window of the call that creates a file system, on the clock of {@link System#nanoTime()}.
     * It is set and read inside the synchronized {@code getFileSystem}. A rebuild of the probe of {@code findFile}
     * gets the window of the probe. Every other call opens its window when it enters {@code getFileSystem}.
     */
    protected long windowStart;

    private final static String CONNECT_TIMEOUT = "transport.vfs.ConnectTimeout";

    /**
     * Constructs a new instance for subclasses.
     */

    public AbstractOriginatingFileProvider() {
    }

    /**
     * Creates a {@link FileSystem}. If the returned FileSystem implements {@link VfsComponent}, it will be initialized.
     *
     * @param rootFileName The name of the root file of the file system to create.
     * @param fileSystemOptions The FileSystem options.
     * @return The FileSystem, never null.
     * @throws FileSystemException if an error occurs.
     */
    protected abstract FileSystem doCreateFileSystem(FileName rootFileName, FileSystemOptions fileSystemOptions) throws FileSystemException;

    /**
     * Locates a file from its parsed URI.
     *
     * @param fileName The file name.
     * @param fileSystemOptions FileSystem options.
     * @return A FileObject associated with the file, never null.
     * @throws FileSystemException if an error occurs.
     */
    protected FileObject findFile(final FileName fileName, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        // Check in the cache for the file system
        final FileName rootName = getContext().getFileSystemManager().resolveName(fileName, FileName.ROOT_PATH);
        // Locate the file
        return getFileSystem(rootName, fileSystemOptions).resolveFile(fileName);
    }

    /**
     * Locates a file object, by absolute URI.
     *
     * @param baseFileObject The base file object.
     * @param uri The URI of the file to locate
     * @param fileSystemOptions The FileSystem options.
     * @return The located FileObject
     * @throws FileSystemException if an error occurs.
     */
    @Override
    public FileObject findFile(final FileObject baseFileObject, final String uri, final FileSystemOptions fileSystemOptions)
            throws FileSystemException {
        Integer timeout = null;
        final Map<String, String> queryParam = UriParser.extractQueryParams(uri);//Extracting Connect Timeout from uri
        String strConnectTimeout = queryParam.get(CONNECT_TIMEOUT);
        if (strConnectTimeout != null) {
            try {
                timeout = Integer.valueOf(strConnectTimeout);
            } catch (NumberFormatException nfe) {
                getLogger().warn("value of transport.vfs.ConnectTimeout param " + strConnectTimeout + " is invalid "
                        + "so the time out does not applied" );
                timeout = null; //timeout not set
            }
        }
        // Parse the URI
        final FileName name;
        try {
            name = parseUri(baseFileObject != null ? baseFileObject.getName() : null, uri);
        } catch (final FileSystemException exc) {
            throw new FileSystemException("vfs.provider/invalid-absolute-uri.error", uri, exc);
        }
        // Locate the file
        return findFile(name, fileSystemOptions, timeout);
    }

    /**
     * Locates a file from its parsed URI.Can be used to set  default timeout for ftp and ftps
     * connections.
     * <p>
     * The file is probed with {@code exists()} on the cached file system. The probe opens a fast window at its entry.
     * An error that is no transport error is thrown as it is. After a transport error the file system is aborted and
     * closed. It is built again once, with the window of the probe, only when the error came inside the window. After
     * a slow transport error nothing is built again and the error is thrown.
     * </p>
     *
     * @param name The file name.
     * @param fileSystemOptions FileSystem options.
     * @return A FileObject associated with the file.
     * @throws FileSystemException if an error occurs.
     */
    protected FileObject findFile(final FileName name, final FileSystemOptions fileSystemOptions, Integer defaultTimeout)
            throws FileSystemException {
        // Check in the cache for the file system
        final FileName rootName = getContext().getFileSystemManager().resolveName(name, FileName.ROOT_PATH);

        final FileSystem fs = getFileSystem(rootName, fileSystemOptions, defaultTimeout);
        final FileObject testFile = fs.resolveFile(name);
        final long probeStart = System.nanoTime();
        try {
            testFile.exists();
            return testFile;
        } catch (final FileSystemException e) {
            if (!Faults.isTransport(e)) {
                throw e;
            }
            ((DefaultFileSystemManager) getContext().getFileSystemManager()).abortAndCloseFileSystem(fs);
            if (!Faults.isFast(probeStart)) {
                throw e;
            }
            TestPoints.hold("findfile.probe", pointKey(rootName));
            final FileSystem replacement;
            synchronized (this) {
                if (!Faults.isFast(probeStart)) {
                    throw e;
                }
                replacement = getFileSystem(rootName, fileSystemOptions, defaultTimeout, probeStart);
            }
            return replacement.resolveFile(name);
        }
    }

    // The root URI with no user part and no query, as the test points write a key.
    private static String pointKey(final FileName rootName) {
        if (!(rootName instanceof GenericFileName)) {
            return rootName.getURI();
        }
        final GenericFileName generic = (GenericFileName) rootName;
        final StringBuilder key = new StringBuilder(generic.getScheme()).append("://").append(generic.getHostName());
        if (generic.getPort() != generic.getDefaultPort()) {
            key.append(':').append(generic.getPort());
        }
        return key.append(generic.getPath()).toString();
    }

    /**
     * Returns the FileSystem associated with the specified root.
     *
     * @param rootFileName The root path.
     * @param fileSystemOptions The FileSystem options.
     * @return The FileSystem, never null.
     * @throws FileSystemException if an error occurs.
     * @since 2.0
     */
    protected synchronized FileSystem getFileSystem(final FileName rootFileName, final FileSystemOptions
            fileSystemOptions)
            throws FileSystemException {
        return getFileSystem(rootFileName, fileSystemOptions, null);
    }

    /**
     * Returns the FileSystem associated with the specified root. Facilitate timeout option.
     *
     * @param rootFileName The root path.
     * @param fileSystemOptions The FileSystem options.
     * @return The FileSystem.
     * @throws FileSystemException if an error occurs.
     * @since 2.0
     */
    protected synchronized FileSystem getFileSystem(final FileName rootFileName, final FileSystemOptions
            fileSystemOptions, Integer defaultTimeout)
            throws FileSystemException {
        return getFileSystem(rootFileName, fileSystemOptions, defaultTimeout, System.nanoTime());
    }

    /**
     * Returns the FileSystem associated with the specified root. A file system that is created here gets the given
     * start of the fast window for its first connect.
     *
     * @param rootFileName The root path.
     * @param fileSystemOptions The FileSystem options.
     * @param defaultTimeout The connect timeout of the URI, or null.
     * @param windowStart The start of the fast window, on the clock of {@link System#nanoTime()}.
     * @return The FileSystem.
     * @throws FileSystemException if an error occurs.
     */
    protected synchronized FileSystem getFileSystem(final FileName rootFileName, final FileSystemOptions
            fileSystemOptions, final Integer defaultTimeout, final long windowStart)
            throws FileSystemException {
        this.defaultTimeout = defaultTimeout;
        this.windowStart = windowStart;
        FileSystem fs = findFileSystem(rootFileName, fileSystemOptions);
        if (fs == null) {
            // Need to create the file system, and cache it
            fs = doCreateFileSystem(rootFileName, fileSystemOptions);
            addFileSystem(rootFileName, fs);
        }
        return fs;
    }
}
