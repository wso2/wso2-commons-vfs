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
package org.wso2.org.apache.commons.vfs2.provider.local;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.wso2.org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.wso2.org.apache.commons.vfs2.provider.AtomicOutcome;
import org.wso2.org.apache.commons.vfs2.provider.AtomicResult;
import org.wso2.org.apache.commons.vfs2.provider.MoveOptions;
import org.wso2.org.apache.commons.vfs2.util.FileObjectUtils;
import org.wso2.org.apache.commons.vfs2.util.TestPoints;

@Tag("testbuild")
public class MovePointsTest {
    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(strings={"before-rename","after-rename-done","after-target-delete","before-copy","copy.half",
            "before-time-set","before-commit","before-source-delete","after-link","no-replace.after-look",
            "commit-no-replace.after-look"})
    void stopsAtTheNamedBoundary(String boundary) throws Exception {
        Path controls=Files.createDirectory(dir.resolve("points"));
        System.setProperty("vfs.testpoints.dir",controls.toString());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try (StandardFileSystemManager manager=new StandardFileSystemManager()) {
            manager.init();
            TestPoints.event("move-test",null);
            Path copy;
            try (Stream<Path> dirs=Files.list(controls)) {
                copy=dirs.filter(p->Files.isRegularFile(p.resolve("copy"))).findFirst().get();
            }
            String point="move."+boundary.replace("commit-no-replace","no-replace");
            Path hold=copy.resolve(point+".hold");
            Path src=Files.write(dir.resolve("source"),new byte[24000]);
            Path dst=dir.resolve("target");
            Path stage=dir.resolve("finished");
            LocalFile source=spy((LocalFile)FileObjectUtils.getAbstractFileObject(manager.resolveFile(src.toUri())));
            LocalFile target=(LocalFile)FileObjectUtils.getAbstractFileObject(manager.resolveFile(dst.toUri()));
            LocalFile temp=spy((LocalFile)FileObjectUtils.getAbstractFileObject(manager.resolveFile(stage.toUri())));
            boolean replace=!boundary.contains("no-replace") && !boundary.equals("after-link");
            if (replace) Files.write(dst,new byte[]{1});
            switch (boundary) {
            case "after-target-delete":
                doReturn(new AtomicResult(AtomicOutcome.UNSUPPORTED,null)).when(source).renameReplacing(eq(target),any(),any());
                break;
            case "no-replace.after-look":
                doReturn(new AtomicResult(AtomicOutcome.UNSUPPORTED,null)).when(source).linkExclusive(eq(target),any());
                break;
            case "commit-no-replace.after-look":
                doReturn(new AtomicResult(AtomicOutcome.UNSUPPORTED,null)).when(temp).publishExclusive(eq(target),any());
                source.setIsMounted(true);
                break;
            case "before-rename":
            case "after-rename-done":
            case "after-link":
                break;
            default:
                source.setIsMounted(true);
                target.setUpdateLastModified(true);
            }
            Files.write(hold,("wait=30\n"+(boundary.equals("copy.half")?"bytes=13000\n":"")).getBytes(StandardCharsets.UTF_8));
            Future<?> move=worker.submit(()->{source.moveTo(target,new MoveOptions(replace,temp,"probe",null,null)); return null;});
            try {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                Path record=null;
                while (record==null && System.nanoTime()<end && !move.isDone()) {
                    try (Stream<Path> files=Files.list(copy)) {
                        record=files.filter(p->p.getFileName().toString().startsWith(point+"@")
                                && p.getFileName().toString().endsWith(".reached")).findFirst().orElse(null);
                    }
                    if (record==null) Thread.sleep(10);
                }
                assertNotNull(record,point);
                String line=new String(Files.readAllBytes(record),StandardCharsets.UTF_8);
                assertTrue(line.contains("key="+source.getName().getURI()),line);
                switch (boundary) {
                case "after-rename-done":
                    assertFalse(Files.exists(src));
                    assertEquals(24000,Files.size(dst));
                    break;
                case "after-target-delete":
                case "no-replace.after-look":
                case "commit-no-replace.after-look":
                    assertFalse(Files.exists(dst));
                    assertTrue(Files.exists(src));
                    break;
                case "copy.half":
                    assertEquals(13000,Files.size(stage));
                    assertTrue(line.contains("bytes=13000"));
                    assertArrayEquals(new byte[]{1},Files.readAllBytes(dst));
                    break;
                case "before-time-set":
                case "before-commit":
                    assertEquals(24000,Files.size(stage));
                    assertArrayEquals(new byte[]{1},Files.readAllBytes(dst));
                    break;
                case "after-link":
                    assertTrue(Files.isSameFile(src,dst));
                    break;
                case "before-source-delete":
                    assertEquals(24000,Files.size(dst));
                    assertTrue(Files.exists(src));
                    break;
                default:
                    assertFalse(Files.exists(stage));
                    assertArrayEquals(new byte[]{1},Files.readAllBytes(dst));
                }
            } finally {
                Files.deleteIfExists(hold);
            }
            move.get(10,TimeUnit.SECONDS);
            assertFalse(Files.exists(src));
            assertArrayEquals(new byte[24000],Files.readAllBytes(dst));
        } finally {
            worker.shutdownNow();
            System.clearProperty("vfs.testpoints.dir");
        }
    }
}
