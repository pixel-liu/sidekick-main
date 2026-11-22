package com.sidekick.cli;

import com.sidekick.render.inline.InlineRenderer;
import com.sidekick.runtime.CancellationContext;
import com.sidekick.snapshot.SideGitManager;
import com.sidekick.snapshot.SnapshotConfig;
import com.sidekick.snapshot.SnapshotService;
import com.sidekick.snapshot.TurnSnapshot;
import org.jline.reader.LineReader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class MainTaskCancellationTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"react", "plan", "team"})
    void ctrlCRestoresChatWhilePreTurnSnapshotIsStillBlocked(String mode) throws Exception {
        cancelBlockedSnapshot(mode, false);
    }

    @Test
    void terminalSignalCancelsBlockedSnapshotToo() throws Exception {
        cancelBlockedSnapshot("react", true);
    }

    private void cancelBlockedSnapshot(String mode, boolean signal) throws Exception {
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        AtomicBoolean agentStarted = new AtomicBoolean();
        SideGitManager manager = new SideGitManager(tempDir,
                new SnapshotConfig(true, tempDir.resolve("snapshots"), 10, List.of())) {
            @Override
            public TurnSnapshot preTurnSnapshot(String turnId, String summary) {
                snapshotStarted.countDown();
                // Simulate a filesystem operation that does not respond to interruption.
                while (releaseSnapshot.getCount() > 0) {
                    try {
                        releaseSnapshot.await();
                    } catch (InterruptedException ignored) {
                    }
                }
                return null;
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var keys = Executors.newSingleThreadExecutor();
        try (SnapshotService snapshots = new SnapshotService(manager);
             LineDisciplineTerminal terminal = new LineDisciplineTerminal("cancel-test", "xterm-256color",
                     output, StandardCharsets.UTF_8);
             InlineRenderer renderer = new InlineRenderer(terminal)) {
            terminal.setSize(new Size(80, 24));
            Attributes attributes = terminal.getAttributes();
            attributes.setLocalFlag(Attributes.LocalFlag.ISIG, false);
            terminal.setAttributes(attributes);
            Main.CtrlCExit ctrlC = new Main.CtrlCExit();
            var keyPress = keys.submit(() -> {
                assertTrue(snapshotStarted.await(3, TimeUnit.SECONDS));
                if (signal) {
                    ctrlC.signal();
                } else {
                    terminal.processInputByte(3);
                }
                return null;
            });

            String response = Main.runWithCancelSupport(terminal,
                    new PrintStream(output, true, StandardCharsets.UTF_8), ctrlC,
                    () -> snapshots.runTurn(mode, "郑州今天天气", () -> {
                        agentStarted.set(true);
                        return "should not run";
                    }));

            keyPress.get(3, TimeUnit.SECONDS);
            assertEquals(1, releaseSnapshot.getCount(), "input must resume before Git finishes");
            assertTrue(response.contains("已取消当前任务"), response);
            assertTrue(response.contains("2 秒内再按一次 Ctrl+C"), response);
            assertFalse(agentStarted.get());
            assertNull(CancellationContext.currentRun());

            LineReader reader = renderer.createLineReader();
            Main.configureJLineInteractiveWidgets(reader);
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                try {
                    terminal.processInputBytes("继续\r".getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return true;
            });
            assertEquals(Main.PromptInput.submitted("继续"), Main.readPromptInput(reader, renderer, false));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("试试「解释 @pom.xml」"));
        } finally {
            releaseSnapshot.countDown();
            keys.shutdownNow();
        }
    }
}
