package com.sidekick.snapshot;

import com.sidekick.runtime.CancellationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void canceledTurnNeverStartsSnapshotOrAgent() throws Exception {
        var token = CancellationContext.startRun();
        AtomicBoolean started = new AtomicBoolean();
        try (SnapshotService service = SnapshotService.forProject(tempDir)) {
            token.cancel();
            assertThrows(CancellationException.class, () -> service.runTurn("react", "cancel", () -> {
                started.set(true);
                return "unexpected";
            }));
            assertFalse(started.get());
            assertFalse(Files.exists(service.manager().gitDir()));
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }

    @Test
    void cleanDeletesReadOnlyGitObjectsOnWindows() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));
        manager.preTurnSnapshot("turn-1", "before task");

        Path object;
        try (var objects = Files.walk(manager.gitDir().resolve("objects"))) {
            object = objects.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        DosFileAttributeView attributes = Files.getFileAttributeView(object, DosFileAttributeView.class);
        assertTrue(attributes != null, "Windows snapshot object must expose DOS attributes");
        attributes.setReadOnly(true);

        assertTrue(manager.cleanSnapshots().startsWith("🧹"));
        assertFalse(Files.exists(manager.gitDir().getParent()));
    }
}
