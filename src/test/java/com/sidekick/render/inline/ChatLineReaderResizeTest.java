package com.sidekick.render.inline;

import com.sidekick.render.StatusInfo;
import org.jline.builtins.ScreenTerminal;
import org.jline.reader.LineReader;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.LineDisciplineTerminal;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class ChatLineReaderResizeTest {
    @ParameterizedTest
    @CsvSource({
            "xterm-256color, empty, false", "windows-vtp, empty, false",
            "xterm-256color, short, false", "windows-vtp, short, false",
            "xterm-256color, long, false", "windows-vtp, long, false",
            "xterm-256color, long, true", "windows-vtp, long, true"
    })
    void repeatedSnappingErasesReflowedFramesAndPreservesEditing(String type, String draftKind,
                                                                boolean moveCaret) throws Exception {
        String draft = switch (draftKind) {
            case "short" -> "draft";
            case "long" -> "a long draft that wraps across several rows after snapping the window";
            default -> "";
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LineDisciplineTerminal terminal = new LineDisciplineTerminal("resize-test", type,
                output, StandardCharsets.UTF_8);
             BottomStatusBar dock = new BottomStatusBar(terminal)) {
            terminal.setSize(new Size(120, 40));
            dock.start();
            dock.update(StatusInfo.tokens("model", 10_000, 100, 123, 45, 6,
                    null, false, 0, "idle"));
            ChatLineReader reader = new ChatLineReader(terminal, dock);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                try {
                    reader.getBuffer().write(draft);
                    if (moveCaret) {
                        reader.getBuffer().cursor(12);
                    }
                    reader.redisplay();
                    int oldWidth = 120;
                    for (Size next : List.of(new Size(40, 24), new Size(120, 40), new Size(60, 24),
                            new Size(60, 40), new Size(120, 40), new Size(40, 24))) {
                        verifyResize(terminal, reader, output, oldWidth, next, draft);
                        oldWidth = next.getColumns();
                    }
                    // Duplicate notifications and transient invalid grids must not damage editing.
                    output.reset();
                    terminal.raise(Terminal.Signal.WINCH);
                    assertEquals("", output.toString(StandardCharsets.UTF_8));
                    terminal.setSize(new Size(0, 0));
                    terminal.raise(Terminal.Signal.WINCH);
                    terminal.setSize(new Size(40, 24));
                    terminal.raise(Terminal.Signal.WINCH);
                    assertEquals(draft, reader.getBuffer().toString());
                } catch (Throwable e) {
                    // JLine catches widget failures. Carry assertions out of the widget explicitly.
                    failure.set(e);
                } finally {
                    try {
                        terminal.processInputBytes("\r".getBytes(StandardCharsets.UTF_8));
                    } catch (java.io.IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return true;
            });
            assertEquals(draft, reader.readChatLine("> "));
            if (failure.get() != null) {
                throw new AssertionError("resize frame verification failed", failure.get());
            }
        }
    }

    private static void verifyResize(LineDisciplineTerminal terminal, ChatLineReader reader,
                                     ByteArrayOutputStream output, int oldWidth, Size next, String draft) {
        int width = next.getColumns();
        int height = next.getRows();
        int caret = reader.getBuffer().cursor();
        // Seed the old hard lines reflowed at the NEW width, independently of Display.
        // Windows Terminal has already reflowed these rows when it sends WINCH.
        ScreenTerminal screen = new ScreenTerminal(width, height);
        screen.write("HISTORY\r\n" + "─".repeat(oldWidth - 1) + "\r\n> "
                + (draft.isEmpty() ? "试试「解释 @pom.xml」" : draft)
                + "\r\n" + "─".repeat(oldWidth - 1));
        int topRows = (oldWidth - 2) / width + 1;
        int cursorCells = 2 + caret;
        screen.write("\u001b[" + (2 + topRows + cursorCells / width) + ";"
                + (1 + cursorCells % width) + "H");

        output.reset();
        terminal.setSize(next);
        terminal.raise(Terminal.Signal.WINCH);
        String emitted = output.toString(StandardCharsets.UTF_8);
        screen.write(emitted);
        // ScreenTerminal represents xterm's ACS horizontal rule as an em dash.
        String painted = screen.toString().replace('—', '─');
        var rules = painted.lines().map(String::strip).filter(line -> line.matches("─+")).toList();
        assertTrue(painted.startsWith("HISTORY"), painted);
        assertEquals(List.of("─".repeat(width - 1), "─".repeat(width - 1)), rules, painted);
        assertTrue(painted.contains("ctx 1%"), painted);
        assertTrue(painted.contains("123") && painted.contains("45") && painted.contains("6"), painted);
        assertEquals(draft, reader.getBuffer().toString());
        assertEquals(caret, reader.getBuffer().cursor());
        int[] cursor = new int[2];
        screen.dump(new long[width * height], cursor);
        assertEquals(cursorCells % width, cursor[0], painted);
        assertFalse(emitted.contains("\u001b[2J") || emitted.contains("\u001b[3J"), emitted);
        assertFalse(emitted.contains("\u001b[?1049h"), emitted);
    }
}
