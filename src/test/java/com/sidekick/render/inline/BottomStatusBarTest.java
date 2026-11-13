package com.sidekick.render.inline;

import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void groupsUsageAfterContextOnOneRow() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "running").withEnvironment("MCP 0/1", "Skill 1/1");
        var lines = BottomStatusBar.formatStatusLines(info, 200);

        String session = lines.get(0).toString();
        assertEquals(1, lines.size());
        assertTrue(session.startsWith("glm-5.1  ctx 1%  ▏░░░░░░░  (1.2k/200.0k)"
                + "  in 1.0k  out 234  cache 100  running  ¥0.0123"), session);
        assertFalse(session.contains(Path.of(System.getProperty("user.dir")).toString()), session);
        assertFalse(session.contains("1.5s"), session);
        assertFalse(session.contains("HITL"), session);
        assertFalse(session.contains("MCP 0/1"), session);
        assertFalse(session.contains("1 skill"), session);
    }

    @Test
    void contextSegmentUsesContextTokensNotUsageTotals() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 51_000L, 2_000L, 0L,
                null, false, 0L, "idle");
        String line = statusLine(info, 200);

        assertTrue(line.contains("1.2k/200.0k"));
        assertFalse(line.contains("53.0k/200.0k"));
        assertFalse(line.contains("idle"));
        assertTrue(line.contains("in 51.0k  out 2.0k  cache 0"));
    }

    @Test
    void narrowStatusKeepsContextAndFitsWideCharacters() {
        StatusInfo info = StatusInfo.idle("超长中文模型名称", 200_000L, false)
                .withEnvironment("MCP 4/4", "Skill 2/2");
        AttributedString line = BottomStatusBar.formatStatusLines(info, 42).get(0);
        String session = line.toString();
        assertEquals(42, line.columnLength());
        assertTrue(session.startsWith("超长"), session);
        assertTrue(session.contains("…  ctx 0%  ░"), session);
        assertTrue(session.contains("in —  out —  cache —"), session);

        String compact = statusLine(info, 20);
        assertEquals("ctx 0%  I:— O:— C:— ", compact);
    }

    @Test
    void statusLinesUseBrightColorsForTextAndWarnings() {
        StatusInfo info = StatusInfo.tokens("deepseek-v4-pro", 1_000_000L, 950_000L, 16_100L,
                        437L, 14_300L, null, false, 0L, "idle")
                .withEnvironment("MCP 5/5", "Skill 3/5");
        AttributedString line = BottomStatusBar.formatStatusLines(info, 140).get(0);
        AttributedStyle red = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.RED);
        assertSegmentStyle(line, "deepseek-v4-pro", AttributedStyle.DEFAULT.foreground(147).bold());
        assertSegmentStyle(line, "ctx 95%", red.bold());
        assertSegmentStyle(line, "███████▋", red.bold());
        assertSegmentStyle(line, "in 16.1k", AttributedStyle.DEFAULT.foreground(153));
        assertSegmentStyle(line, "out 437", AttributedStyle.DEFAULT.foreground(156));
        assertSegmentStyle(line, "cache 14.3k", AttributedStyle.DEFAULT.foreground(213));

        AttributedString warning = BottomStatusBar.formatStatusLines(
                StatusInfo.idle("model", 100, 70, true), 120).get(0);
        AttributedStyle yellow = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.YELLOW);
        assertSegmentStyle(warning, "ctx 70%", yellow.bold());
        assertSegmentStyle(warning, "█████▋", yellow.bold());
    }

    @Test
    void environmentRefreshRetainsUsageUntilNextMeasuredRequest() {
        BottomStatusBar bar = new BottomStatusBar(Mockito.mock(Terminal.class));
        bar.update(StatusInfo.tokens("model", 200_000L, 1200L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "idle"));
        bar.update(StatusInfo.idle("model", 200_000L, 2300L, false).withEnvironment("MCP 0/1", "Skill 4/4"));

        StatusInfo refreshed = bar.currentStatus();
        assertEquals(2300L, refreshed.totalTokens());
        assertEquals(100L, refreshed.cachedInputTokens());
        assertEquals("¥0.0123", refreshed.estimatedCost());
        assertEquals("MCP 0/1", refreshed.mcpSummary());
        assertFalse(refreshed.hitlEnabled());

        bar.update(StatusInfo.tokens("model", 200_000L, 2500L, 2345L, 501L, 0L, "¥0.0200",
                false, 2000L, "idle"));
        String narrow = statusLine(bar.currentStatus(), 42);
        assertTrue(narrow.contains("in 2.3k  out 501  cache 0"), narrow);
    }

    @Test
    void closeBeforeStartIsSafe() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.close();
        // 不应抛异常；不应输出 ANSI（因为 start 没调过）
        assertTrue(sink.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void startDoesNotHandWriteScrollRegion() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertFalse(emitted.contains("[1;21r"), "dock setup must be delegated to JLine Status: " + emitted);
        } finally {
            bar.close();
        }
    }

    @Test
    void closeErasesReservedRowsAndRestoresFullScrolling() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (Terminal terminal = TerminalBuilder.builder().system(false).type("xterm-256color")
                .streams(InputStream.nullInputStream(), sink).build()) {
            terminal.setSize(new Size(80, 24));
            BottomStatusBar bar = new BottomStatusBar(terminal);
            bar.start();
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            assertEquals(1, Status.getStatus(terminal).size());
            assertTrue(sink.toString(StandardCharsets.UTF_8).contains(AnsiSeq.setScrollRegion(1, 23)));

            sink.reset();
            bar.close();
            assertEquals(0, Status.getStatus(terminal).size());
            String teardown = sink.toString(StandardCharsets.UTF_8);
            assertTrue(teardown.contains(AnsiSeq.setScrollRegion(1, 24)), teardown);
            assertTrue(teardown.contains(AnsiSeq.moveCursor(24, 1) + AnsiSeq.CLEAR_TO_EOL), teardown);
            assertFalse(teardown.contains(AnsiSeq.CLEAR_TO_EOS), teardown);

            bar.flushNow();
            bar.update(StatusInfo.idle("next-model", 200_000L, true));
            bar.close();
            assertEquals(teardown, sink.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void flushNowDoesNotPrintOutsideInputLifecycle() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            bar.flushNow();
            assertTrue(sink.toString(StandardCharsets.UTF_8).isEmpty());
        } finally {
            bar.close();
        }
    }

    @Test
    void inputLifecycleDoesNotHandWriteInlineStatusOrClearScrollback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        try {
            bar.update(StatusInfo.idle("glm-5.1", 200_000L, false));
            sink.reset();
            bar.prepareInputLine(3);
            String prepared = sink.toString(StandardCharsets.UTF_8);
            assertFalse(prepared.startsWith("\n\n"), "dock must not inject spacer rows under the prompt: " + prepared);
            assertFalse(prepared.contains(AnsiSeq.moveUp(3)), prepared);
            assertFalse(prepared.contains("[22;1H"), "prompt must stay in transcript flow: " + prepared);

            sink.reset();
            bar.finishInputLine();
            String finished = sink.toString(StandardCharsets.UTF_8);
            assertFalse(finished.contains(AnsiSeq.CLEAR_TO_EOS), finished);
            assertFalse(finished.contains("[22;1H"), "transcript scrolling is not manually forced anymore: " + finished);
        } finally {
            bar.close();
        }
    }

    private static String statusLine(StatusInfo info, int columns) {
        return BottomStatusBar.formatStatusLines(info, columns).get(0).toString();
    }

    private static void assertSegmentStyle(AttributedString line, String segment, AttributedStyle colorStyle) {
        int start = line.toString().indexOf(segment);
        assertTrue(start >= 0, "missing segment: " + segment + " in " + line);
        AttributedStyle expected = AnsiStyle.isEnabled() ? colorStyle : AttributedStyle.DEFAULT;
        for (int index = start; index < start + segment.length(); index++) {
            assertEquals(expected, line.styleAt(index), segment);
        }
    }
}
