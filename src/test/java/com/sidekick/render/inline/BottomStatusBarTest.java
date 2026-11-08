package com.sidekick.render.inline;

import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void footerLineIncludesAllFields() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "running");
        String line = footerLine(info, 200);
        assertTrue(line.contains("Auto Model"), line);
        assertTrue(line.contains("running"), line);
        assertTrue(line.contains("glm-5.1"), line);
        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1%"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("in 1.0k out 234"), line);
        assertTrue(line.contains("cache 100"), line);
        assertTrue(line.contains("¥0.0123"), line);
        assertTrue(line.contains("1.5s"), line);
    }

    @Test
    void contextSegmentUsesContextTokensNotUsageTotals() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 51_000L, 2_000L, 0L,
                null, false, 0L, "idle");
        String line = footerLine(info, 200);

        assertTrue(line.contains("ctx"), line);
        assertTrue(line.contains("1.2k/200.0k"), line);
        assertTrue(line.contains("in 51.0k out 2.0k"), line);
        assertFalse(line.contains("53.0k/200.0k"), line);
    }

    @Test
    void formatStatusLinePadsToColumnWidth() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        AttributedString line = BottomStatusBar.formatStatusLines(info, 80).get(0);
        assertEquals(80, line.columnLength());
    }

    @Test
    void formatStatusLineTruncatesWhenLong() {
        StatusInfo info = StatusInfo.idle("glm-5.1", 200_000L, false)
                .withEnvironment("MCP 4/4", "Skill 2/2");
        AttributedString line = BottomStatusBar.formatStatusLines(info, 30).get(0);
        assertEquals(30, line.columnLength());
        assertTrue(line.toString().startsWith("YOLO Ctrl+Y to enable HITL"));
    }

    @Test
    void footerLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = footerLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void footerLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, false, 250L);
        String line = footerLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void footerLineFitsColumnWidth() {
        AttributedString line = BottomStatusBar.formatStatusLines(StatusInfo.idle("测试模型", 200_000L, false), 40).get(1);
        assertEquals(40, line.columnLength());
        assertTrue(line.toString().startsWith("Auto Model · 测试模型"), line.toString());
    }

    @Test
    void activeStatusLineShowsPhase() {
        StatusInfo info = StatusInfo.active("glm-5.1", 200_000L, false, "plan")
                .withEnvironment("MCP 4/4", "Skill 2/2");
        String top = BottomStatusBar.formatStatusLines(info, 80).get(0).toString();
        String bottom = footerLine(info, 80);
        assertEquals("YOLO Ctrl+Y to enable HITL · 4 MCP servers · 2 skills", top.stripTrailing());
        assertTrue(bottom.startsWith("Auto Model · glm-5.1  plan"), bottom);
    }

    @Test
    void statusLinesUseBrightColorsForTextAndWarnings() {
        StatusInfo info = StatusInfo.tokens("deepseek-v4-pro", 1_000_000L, 5200L, 16_100L,
                        437L, 14_300L, null, false, 0L, "idle")
                .withEnvironment("MCP 5/5", "Skill 5/5");
        var lines = BottomStatusBar.formatStatusLines(info, 140);
        assertEquals(2, lines.size());
        AttributedStyle white = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.WHITE);
        AttributedStyle yellow = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.YELLOW);
        assertSegmentStyle(lines.get(0), "YOLO", yellow.bold());
        assertSegmentStyle(lines.get(0), "5 MCP servers · 5 skills", white);
        assertSegmentStyle(lines.get(1), "Auto Model · ", white);
        assertSegmentStyle(lines.get(1), "deepseek-v4-pro", white.bold());
        assertSegmentStyle(lines.get(1), "idle  ctx ░░░░░░░░", white);
        assertSegmentStyle(lines.get(1), "(5.2k/1.0M)  in 16.1k out 437 cache 14.3k", white);
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
    void closeIsSafeAfterStart() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getSize()).thenReturn(new Size(80, 24));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BottomStatusBar bar = new BottomStatusBar(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        bar.start();
        bar.close();
        // 不应抛异常；mock terminal 不创建真实 JLine Status。
        assertTrue(true);
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
            bar.prepareInputLine();
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

    private static String footerLine(StatusInfo info, int columns) {
        return BottomStatusBar.formatStatusLines(info, columns).get(1).toString();
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
