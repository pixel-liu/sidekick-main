package com.sidekick.snapshot;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class InterruptibleFileTreeIteratorTest {
    @TempDir
    Path tempDir;

    @Test
    void interruptsAnOpenFileStreamInsideNestedDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("nested/deeper"));
        Files.writeString(tempDir.resolve("nested/deeper/large.bin"), "large file content");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call();
             TreeWalk walk = new TreeWalk(git.getRepository())) {
            walk.addTree(new InterruptibleFileTreeIterator(git.getRepository()));
            walk.setRecursive(true);
            while (walk.next()) {
                if (!walk.getPathString().equals("nested/deeper/large.bin")) {
                    continue;
                }
                var iterator = walk.getTree(0, InterruptibleFileTreeIterator.class);
                assertNotNull(iterator);
                try (InputStream input = iterator.openEntryStream()) {
                    assertTrue(input.read() >= 0);
                    Thread.currentThread().interrupt();
                    try {
                        assertThrows(CancellationException.class, () -> input.read(new byte[8]));
                        assertThrows(CancellationException.class, input::read);
                        assertThrows(CancellationException.class, () -> iterator.next(1));
                    } finally {
                        Thread.interrupted();
                    }
                }
                return;
            }
            fail("nested snapshot file was not visited");
        }
    }
}
