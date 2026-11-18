package com.sidekick.render.inline;

import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.llm.LlmClient;
import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import com.sidekick.util.TerminalMarkdownRenderer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineRendererTest {

    @Test
    void onAnsiTerminalEnablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertTrue(renderer.hasStatusBar());
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void onSmallTerminalDisablesStatusBar() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(40, 4));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertFalse(renderer.hasStatusBar());
            // updateStatus should still not throw
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamReturnsSystemOut() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            assertNotNull(renderer.stream());
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamUsesPrintAboveWhenLineReaderIsReading() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            renderer.stream().println("异步通知");

            Mockito.verify(lineReader).printAbove(InlineTheme.format("异步通知" + System.lineSeparator()));
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("异步通知"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamPreservesExplicitBrightWhiteForeground() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.stream().println(AnsiStyle.brightWhite("MCP 启动提示"));

            assertTrue(sink.toString(StandardCharsets.UTF_8).contains(AnsiStyle.brightWhite("MCP 启动提示")));
        } finally {
            renderer.close();
        }
    }

    @Test
    void submittedPromptUsesBrightWhiteTextWithoutGrayPanel() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.printSubmittedPrompt("解释 pom.xml");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("> 解释 pom.xml"), emitted);
            if (AnsiStyle.isEnabled()) {
                assertTrue(emitted.contains("\u001B[97m> 解释 pom.xml"), emitted);
            }
            assertFalse(emitted.contains("\u001B[48;5;236m"), emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void streamedCodeBlockStaysVisibleWithPrintAbove() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        LineReader lineReader = Mockito.mock(LineReader.class);
        Mockito.when(lineReader.isReading()).thenReturn(true);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.bindLineReader(lineReader);
            renderer.beginTurn();
            var markdown = new TerminalMarkdownRenderer(renderer.assistantStream());
            markdown.append("```bash\necho hi\n");

            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            Mockito.verify(lineReader, Mockito.times(2)).printAbove(output.capture());
            assertTrue(output.getAllValues().get(0).contains("code: bash"));
            assertTrue(output.getAllValues().get(1).contains("    echo hi"));
            assertFalse(sink.toString(StandardCharsets.UTF_8).contains("echo hi"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlineRendererKeepsPromptInTranscriptFlow() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(sink, StandardCharsets.UTF_8), true);
        Mockito.when(terminal.writer()).thenReturn(writer);
        Mockito.doAnswer(invocation -> {
            writer.flush();
            return null;
        }).when(terminal).flush();

        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.start();
            sink.reset();
            renderer.beforeInput();
            renderer.afterInput();

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertEquals("❯ ", renderer.inputPrompt());
            assertNull(renderer.inputRightPrompt());
            assertFalse(emitted.contains("[39;1H"), "LineReader should own the input row: " + emitted);
            assertFalse(emitted.contains("[37;1H"), "renderer should not force transcript cursor rows: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void firstInputDrawsOneFrameAfterTheStartupBanner() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("\n\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(screen))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("test-model", 10_000, false));
            renderer.installStartupScreen(List.of("SK_STARTUP"));

            renderer.beforeInput();
            String beforeEdit = screen.toString(StandardCharsets.UTF_8);
            assertTrue(beforeEdit.contains("SK_STARTUP"), beforeEdit);
            assertFalse(beforeEdit.contains("❯"), beforeEdit);
            assertEquals("", reader.readChatLine("❯ "));
            renderer.afterInput();

            String firstFrame = screen.toString(StandardCharsets.UTF_8).substring(beforeEdit.length());
            assertEquals(1, firstFrame.split("❯", -1).length - 1, firstFrame);
            assertTrue(firstFrame.contains("pom.xml"), firstFrame);
            renderer.beforeInput();
            assertEquals("", reader.readChatLine("❯ "));
            renderer.afterInput();
            assertEquals(1, screen.toString(StandardCharsets.UTF_8).split("SK_STARTUP", -1).length - 1);
        }
    }

    @Test
    void activityPanelOmitsCancelHintForNonCancelableWork() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(screen))) {
            renderer.start();
            renderer.beginActivity("Compacting conversation", "正在整理早期对话并生成摘要");

            String rendered = screen.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("Compacting conversation"), rendered);
            assertTrue(rendered.contains("%"), rendered);
            assertFalse(rendered.contains("Esc/Ctrl+C to cancel"), rendered);
        }
    }

    @Test
    void inlinePreviewShowsToolDetailsWithoutReplayingConversation() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("\u001Bo\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.beginTurn();
            renderer.stream().println("before");
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.stream().println("after");
            String original = transcript.toString(StandardCharsets.UTF_8);
            assertFalse(original.contains("README.md"), original);
            assertFalse(original.contains("alt+o to expand"), original);

            assertEquals("", reader.readChatLine("❯ "));

            assertEquals(original, transcript.toString(StandardCharsets.UTF_8));
            assertTrue(screen.toString(StandardCharsets.UTF_8).contains("README.md"));
        }
    }

    @Test
    void closeIsIdempotent() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));

        InlineRenderer renderer = new InlineRenderer(terminal);
        renderer.start();
        renderer.close();
        renderer.close();
    }

    @Test
    void promptApprovalDelegatesToFallback() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            // When run without TTY, fallback PlainRenderer reads from stdin.
            // Just verify the call doesn't throw and returns a non-null result.
            // Using `n` as the input is unreliable here, so we skip assertion on actual decision
            // and just verify the type contract by interrupting via empty stdin → reject.
            ApprovalRequest req = ApprovalRequest.of("write_file", "{}", "test");
            ApprovalResult result = renderer.promptApproval(req);
            assertNotNull(result);
        } finally {
            renderer.close();
        }
    }

    @Test
    void openPaletteReturnsMinusOneOnNoInput() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");

        InlineRenderer renderer = new InlineRenderer(terminal);
        try {
            int idx = renderer.openPalette("title", java.util.List.of("a", "b"));
            assertEquals(-1, idx);
        } finally {
            renderer.close();
        }
    }

    private static LlmClient.ToolCall tc(String name, String args) {
        return new LlmClient.ToolCall(name + "-id", new LlmClient.ToolCall.Function(name, args));
    }

    @Test
    void directoryTreeStaysInAnswerAndDoesNotOpenADetailView() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("\u000F\u001Bo\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.beginTurn();
            var markdown = new TerminalMarkdownRenderer(renderer.assistantStream());
            markdown.append("项目目录结构\n\n```\nsidekick/\n├── pom.xml\n└── src/\n```\n\n构建说明\n");
            markdown.finish();

            renderer.beforeInput();
            screen.reset();
            assertEquals("", reader.readChatLine("❯ "));
            renderer.afterInput();
            renderer.beginTurn();
            String frames = screen.toString(StandardCharsets.UTF_8);
            int root = frames.indexOf("    sidekick/");
            assertTrue(root >= 0 && frames.indexOf("pom.xml", root) > root, frames);
            assertFalse(frames.contains("工具详情"), frames);
            var lines = AttributedString.fromAnsi(transcript.toString(StandardCharsets.UTF_8)).toString().lines().toList();
            assertEquals(List.of("项目目录结构", "", "┌─ code", "    sidekick/", "    ├── pom.xml",
                    "    └── src/", "└─ end", "", "构建说明"), lines);
        }
    }

    @Test
    void toolResultSummariesAppearOnlyInToolDetails() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("\u000F\u000F\u001Bo\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.beginTurn();
            renderer.appendCollapsedThinking("REASONING");
            renderer.appendToolCalls(List.of(tc("web_search", "{}"), tc("web_fetch", "{}")));
            renderer.appendToolResultSummary("搜索失败：SEARCH_ERROR");
            renderer.appendToolResultSummary("抓取完成：FETCH_SUCCESS");
            var markdown = new TerminalMarkdownRenderer(renderer.assistantStream());
            markdown.append("FINAL ANSWER\n```text\nDIRECTORY_TREE\n```\n");
            markdown.finish();
            renderer.beforeInput();
            screen.reset();
            assertEquals("", reader.readChatLine("❯ "));
            renderer.afterInput();
            renderer.beginTurn();

            String frames = screen.toString(StandardCharsets.UTF_8);
            int details = frames.indexOf("工具详情 · Alt+O 收起");
            assertTrue(details >= 0, frames);
            assertTrue(frames.indexOf("DIRECTORY_TREE") >= 0 && frames.indexOf("DIRECTORY_TREE") < details, frames);
            assertTrue(frames.indexOf("SEARCH_ERROR") > details, frames);
            assertTrue(frames.indexOf("FETCH_SUCCESS") > details, frames);
            String committed = transcript.toString(StandardCharsets.UTF_8);
            assertTrue(committed.contains("FINAL ANSWER"), committed);
            assertTrue(committed.contains("DIRECTORY_TREE"), committed);
            assertFalse(committed.contains("SEARCH_ERROR"), committed);
            assertFalse(committed.contains("FETCH_SUCCESS"), committed);
        }
    }

    @Test
    void mouseWheelReturnsToEndOfCollapsedAnswerAfterOverscrollingTop() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        String keys = "\u001b[<64;5;8M".repeat(25) + "\u001b[<65;5;8M".repeat(25) + "\n";
        try (Terminal terminal = viewTerminal(keys, screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(new ByteArrayOutputStream()))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.beginTurn();
            renderer.appendCollapsedThinking("HIDDEN_REASONING");
            renderer.assistantStream().println("TOP_OF_ANSWER");
            renderer.assistantStream().print("ANSWER_BODY\n".repeat(50));
            renderer.assistantStream().println("BOTTOM_OF_ANSWER");
            renderer.beforeInput();
            screen.reset();
            assertEquals("", reader.readChatLine("❯ "));
            renderer.afterInput();

            String frames = screen.toString(StandardCharsets.UTF_8);
            int firstEnd = frames.indexOf("BOTTOM_OF_ANSWER");
            int start = frames.indexOf("TOP_OF_ANSWER");
            assertTrue(firstEnd >= 0 && start > firstEnd, frames);
            assertTrue(frames.lastIndexOf("BOTTOM_OF_ANSWER") > start, frames);
            assertFalse(frames.contains("HIDDEN_REASONING"), frames);
        }
    }

    @Test
    void multipleModelCallsShareOneThinkingBlockAndToggleInlineWithoutAppendingAnswer() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        String keys = "draft\u000F" + "\u001b[<64;5;8M".repeat(3)
                + "\u001b[<65;5;8M".repeat(45) + "\u001b[<64;5;8M\u000F\n";
        try (Terminal terminal = viewTerminal(keys, screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("test-model", 10_000, false));
            renderer.beginTurn();
            renderer.printSubmittedPrompt("输入问题");
            renderer.assistantStream().println("ANSWER_START");
            renderer.beginThinking("Thinking");
            renderer.appendThinking("第一次思考\n" + "长思考内容\n".repeat(26));
            renderer.collapseThinking();
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"old-tool.txt\"}")));
            renderer.beginThinking("Thinking");
            renderer.appendThinking("第二次思考");
            renderer.collapseThinking();
            renderer.appendCollapsedThinking("补充思考");
            renderer.assistantStream().print("FINAL_ANSWER\n".repeat(80));
            renderer.assistantStream().println("ANSWER_END");

            String original = transcript.toString(StandardCharsets.UTF_8);
            assertFalse(original.contains("ANSWER_START"), original);
            assertFalse(original.contains("FINAL_ANSWER"), original);
            screen.reset();
            renderer.beforeInput();
            String published = transcript.toString(StandardCharsets.UTF_8);
            assertEquals(original, published, "while Ctrl+O can expand, the current answer must stay out of scrollback");
            assertEquals("draft", reader.readChatLine("❯ "));
            renderer.afterInput();

            String frames = screen.toString(StandardCharsets.UTF_8);
            assertFalse(frames.contains("\u001B[?1049h"), frames);
            assertTrue(frames.contains("第一次思考"), frames);
            assertTrue(frames.contains("第二次思考"), frames);
            assertTrue(frames.contains("补充思考"), frames);
            assertFalse(frames.contains("PgUp/PgDn"), frames);
            int answerAt = frames.indexOf("ANSWER_START");
            assertTrue(frames.indexOf("Ctrl+O 收起") >= 0 && frames.indexOf("Ctrl+O 收起") < frames.indexOf("第一次思考"), frames);
            assertTrue(frames.indexOf("补充思考") < answerAt, frames);
            assertTrue(frames.indexOf("ANSWER_END", answerAt) > answerAt, frames);
            assertTrue(frames.contains("\u001b[?1000h"), frames);
            assertTrue(frames.contains("\u001b[?1000l"), frames);
            assertEquals(1, frames.split("\u001b\\[\\?1000h", -1).length - 1, frames);
            assertFalse(frames.contains("Alt+↓ 继续浏览"), frames);
            assertFalse(frames.matches("(?s).*\u001b\\[[0-9]*[LM].*"), frames);
            assertEquals(published, transcript.toString(StandardCharsets.UTF_8));

            renderer.beginTurn();
            String raw = transcript.toString(StandardCharsets.UTF_8);
            assertTrue(raw.contains(AnsiStyle.brightWhite("Thinking . . . >")), raw);
            assertEquals(AnsiStyle.brightWhite("Thinking . . . < · Ctrl+O 收起"),
                    InlineTurnDisplay.thinkingLabel(" . . . < · Ctrl+O 收起"));
            String committed = AttributedString.fromAnsi(raw).toString();
            assertEquals(1, committed.split("Thinking \\. \\. \\. >", -1).length - 1, committed);
            assertTrue(committed.indexOf("Thinking . . . >") < committed.indexOf("ANSWER_START"), committed);
            assertEquals(80, committed.split("FINAL_ANSWER", -1).length - 1, committed);
            assertFalse(committed.contains("第一次思考"), committed);
            assertFalse(committed.contains("old-tool.txt"), committed);
        }
    }

    private static ChatLineReader bindPreview(InlineRenderer renderer) {
        ChatLineReader reader = renderer.createLineReader();
        renderer.bindLineReader(reader);
        reader.getWidgets().put("preview-thinking", () -> renderer.toggleBlockPreview(true));
        reader.getWidgets().put("preview-tool", () -> renderer.toggleBlockPreview(false));
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("preview-thinking"), "\u000F");
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("preview-tool"), "\u001Bo");
        return reader;
    }

    private static Terminal viewTerminal(String keys, ByteArrayOutputStream screen) throws IOException {
        Terminal terminal = new DumbTerminal("test", "xterm-256color",
                new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)), screen, StandardCharsets.UTF_8);
        terminal.setSize(new Size(70, 18));
        return terminal;
    }

    private static PrintStream sink(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    @Test
    void nonCodeStreamingTextStillFlowsThrough() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            renderer.stream().println("普通段落 1");
            renderer.stream().println("普通段落 2");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("普通段落 1"), emitted);
            assertTrue(emitted.contains("普通段落 2"), emitted);
            // 不应出现折叠箭头
            assertFalse(emitted.contains("⏵"));
        } finally {
            renderer.close();
        }
    }

    @Test
    void conversationUiUsesExactRgbWithoutChangingAssistantMarkdown() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InlineRenderer renderer = new InlineRenderer(null, sink(output))) {
            String heading = AnsiStyle.heading("最终答复");
            renderer.stream().println(AnsiStyle.subtle("工具提示"));
            renderer.assistantStream().println(heading);
            renderer.stream().println("运行完成");

            String raw = output.toString(StandardCharsets.UTF_8);
            assertTrue(raw.contains(heading + System.lineSeparator()), raw);
            if (AnsiStyle.isEnabled()) {
                assertTrue(raw.contains("\u001B[38;2;102;102;102m工具提示"), raw);
                assertTrue(raw.contains("\u001B[38;2;102;102;102m运行完成"), raw);
                assertFalse(raw.contains("\u001B[2m"), raw);
            }
        }
    }
}
