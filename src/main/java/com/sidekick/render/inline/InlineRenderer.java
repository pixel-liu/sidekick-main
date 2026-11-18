package com.sidekick.render.inline;

import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.llm.LlmClient;
import com.sidekick.render.PlainRenderer;
import com.sidekick.render.Renderer;
import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；输入期状态区紧跟当前 prompt 渲染。
 * 回答正文按原顺序流式显示，工具调用和结果摘要留在独立的详情视图。
 */
public final class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final PrintStream stream;
    private final PrintStream assistantStream;
    private final PrintStream uiOutput;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final StringBuilder turnThinking = new StringBuilder();
    private final InlineActivityDisplay activityDisplay;
    private String startupScreen;
    private volatile LineReader lineReader;
    private volatile boolean started;
    private volatile boolean closed;
    private InlineTurnDisplay turnDisplay;
    private boolean managedTurn;
    private boolean turnHasThinking;
    private boolean turnInEditor;
    private volatile FoldableBlock toolCallBlock;

    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder assistantLineBuffer = new StringBuilder();

    public InlineRenderer(Terminal terminal) {
        this(terminal, System.out);
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    InlineRenderer(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.fallback = new PlainRenderer();
        this.out = out;
        this.statusBar = TerminalCapabilities.supportsScrollRegion(terminal)
                ? new BottomStatusBar(terminal, out)
                : null;
        this.activityDisplay = statusBar == null
                ? null
                : new InlineActivityDisplay(terminal, statusBar);
        this.stream = createTranscriptStream(lineBuffer, false);
        this.assistantStream = createTranscriptStream(assistantLineBuffer, true);
        this.uiOutput = InlineTheme.stream(out);
    }

    @Override
    public void beginTurn() {
        synchronized (transcriptLock) {
            commitTurn(true);
            managedTurn = false;
            turnHasThinking = false;
            turnInEditor = false;
            lineBuffer.setLength(0);
            assistantLineBuffer.setLength(0);
            toolCallBlock = null;
            synchronized (turnThinking) {
                turnThinking.setLength(0);
            }
        }
    }

    @Override
    public void start() {
        if (started || closed) {
            return;
        }
        if (statusBar != null) {
            statusBar.start();
        }
        started = true;
    }

    @Override
    public void beforeInput() {
        synchronized (transcriptLock) {
            if (startupScreen != null && lineReader != null) {
                lineReader.printAbove(startupScreen);
                startupScreen = null;
            }
            if (managedTurn && lineReader instanceof ChatLineReader chat) {
                synchronized (out) {
                    if (turnDisplay == null) {
                        turnDisplay = new InlineTurnDisplay(terminal);
                    }
                    turnDisplay.unpaint();
                    // An expandable turn must stay together until its heading and answer can be committed once.
                    if (!turnHasThinking) {
                        var size = TerminalCapabilities.safeSize(terminal);
                        turnDisplay.publishOverflow(out, size, chat.foldedTranscriptRows(size));
                    }
                }
                chat.setTurnLines(turnDisplay.lines(), turnDisplay.answerLines(), turnHasThinking);
                turnInEditor = true;
            }
        }
    }

    @Override
    public void afterInput() {
        if (statusBar != null) {
            statusBar.finishInputLine();
        }
    }

    @Override
    public String inputPrompt() {
        return "❯ ";
    }

    public ChatLineReader createLineReader() {
        return new ChatLineReader(terminal, statusBar);
    }

    @Override
    public void close() {
        synchronized (transcriptLock) {
            if (closed) {
                return;
            }
            commitTurn(true);
            closed = true;
        }
        try {
            if (activityDisplay != null) {
                activityDisplay.close();
            }
        } finally {
            if (statusBar != null) {
                statusBar.close();
            }
            fallback.close();
        }
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public PrintStream assistantStream() {
        return assistantStream;
    }

    @Override
    public int terminalColumns() {
        return Math.max(40, TerminalCapabilities.safeSize(terminal).getColumns());
    }

    @Override
    public boolean supportsThinkingPanel() {
        return activityDisplay != null;
    }

    @Override
    public void beginThinking(String label) {
        if (activityDisplay != null && !closed) {
            activityDisplay.begin(label);
        }
    }

    @Override
    public void appendThinking(String delta) {
        if (activityDisplay != null && !closed) {
            activityDisplay.appendThinking(delta);
        }
    }

    @Override
    public void endThinking() {
        if (activityDisplay != null) {
            activityDisplay.end();
        }
    }

    @Override
    public void collapseThinking() {
        if (activityDisplay == null || closed) {
            return;
        }
        appendCollapsedThinking(activityDisplay.collapse());
    }

    @Override
    public void appendCollapsedThinking(String thinking) {
        if (thinking == null || thinking.isBlank() || closed) {
            return;
        }

        boolean first;
        synchronized (turnThinking) {
            first = turnThinking.isEmpty();
            if (!first) {
                turnThinking.append("\n\n");
            }
            turnThinking.append(thinking.strip());
        }
        if (first) {
            synchronized (transcriptLock) {
                turnHasThinking = true;
                if (manageTurn()) {
                    statusBar.writeAboveActivity(() -> currentTurnDisplay().paint(true));
                } else {
                    emit(InlineTurnDisplay.FOLDED_THINKING + System.lineSeparator());
                }
            }
        }
    }

    static String cleanThinkingLine(String line) {
        return line.replaceAll("[\\p{Cc}&&[^\\t]]", "");
    }

    @Override
    public boolean supportsActivityPanel() {
        return activityDisplay != null;
    }

    @Override
    public void beginActivity(String label, String detail) {
        if (activityDisplay != null && !closed) {
            activityDisplay.beginActivity(label);
        }
    }

    @Override
    public void endActivity() {
        if (activityDisplay != null) {
            activityDisplay.end();
        }
    }

    /**
     * 绑定当前交互循环使用的 JLine LineReader。
     *
     * <p>绑定后，用户正在输入时的异步输出会优先通过
     * {@link LineReader#printAbove(String)} 显示在输入行上方；非读取态和
     * 测试/降级路径继续使用构造时注入的 {@link PrintStream}。
     */
    public void bindLineReader(LineReader lineReader) {
        this.lineReader = lineReader;
    }

    /** Queue the banner before the first read. JLine resets its display after its init callback. */
    public void installStartupScreen(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        synchronized (transcriptLock) {
            startupScreen = joinLines(lines);
        }
    }

    public void printSubmittedPrompt(String input) {
        String visible = input == null ? "" : input.strip();
        if (visible.isEmpty()) {
            return;
        }
        int cols = Math.max(20, TerminalCapabilities.safeSize(terminal).getColumns() - 1);
        emitUi(AnsiStyle.userMessageBlock(visible, cols) + "\n");
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        Map<String, List<LlmClient.ToolCall>> grouped = ToolCallRenderer.group(toolCalls);
        toolCallBlock = new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped));
    }

    @Override
    public void appendToolResultSummary(String summary) {
        FoldableBlock block = toolCallBlock;
        if (block != null) {
            block.appendDetail("  → " + summary);
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        new InlineDiffRenderer(stream).render(filePath, before, after);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
        if (activityDisplay != null) {
            activityDisplay.refreshIfActive();
        }
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        if (terminal == null) {
            return fallback.promptApproval(request);
        }
        flushBeforeDialog();
        return new InlineApprovalPrompter(uiOutput, terminal).prompt(request);
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (terminal == null) {
            return fallback.openPalette(title, items);
        }
        flushBeforeDialog();
        return new SlashPalette(uiOutput, terminal).open(title, items);
    }

    /** 测试可见：当前实例是否启动了 status bar。 */
    public boolean hasStatusBar() {
        return statusBar != null;
    }

    /** 测试 / Main.java 可见：拿到 terminal 用于其它 inline 组件。 */
    public Terminal terminal() {
        return terminal;
    }

    /** Ctrl+O shows reasoning; Alt+O shows the latest tool batch. */
    public boolean toggleBlockPreview(boolean showThinking) {
        if (closed || !(lineReader instanceof ChatLineReader reader)) {
            return false;
        }
        if (showThinking) {
            String thinking;
            synchronized (turnThinking) {
                thinking = turnThinking.toString();
            }
            if (thinking.isEmpty()) {
                return false;
            }
            List<AttributedString> lines = thinking.lines()
                    .map(line -> new AttributedString("│ " + cleanThinkingLine(line), InlineTheme.TEXT))
                    .toList();
            return reader.togglePreview("思考正文", "Ctrl+O", lines);
        }
        FoldableBlock block = toolCallBlock;
        if (block == null) {
            return false;
        }
        return reader.togglePreview("工具详情", "Alt+O", block.expandedLines().stream()
                .map(AttributedString::fromAnsi)
                .map(InlineTheme::foreground).toList());
    }

    private PrintStream createTranscriptStream(StringBuilder buffer, boolean assistant) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                String text = new String(b, off, len, StandardCharsets.UTF_8);
                synchronized (transcriptLock) {
                    if (closed) {
                        return;
                    }
                    feedTranscript(text, buffer, assistant);
                }
            }

            @Override
            public void flush() {
                out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    private void feedTranscript(String text, StringBuilder buffer, boolean assistant) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            buffer.append(ch);
            if (ch == '\n') {
                String line = buffer.toString();
                buffer.setLength(0);
                processStreamedLine(line, assistant);
            }
        }
    }

    private void processStreamedLine(String line, boolean assistant) {
        if (assistant) {
            // Keep the first answer line editable even if reasoning first arrives after it.
            manageTurn();
            emit(line, true);
        } else {
            emitUi(line);
        }
    }

    private void emitUi(String text) {
        emit(InlineTheme.format(text));
    }

    private LineReader activePrintAboveReader() {
        LineReader reader = lineReader;
        if (reader == null || closed) {
            return null;
        }
        try {
            return reader.isReading() ? reader : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void emit(String text) {
        emit(text, false);
    }

    private void emit(String text, boolean assistant) {
        if (text == null || text.isEmpty()) {
            return;
        }
        LineReader reader = activePrintAboveReader();
        if (reader != null) {
            reader.printAbove(text);
            return;
        }
        synchronized (transcriptLock) {
            if (turnInEditor) {
                commitTurn(true);
                managedTurn = false;
                turnInEditor = false;
            }
            Runnable write = () -> {
                if (managedTurn) {
                    currentTurnDisplay().append(text, turnHasThinking, assistant);
                } else {
                    out.print(text);
                    out.flush();
                }
            };
            if (statusBar != null) {
                statusBar.writeAboveActivity(write);
            } else {
                write.run();
            }
        }
    }

    private boolean manageTurn() {
        if (!turnInEditor && started && statusBar != null && lineReader instanceof ChatLineReader) {
            managedTurn = true;
        }
        return managedTurn;
    }

    private InlineTurnDisplay currentTurnDisplay() {
        if (turnDisplay == null) {
            turnDisplay = new InlineTurnDisplay(terminal);
        }
        return turnDisplay;
    }

    private void flushBeforeDialog() {
        synchronized (transcriptLock) {
            commitTurn(false);
        }
    }

    private void commitTurn(boolean includeThinking) {
        synchronized (out) {
            if (turnDisplay != null) {
                turnDisplay.commit(out, includeThinking && turnHasThinking);
                turnDisplay = null;
            } else if (managedTurn && includeThinking && turnHasThinking) {
                out.println(InlineTurnDisplay.FOLDED_THINKING);
            }
        }
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        String block = String.join("\n", lines);
        return block.endsWith("\n") ? block : block + "\n";
    }

}
