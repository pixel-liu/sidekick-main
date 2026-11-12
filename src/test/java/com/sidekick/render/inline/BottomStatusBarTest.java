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
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottomStatusBarTest {

    @Test
    void separatesSessionFromEnvironmentAndLastRequestUsage() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 1000L, 234L, 100L, "¥0.0123",
                true, 1500L, "running").withEnvironment("MCP 0/1", "Skill 1/1");
        var lines = BottomStatusBar.formatStatusLines(info, 200);

        String session = lines.get(0).toString();
        String directory = Path.of(System.getProperty("user.dir")).getFileName().toString();
        assertTrue(session.startsWith("HITL  glm-5.1  ctx 1%  ▏░░░░░░░  (1.2k/200.0k)  running"), session);
        assertTrue(session.endsWith(directory), session);
        String details = lines.get(1).toString();
        assertEquals("MCP 0/1|1 skill|in 1.0k / out 234 / cache 100|¥0.0123|1.5s",
                details.replaceAll(" {2,}", "|"));
        int[] gaps = Pattern.compile(" {2,}").matcher(details).results()
                .mapToInt(gap -> gap.end() - gap.start()).sorted().toArray();
        assertTrue(gaps[gaps.length - 1] - gaps[0] <= 1, details);
    }

    @Test
    void contextSegmentUsesContextTokensNotUsageTotals() {
        StatusInfo info = StatusInfo.tokens("glm-5.1", 200_000L, 1200L, 51_000L, 2_000L, 0L,
                null, false, 0L, "idle");
        var lines = BottomStatusBar.formatStatusLines(info, 200);

        assertTrue(lines.get(0).toString().contains("1.2k/200.0k"));
        assertFalse(lines.get(0).toString().contains("53.0k/200.0k"));
        assertFalse(lines.get(0).toString().contains("idle"));
        assertTrue(lines.get(1).toString().contains("in 51.0k / out 2.0k"));
    }

    @Test
    void narrowStatusKeepsContextAndFitsWideCharacters() {
        StatusInfo info = StatusInfo.idle("超长中文模型名称", 200_000L, false)
                .withEnvironment("MCP 4/4", "Skill 2/2");
        var lines = BottomStatusBar.formatStatusLines(info, 22);
        String session = lines.get(0).toString();
        assertEquals(22, lines.get(0).columnLength());
        assertEquals(22, lines.get(1).columnLength());
        assertTrue(session.startsWith("超长"), session);
        assertTrue(session.contains("…  ctx 0%  ░"), session);
        assertFalse(session.contains("YOLO"), session);

        String midWidth = BottomStatusBar.formatStatusLines(info, 46).get(0).toString();
        String directory = Path.of(System.getProperty("user.dir")).getFileName().toString();
        String tail = directory.substring(directory.offsetByCodePoints(directory.length(),
                -Math.min(5, directory.codePointCount(0, directory.length()))));
        assertTrue(midWidth.endsWith(tail), midWidth);
    }

    @Test
    void detailsLineHidesElapsedWhenZero() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 200_000L, false, 0L);
        String line = detailsLine(info, 80);
        assertFalse(line.contains("ms"));
        assertFalse(line.contains("0s"));
    }

    @Test
    void detailsLineHandlesMillisecondElapsed() {
        StatusInfo info = new StatusInfo("glm-5.1", 0L, 0L, false, 250L);
        String line = detailsLine(info, 80);
        assertTrue(line.contains("250ms"), line);
    }

    @Test
    void statusLinesUseBrightColorsForTextAndWarnings() {
        StatusInfo info = StatusInfo.tokens("deepseek-v4-pro", 1_000_000L, 950_000L, 16_100L,
                        437L, 14_300L, null, false, 0L, "idle")
                .withEnvironment("MCP 5/5", "Skill 3/5");
        var lines = BottomStatusBar.formatStatusLines(info, 140);
        assertEquals(2, lines.size());
        AttributedStyle white = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.WHITE);
        AttributedStyle red = AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.RED);
        assertFalse(lines.get(0).toString().contains("YOLO"));
        assertFalse(lines.get(0).toString().contains("HITL"));
        assertSegmentStyle(lines.get(0), "deepseek-v4-pro", white.bold());
        assertSegmentStyle(lines.get(0), "95%", red.bold());
        assertSegmentStyle(lines.get(0), "███████▋", red.bold());
        assertSegmentStyle(lines.get(1), "MCP 5/5", AttributedStyle.DEFAULT.foreground(117));
        assertSegmentStyle(lines.get(1), "3/5 skills", AttributedStyle.DEFAULT.foreground(183));
        assertSegmentStyle(lines.get(1), "in 16.1k / out 437 / cache 14.3k", AttributedStyle.DEFAULT.foreground(156));
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

    private static String detailsLine(StatusInfo info, int columns) {
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
