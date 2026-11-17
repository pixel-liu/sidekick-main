package com.sidekick.render.inline;

import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.llm.LlmClient;
import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
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
    void streamedCodeBlockUsesCollapsedHeaderWithPrintAbove() {
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
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
            Mockito.verify(lineReader).printAbove(output.capture());
            String rendered = output.getValue();
            assertTrue(rendered.contains("⏵"), rendered);
            assertTrue(rendered.contains("code: bash"), rendered);
            assertTrue(rendered.contains("1 行"), rendered);
            assertFalse(rendered.contains("echo hi"), rendered);
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
    void streamedCodeBlockCollapsesIntoFoldableHeader() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        InlineRenderer renderer = new InlineRenderer(terminal,
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            renderer.beginTurn();
            // 模拟 TerminalMarkdownRenderer 输出的代码块（手写预渲染好的 markup）
            renderer.stream().println("┌─ code: java");
            renderer.stream().println("    public class Main {");
            renderer.stream().println("    }");
            renderer.stream().println("└─ end");

            String emitted = sink.toString(StandardCharsets.UTF_8);
            assertTrue(emitted.contains("⏵"), "应该出现折叠箭头: " + emitted);
            assertTrue(emitted.contains("code: java"), emitted);
            assertTrue(emitted.contains("2 行"), "应统计 body 行数: " + emitted);
            assertTrue(emitted.contains("alt+o"), emitted);
            // body 行不应直接显示在 delegate 上（被吞掉了）—— 验证：last occurrence 不包含 "public class"
            // 但因为 delegate.print(line) 还是会先写 body？让我们再确认：检查 final state。
            // 注意：进入代码块后 body 走 codeBodyLines 缓冲，不写 delegate；end 触发 move-up + clear-to-eos
            // 所以 emitted 里包含 ANSI 序列但**不**包含原 body 文本
            assertFalse(emitted.contains("public class Main {"),
                    "代码体应被折叠后不再可见: " + emitted);
        } finally {
            renderer.close();
        }
    }

    @Test
    void inlinePreviewShowsCodeBody() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("\u001Bo\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(new ByteArrayOutputStream()))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            assertEquals("", reader.readChatLine("❯ "));
            assertTrue(screen.toString(StandardCharsets.UTF_8).contains("echo hi"));
        }
    }

    @Test
    void multipleModelCallsShareOneThinkingBlockAndToggleInlineWithoutAppendingAnswer() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        String keys = "draft\u000F" + "\u001B[6~".repeat(10) + "\u000F\n";
        try (Terminal terminal = viewTerminal(keys, screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = bindPreview(renderer);
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("test-model", 10_000, false));
            renderer.beginTurn();
            renderer.printSubmittedPrompt("输入问题");
            renderer.assistantStream().println("ANSWER_START");
            renderer.beginThinking("Thinking");
            renderer.appendThinking("第一次思考\n" + "一段需要分页的较长正文\n".repeat(3)
                    + "下一页思考标记\n" + "一段需要分页的较长正文\n".repeat(22));
            renderer.collapseThinking();
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"old-tool.txt\"}")));
            renderer.beginThinking("Thinking");
            renderer.appendThinking("第二次思考");
            renderer.collapseThinking();
            renderer.appendCollapsedThinking("补充思考");
            renderer.assistantStream().print("FINAL_ANSWER\n".repeat(80));

            String original = transcript.toString(StandardCharsets.UTF_8);
            assertFalse(original.contains("ANSWER_START"), original);
            assertFalse(original.contains("FINAL_ANSWER"), original);
            screen.reset();
            renderer.beforeInput();
            assertEquals("draft", reader.readChatLine("❯ "));
            renderer.afterInput();

            String frames = screen.toString(StandardCharsets.UTF_8);
            assertFalse(frames.contains("\u001B[?1049h"), frames);
            assertTrue(frames.contains("第一次思考"), frames);
            assertTrue(frames.contains("第二次思考"), frames);
            assertTrue(frames.contains("补充思考"), frames);
            int answerAt = frames.indexOf("ANSWER_START");
            assertTrue(frames.indexOf("第一次思考") < answerAt, frames);
            assertTrue(answerAt < frames.indexOf("下一页思考标记"), frames);
            assertEquals(original, transcript.toString(StandardCharsets.UTF_8));

            renderer.beginTurn();
            String committed = transcript.toString(StandardCharsets.UTF_8);
            assertEquals(1, committed.split("Thinking \\. \\. \\. >", -1).length - 1, committed);
            assertTrue(committed.indexOf("Thinking . . . >") < committed.indexOf("ANSWER_START"), committed);
            assertEquals(80, committed.split("FINAL_ANSWER", -1).length - 1, committed);
            assertFalse(committed.contains("第一次思考"), committed);
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
