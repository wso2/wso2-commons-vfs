/*
 * Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.org.apache.commons.vfs2.provider.smb2.test;

import org.wso2.org.apache.commons.vfs2.FileName;
import org.wso2.org.apache.commons.vfs2.FileSystemException;
import org.wso2.org.apache.commons.vfs2.impl.DefaultFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileName;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileNameParser;
import org.wso2.org.apache.commons.vfs2.provider.smb2.Smb2FileProvider;
import org.junit.Test;

import java.net.URI;
import java.net.URISyntaxException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;

public class Smb2FileNameParserTest {
    private final static String testPasswordUE = /* printable ascii chars */
            "%20%21%22%23%24%25%26%27%28%29%2A%2B%2C%2D%2E%2F%30%31%32%33%34%35%36%37%38%39%3A%3B%3C%3D%3E%3F%40%41%42%43%44%45%46%47%48%49%4A%4B%4C%4D%4E%4F" +
                    "%50%51%52%53%54%55%56%57%58%59%5A%5B%5C%5D%5E%5F%60%61%62%63%64%65%66%67%68%69%6A%6B%6C%6D%6E%6F%70%71%72%73%74%75%76%77%78%79%7A%7B%7C%7D%7E";
    private final static String testPath = 
            "%20%21%22%23%24%25%26%27%28%29%2A%2B%2C%2D%2E%2F%30%31%32%33%34%35%36%37%38%39%3A%3B%3C%3D%3E%3F%40%41%42%43%44%45%46%47%48%49%4A%4B%4C%4D%4E%4F" +
                    "%50%51%52%53%54%55%56%57%58%59%5A%5B%5C%5D%5E%5F%60%61%62%63%64%65%66%67%68%69%6A%6B%6C%6D%6E%6F%70%71%72%73%74%75%76%77%78%79%7A%7B%7C%7D%7E";
    private final static String testURIString = "smb2://WORKGROUP;USER:" + testPasswordUE + "@example.com/ShareName/Folder1/" + testPath + "/Folder2/?somequery=true&another=query#fragment";
    // The credentials of the URI above with a path that the parser keeps and no query.
    private final static String credentialsURIString = "smb2://WORKGROUP;USER:" + testPasswordUE + "@example.com/ShareName/Folder1/Folder2/";
    // An encoded space in the path, with the same credentials and no query.
    private final static String encodedPathURIString = "smb2://WORKGROUP;USER:" + testPasswordUE + "@example.com/ShareName/Folder1/With%20Space/Folder2/";

    @Test(expected = Test.None.class /* no exception expected */)
    public void testParseUriUserInfo() throws FileSystemException, URISyntaxException {
        String expectedDecodedUserInfo = new URI(credentialsURIString).getUserInfo();

        Smb2FileName fileName = (Smb2FileName) Smb2FileNameParser.getInstance().parseUri(null, null, credentialsURIString);
        String resultDecodedUserInfo = fileName.getUserName() + ":" + fileName.getPassword();

        assertThat("Password decoded correctly", resultDecodedUserInfo, is(expectedDecodedUserInfo));
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testParseUriEncodedPath() throws FileSystemException, URISyntaxException {
        String expectedDecodedPath = (new URI(encodedPathURIString).getPath());

        Smb2FileName fileName = (Smb2FileName) Smb2FileNameParser.getInstance().parseUri(null, null, encodedPathURIString);
        String resultDecodedPath = "/" + fileName.getShareName() + fileName.getPathDecoded() + "/";

        assertThat("Path decoded correctly", resultDecodedPath, is(expectedDecodedPath));
    }

    @Test
    public void queryAndPlainNameKeepTheSameShareRelativePath() throws FileSystemException {
        final String uri = "smb2://host/share/in/file.json";
        final String query = "transport.vfs.diskShareAccessMask=MAXIMUM_ALLOWED";
        final Smb2FileNameParser parser = Smb2FileNameParser.getInstance();
        final Smb2FileName plain = (Smb2FileName) parser.parseUri(null, null, uri);
        final Smb2FileName selected = (Smb2FileName) parser.parseUri(null, null, uri + "?" + query);
        assertEquals("/in/file.json", plain.getPath());
        assertEquals(plain.getPath(), selected.getPath());
        assertEquals("share", selected.getShareName());
        assertNull(plain.getQueryString());
        assertEquals(query, selected.getQueryString());
        assertEquals(uri + "?" + query, selected.getURI());
    }

    @Test(expected = Test.None.class /* no exception expected */)
    public void testParseUriPathWithEveryPrintableCharacterAndAQuery() throws FileSystemException, URISyntaxException {
        String expectedDecodedPath = (new URI(testURIString).getPath());

        Smb2FileName fileName = (Smb2FileName) Smb2FileNameParser.getInstance().parseUri(null, null, testURIString);
        String resultDecodedPath = "/" + fileName.getShareName() + fileName.getPathDecoded() + "/";

        assertThat("Path decoded correctly", resultDecodedPath, is(expectedDecodedPath));
    }

    private static Smb2FileName parse(final String uri) throws FileSystemException {
        return (Smb2FileName) Smb2FileNameParser.getInstance().parseUri(null, null, uri);
    }

    // A name with a character above U+00FF. The escape of the path kept only the low byte, so U+540D became %0d and
    // the share got a carriage return. A surrogate pair is two such characters.
    @Test
    public void nameAboveLatin1KeepsEveryCharacter() throws FileSystemException {
        for (final String name : new String[] {"ü名.txt", "😀.txt"}) {
            final Smb2FileName parsed = parse("smb2://host/share/in/" + name);
            assertEquals(name, parsed.getBaseName());
            assertEquals("/in/" + name, parsed.getPathDecoded());
            assertEquals("share", parsed.getShareName());
        }
    }

    // A listed child is resolved by the manager against its folder, which is the path of a folder listing.
    @Test
    public void listedChildAboveLatin1ResolvesToItsOwnName() throws FileSystemException {
        final DefaultFileSystemManager manager = new DefaultFileSystemManager();
        manager.addProvider("smb2", new Smb2FileProvider());
        manager.init();
        try {
            final FileName folder = manager.resolveURI("smb2://host/share/in");
            for (final String name : new String[] {"u8.txt", "ü名.txt", "e8.txt"}) {
                final Smb2FileName child = (Smb2FileName) manager.resolveName(folder, name);
                assertEquals(name, child.getBaseName());
                assertEquals("/in/" + name, child.getPathDecoded());
                assertEquals("share", child.getShareName());
            }
        } finally {
            manager.close();
        }
    }

    // A Latin 1 name worked before, through the escape of its low byte. Its literal form and its escaped form, which
    // an earlier version put into URIs, still give the same file of the share.
    @Test
    public void latin1NameResolvesToTheSameFileAsBefore() throws FileSystemException {
        final String[][] names = {{"ü.txt", "%fc.txt"}, {"é.txt", "%e9.txt"}};
        for (final String[] name : names) {
            final Smb2FileName literal = parse("smb2://host/share/in/" + name[0]);
            final Smb2FileName escaped = parse("smb2://host/share/in/" + name[1]);
            assertEquals("/in/" + name[0], literal.getPathDecoded());
            assertEquals(literal.getPathDecoded(), escaped.getPathDecoded());
            assertEquals(literal.getShareName(), escaped.getShareName());
        }
    }

    // An ASCII character outside the allowed set is still escaped in the URI and decoded for the share.
    @Test
    public void asciiOutsideTheAllowedSetIsStillEscaped() throws FileSystemException {
        final Smb2FileName parsed = parse("smb2://host/share/in/a b.txt");
        assertEquals("a%20b.txt", parsed.getBaseName());
        assertEquals("/in/a b.txt", parsed.getPathDecoded());
        assertEquals("smb2://host/share/in/a%20b.txt", parsed.getURI());
    }
}
