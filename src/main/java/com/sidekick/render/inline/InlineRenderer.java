package com.sidekick.render.inline;

import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.llm.LlmClient;
import com.sidekick.render.PlainRenderer;
import com.sidekick.render.Renderer;
import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inline 流式渲染器：默认形态。
 *
 * <p>不进 alternate screen，主屏直接输出；输入期状态区紧跟当前 prompt 渲染。
 * 工具调用块、行内 diff、HITL 单字符提示、palette 等高级特性在 Day 3 / Day 4 落地，
 * 现阶段对应方法委托给 {@link PlainRenderer} 兜底。
 */
public final class InlineRenderer implements Renderer {

    private final Terminal terminal;
    private final PlainRenderer fallback;
    private final BottomStatusBar statusBar;
    private final BlockRegistry blockRegistry;
    private final PrintStream stream;
    private final PrintStream assistantStream;
    private final PrintStream uiOutput;
    private final PrintStream out;
    private final Object transcriptLock = new Object();
    private final StringBuilder turnThinking = new StringBuilder();
    private final InlineActivityDisplay activityDisplay;
    private final AtomicBoolean startupScreenPrinted = new AtomicBoolean(true);
    private volatile LineReader lineReader;
    private volatile boolean started;
    private volatile boolean closed;
    private InlineTurnDisplay turnDisplay;
    private boolean managedTurn;
    private boolean turnHasThinking;
    private boolean turnInEditor;

    // —— 代码块折叠状态机字段（仅供 createTranscriptStream 内部使用）——
    private final StringBuilder lineBuffer = new StringBuilder();
    private final StringBuilder assistantLineBuffer = new StringBuilder();
    private final List<String> codeBodyLines = new ArrayList<>();
    private boolean inCodeBlock;
    private boolean codeIsAssistant;
    private volatile FoldableBlock assistantCodeBlock;
    private String codeLanguage = "";
    private String codeHeaderLine;

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
        this.blockRegistry = new BlockRegistry();
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
            inCodeBlock = false;
            assistantCodeBlock = null;
            codeBodyLines.clear();
            codeLanguage = "";
            codeHeaderLine = null;
            synchronized (turnThinking) {
                turnThinking.setLength(0);
            }
            blockRegistry.clear();
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
            if (managedTurn && lineReader instanceof ChatLineReader chat) {
                synchronized (out) {
                    if (turnDisplay == null) {
                        turnDisplay = new InlineTurnDisplay(terminal);
                    }
                    turnDisplay.unpaint();
                }
                chat.setTurnLines(turnDisplay.lines(), turnHasThinking);
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
                    emitUi("Thinking . . . >" + System.lineSeparator());
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

    /**
     * 在 LineReader 第一次进入 readLine 时打印首屏。
     *
     * <p>首屏不能在 readLine 之前用普通 stdout 打印：底部 Status 初始化后，LineReader
     * 第一次重绘会重新接管输入区，提前打印的 banner 容易被滚动或覆盖。挂到
     * {@link LineReader#CALLBACK_INIT} 后，JLine 会把首屏、输入行和底部 dock 放在同一个
     * 显示生命周期里处理。
     */
    public void installStartupScreen(List<String> lines) {
        LineReader reader = lineReader;
        if (reader == null || lines == null || lines.isEmpty()) {
            return;
        }
        startupScreenPrinted.set(false);
        List<String> snapshot = List.copyOf(lines);
        Widget previous = reader.getWidgets().get(LineReader.CALLBACK_INIT);
        reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean ok = previous == null || previous.apply();
            if (startupScreenPrinted.compareAndSet(false, true)) {
                reader.printAbove(joinLines(snapshot));
            }
            return ok;
        });
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
        emitBlock(new FoldableBlock(out,
                ToolCallRenderer.collapsedHeader(grouped),
                ToolCallRenderer.expandedLines(grouped)));
    }

    private void emitBlock(FoldableBlock block) {
        blockRegistry.register(block);
        emitUi(block.collapsedHeader() + System.lineSeparator());
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

    /** Ctrl+O prefers the turn's reasoning. Alt+O always targets the latest tool or code block. */
    public boolean toggleBlockPreview(boolean preferThinking) {
        if (closed || !(lineReader instanceof ChatLineReader reader)) {
            return false;
        }
        String thinking;
        synchronized (turnThinking) {
            thinking = preferThinking ? turnThinking.toString() : "";
        }
        if (!thinking.isEmpty()) {
            List<AttributedString> lines = thinking.lines()
                    .map(line -> new AttributedString("│ " + cleanThinkingLine(line), InlineTheme.TEXT))
                    .toList();
            return reader.togglePreview("思考正文", "Ctrl+O", lines);
        }
        FoldableBlock block = blockRegistry.peekLast();
        if (block == null) {
            return false;
        }
        boolean assistantCode = block == assistantCodeBlock;
        return reader.togglePreview("详情", preferThinking ? "Ctrl+O" : "Alt+O", block.expandedLines().stream()
                .map(AttributedString::fromAnsi)
                .map(line -> assistantCode ? line : InlineTheme.foreground(line)).toList());
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
                    feedWithCodeBlockDetection(text, buffer, assistant);
                }
            }

            @Override
            public void flush() {
                out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    /**
     * 行级状态机：检测 {@code ┌─ code:} / {@code └─ end} 边界，把整段代码块换成
     * {@link FoldableBlock}。代码体在流式期间不写到终端（避免大段文本刷屏），
     * 等 {@code └─ end} 到达后输出折叠头。
     */
    private void feedWithCodeBlockDetection(String text, StringBuilder buffer, boolean assistant) {
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
        }
        String stripped = stripAnsi(line).trim();

        if (!inCodeBlock && stripped.startsWith("┌─ code")) {
            // Hold the whole block until the closing marker arrives.
            inCodeBlock = true;
            codeIsAssistant = assistant;
            int colon = stripped.indexOf(':');
            codeLanguage = colon >= 0 ? stripped.substring(colon + 1).trim() : "";
            codeHeaderLine = stripTrailingNewline(line);
            codeBodyLines.clear();
            return;
        }

        if (inCodeBlock && codeIsAssistant == assistant) {
            if (stripped.startsWith("└─ end")) {
                int bodyLineCount = codeBodyLines.size();
                inCodeBlock = false;

                String label = codeLanguage.isEmpty() ? "code" : "code: " + codeLanguage;
                String collapsedHeader = AnsiStyle.readableGray(
                        "⏵ " + label + " (" + bodyLineCount + " 行, alt+o to expand)");

                List<String> expandedLines = new ArrayList<>();
                expandedLines.add(stripTrailingNewline(codeHeaderLine));
                for (String body : codeBodyLines) {
                    expandedLines.add(stripTrailingNewline(body));
                }
                expandedLines.add(stripTrailingNewline(line));

                FoldableBlock block = new FoldableBlock(out, collapsedHeader, expandedLines);
                if (assistant) {
                    assistantCodeBlock = block;
                }
                emitBlock(block);

                codeBodyLines.clear();
                codeHeaderLine = null;
                return;
            }
            // body 行：缓冲，不写终端、不入 transcript
            codeBodyLines.add(line);
            return;
        }

        // 非代码块：常规流式
        if (assistant) {
            emit(line);
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
                    currentTurnDisplay().append(text, turnHasThinking);
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
                out.println(InlineTheme.format(InlineTurnDisplay.THINKING.toString()));
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

    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length()) {
                    char c = s.charAt(j);
                    if (c >= '@' && c <= '~') {
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripTrailingNewline(String s) {
        if (s == null || s.isEmpty()) {
            return s == null ? "" : s;
        }
        int end = s.length();
        if (s.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '\r') {
            end--;
        }
        return s.substring(0, end);
    }

}
