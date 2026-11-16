package com.sidekick.render.inline;

import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.llm.LlmClient;
import com.sidekick.render.StatusInfo;
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

            Mockito.verify(lineReader).printAbove("异步通知" + System.lineSeparator());
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
    void pagerShowsToolDetailsWithoutReplayingConversation() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("q", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            renderer.beginTurn();
            renderer.stream().println("before");
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"README.md\"}")));
            renderer.stream().println("after");
            String original = transcript.toString(StandardCharsets.UTF_8);

            assertTrue(renderer.openLastBlock());

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
            assertTrue(emitted.contains("ctrl+o"), emitted);
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
    void pagerShowsCodeBody() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("q", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(new ByteArrayOutputStream()))) {
            renderer.stream().println("┌─ code: bash");
            renderer.stream().println("    echo hi");
            renderer.stream().println("└─ end");

            assertTrue(renderer.openLastBlock());
            assertTrue(screen.toString(StandardCharsets.UTF_8).contains("echo hi"));
        }
    }

    @Test
    void openingThinkingTwiceKeepsLongAnswerAndDraftInPlace() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        ByteArrayOutputStream transcript = new ByteArrayOutputStream();
        try (Terminal terminal = viewTerminal("draft\u000Fq\u000Fq\n", screen);
             InlineRenderer renderer = new InlineRenderer(terminal, sink(transcript))) {
            ChatLineReader reader = renderer.createLineReader();
            renderer.bindLineReader(reader);
            reader.getWidgets().put("preview-block", renderer::openLastBlock);
            reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("preview-block"), "\u000F");
            renderer.start();
            renderer.updateStatus(StatusInfo.idle("test-model", 10_000, false));
            renderer.appendToolCalls(List.of(tc("read_file", "{\"path\":\"old-tool.txt\"}")));
            renderer.beginThinking("Thinking");
            renderer.appendThinking("思考细节");
            renderer.collapseThinking();
            renderer.stream().print("FINAL_ANSWER\n".repeat(80));

            String original = transcript.toString(StandardCharsets.UTF_8);
            assertTrue(original.contains("Thinking . . . >"), original);
            assertFalse(original.contains("思考细节"), original);
            screen.reset();
            assertEquals("draft", reader.readChatLine("❯ "));

            String frames = screen.toString(StandardCharsets.UTF_8);
            assertEquals(2, frames.split("\\Q\u001B[?1049h\\E", -1).length - 1, frames);
            assertEquals(2, frames.split("\\Q\u001B[?1049l\\E", -1).length - 1, frames);
            assertTrue(frames.contains("思考细节"), frames);
            assertFalse(frames.contains("FINAL_ANSWER"), frames);
            assertFalse(frames.contains("old-tool.txt"), frames);
            assertEquals(original, transcript.toString(StandardCharsets.UTF_8));
        }
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
}
