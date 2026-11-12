package com.sidekick.render.inline;

import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JLine 托管的底部 dock。
 *
 * <p>{@link Status} 保留状态行，聊天框的边线和输入由 {@link ChatLineReader} 重绘。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final String SEPARATOR = " · ";
    private static final Pattern SKILL_RATIO = Pattern.compile("Skill (\\d+)/(\\d+)");
    private static final AttributedStyle BASE_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT | AttributedStyle.WHITE));
    private static final AttributedStyle EMPHASIS_STYLE = style(BASE_STYLE.bold());
    private static final AttributedStyle WARNING_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT | AttributedStyle.YELLOW).bold());
    private static final AttributedStyle CRITICAL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT | AttributedStyle.RED).bold());

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private Status status;
    private volatile boolean started;
    private volatile boolean closed;
    private boolean inputPositioned;

    public BottomStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.out = System.out;
    }

    /** 测试用构造器：注入输出流，避免污染真实 stdout。 */
    BottomStatusBar(Terminal terminal, PrintStream out) {
        this.terminal = terminal;
        this.out = out;
    }

    /** 初始化状态栏。重复调用无副作用。 */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        status = Status.getStatus(terminal);
        started = true;
        renderDock();
    }

    public void update(StatusInfo info) {
        this.current = mergeEnvironment(info, current);
        renderDock();
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        renderDock();
    }

    /** 首次编辑从状态栏上方开始，后续编辑跟随对话输出的位置。 */
    public void prepareInputLine(int inputRows) {
        renderDock();
        Status dock = status;
        if (dock == null || closed || dock.size() == 0 || inputPositioned) {
            return;
        }
        int rows = TerminalCapabilities.safeSize(terminal).getRows();
        int inputRow = Math.max(0, rows - dock.size() - inputRows);
        synchronized (out) {
            terminal.puts(InfoCmp.Capability.cursor_address, inputRow, 0);
            terminal.flush();
        }
        inputPositioned = true;
    }

    /** 输入提交后保留底部 dock；正文继续在 JLine 保留区上方滚动。 */
    public void finishInputLine() {
        renderDock();
    }

    private void renderDock() {
        StatusInfo info = current;
        Status dock = status;
        if (info == null || dock == null || closed || !started) {
            return;
        }
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        synchronized (out) {
            dock.update(formatStatusLines(info, cols));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Status dock = status;
        status = null;
        if (dock != null) {
            dock.close();
        }
    }

    private static StatusInfo mergeEnvironment(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }
        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp == next.mcpSummary() && skill == next.skillSummary()) {
            return next;
        }
        return next.withEnvironment(mcp, skill);
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        return List.of(formatSessionLine(info, cols), formatDetailsLine(info, cols));
    }

    private static AttributedString formatSessionLine(StatusInfo info, int cols) {
        String mode = info.hitlEnabled() ? "HITL" : "YOLO";
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        String phase = info.phase() == null ? "" : info.phase().trim();
        if ("idle".equalsIgnoreCase(phase)) {
            phase = "";
        }
        long total = Math.max(0L, info.totalTokens());
        long window = Math.max(0L, info.contextWindow());
        int percent = window == 0L ? 0 : (int) Math.min(100L, Math.round(total * 100.0 / window));
        String contextLabel = "ctx " + percent + "%";
        int modelRoom = cols - mode.length() - 2 * SEPARATOR.length() - contextLabel.length();
        AttributedStringBuilder line = new AttributedStringBuilder();
        line.append(mode, info.hitlEnabled() ? EMPHASIS_STYLE : WARNING_STYLE);
        appendField(line, shorten(model, modelRoom, false), EMPHASIS_STYLE, cols);
        AttributedStringBuilder context = new AttributedStringBuilder();
        context.append("ctx ", BASE_STYLE).append(percent + "%", contextPercentStyle(percent));
        String contextSize = " (" + formatTokens(total) + "/" + formatTokens(window) + ")";
        int phaseWidth = fieldWidth(phase);
        if (line.columnLength() + SEPARATOR.length() + context.columnLength() + contextSize.length() + phaseWidth <= cols) {
            context.append(contextSize, BASE_STYLE);
        }
        appendField(line, context.toAttributedString(), cols);
        appendField(line, phase, WARNING_STYLE, cols);
        return fitToColumns(line.toAttributedString(), cols);
    }

    private static AttributedString formatDetailsLine(StatusInfo info, int cols) {
        String mcp = info.mcpSummary() == null ? "" : info.mcpSummary();
        String skills = formatSkills(info.skillSummary());
        int environmentWidth = fieldWidth(mcp) + fieldWidth(skills);
        int directoryRoom = Math.max(Math.min(cols, 16), cols - environmentWidth);
        AttributedStringBuilder line = new AttributedStringBuilder();
        appendField(line, shorten(compactCwd(), directoryRoom, true), BASE_STYLE, cols);
        appendField(line, mcp, BASE_STYLE, cols);
        appendField(line, skills, BASE_STYLE, cols);
        if (info.inputTokens() > 0 || info.outputTokens() > 0 || info.cachedInputTokens() > 0) {
            String usage = "in " + formatTokens(info.inputTokens()) + " / out " + formatTokens(info.outputTokens());
            appendField(line, usage, BASE_STYLE, cols);
            if (info.cachedInputTokens() > 0) {
                appendField(line, "cache " + formatTokens(info.cachedInputTokens()), BASE_STYLE, cols);
            }
            if (info.estimatedCost() != null) {
                appendField(line, info.estimatedCost().trim(), BASE_STYLE, cols);
            }
        }
        if (info.elapsedMillis() > 0) {
            appendField(line, formatElapsed(info.elapsedMillis()), BASE_STYLE, cols);
        }
        return fitToColumns(line.toAttributedString(), cols);
    }

    private static String formatSkills(String raw) {
        if (raw == null) {
            return "";
        }
        if ("Skill 0".equals(raw)) {
            return "0 skills";
        }
        Matcher matcher = SKILL_RATIO.matcher(raw);
        if (!matcher.matches()) {
            return raw;
        }
        String enabled = matcher.group(1);
        String total = matcher.group(2);
        return enabled.equals(total) ? enabled + ("1".equals(total) ? " skill" : " skills")
                : enabled + "/" + total + " skills";
    }

    private static int fieldWidth(String value) {
        return value.isEmpty() ? 0 : SEPARATOR.length() + new AttributedString(value).columnLength();
    }

    private static void appendField(AttributedStringBuilder line, String value, AttributedStyle style, int cols) {
        appendField(line, new AttributedString(value, style), cols);
    }

    private static void appendField(AttributedStringBuilder line, AttributedString value, int cols) {
        int separatorWidth = line.length() == 0 ? 0 : SEPARATOR.length();
        if (value.length() == 0 || line.columnLength() + separatorWidth + value.columnLength() > cols) {
            return;
        }
        if (separatorWidth > 0) {
            line.append(SEPARATOR, BASE_STYLE);
        }
        line.append(value);
    }

    private static String shorten(String value, int width, boolean keepEnd) {
        if (width <= 0) {
            return "";
        }
        AttributedString text = new AttributedString(value);
        int length = text.columnLength();
        if (length <= width) {
            return value;
        }
        return keepEnd ? "…" + text.columnSubSequence(length - width + 1, length)
                : text.columnSubSequence(0, width - 1) + "…";
    }

    private static AttributedStyle contextPercentStyle(int percent) {
        if (percent >= 90) {
            return CRITICAL_STYLE;
        }
        if (percent >= 70) {
            return WARNING_STYLE;
        }
        return EMPHASIS_STYLE;
    }

    private static AttributedStyle style(AttributedStyle style) {
        return AnsiStyle.isEnabled() ? style : AttributedStyle.DEFAULT;
    }

    private static AttributedString fitToColumns(AttributedString text, int cols) {
        if (cols <= 0) {
            return new AttributedString("");
        }
        AttributedString safe = text == null ? new AttributedString("") : text;
        int length = safe.columnLength();
        if (length > cols) {
            return safe.columnSubSequence(0, cols);
        }
        if (length == cols) {
            return safe;
        }
        AttributedStringBuilder builder = new AttributedStringBuilder(cols);
        builder.append(safe);
        builder.append(" ".repeat(cols - length), BASE_STYLE);
        return builder.toAttributedString();
    }

    private static String compactCwd() {
        String cwd = System.getProperty("user.dir");
        if (cwd == null || cwd.isBlank()) {
            return "";
        }
        String normalized = Path.of(cwd).toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && normalized.startsWith(home)) {
            normalized = "~" + normalized.substring(home.length());
        }
        return normalized;
    }

    private static String formatTokens(long t) {
        if (t >= 1_000_000) {
            return String.format("%.1fM", t / 1_000_000.0);
        }
        if (t >= 1_000) {
            return String.format("%.1fk", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    private static String formatElapsed(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format("%.1fs", ms / 1000.0);
    }
}
