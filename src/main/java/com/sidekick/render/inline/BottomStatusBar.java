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
import java.util.List;

/**
 * JLine 托管的底部 dock。
 *
 * <p>{@link Status} 保留状态行，聊天框的边线和输入由 {@link ChatLineReader} 重绘。
 */
public final class BottomStatusBar implements AutoCloseable {

    private static final String GAP = "  ";
    private static final int BAR_WIDTH = 8;
    private static final int MIN_MODEL_WIDTH = 10;
    private static final String PARTIAL_BLOCKS = "▏▎▍▌▋▊▉";
    private static final AttributedStyle MODEL_STYLE = style(palette(147).bold());
    private static final AttributedStyle CONTEXT_STYLE = style(palette(116).bold());
    private static final AttributedStyle CONTEXT_SIZE_STYLE = palette(152);
    private static final AttributedStyle PHASE_STYLE = palette(216);
    private static final AttributedStyle TRACK_STYLE = palette(67);
    private static final AttributedStyle WARNING_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT | AttributedStyle.YELLOW).bold());
    private static final AttributedStyle CRITICAL_STYLE = style(AttributedStyle.DEFAULT
            .foreground(AttributedStyle.BRIGHT | AttributedStyle.RED).bold());

    private static final AttributedStyle INPUT_STYLE = palette(153);
    private static final AttributedStyle OUTPUT_STYLE = palette(156);
    private static final AttributedStyle CACHE_STYLE = palette(213);
    private static final AttributedStyle COST_STYLE = palette(222);

    private final Terminal terminal;
    private final PrintStream out;
    private volatile StatusInfo current;
    private Status status;
    private boolean started;
    private boolean closed;
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
    public void start() {
        synchronized (out) {
            if (started || closed) {
                return;
            }
            status = Status.getStatus(terminal);
            started = true;
            renderDock();
        }
    }

    public void update(StatusInfo info) {
        synchronized (out) {
            if (closed) {
                return;
            }
            current = mergeStatus(info, current);
            renderDock();
        }
    }

    /** 当前 StatusInfo 快照，供 thinking 面板等组件复用同一份格式化结果。 */
    public StatusInfo currentStatus() {
        return current;
    }

    /** 立即触发一次重绘（不等节流间隔）。 */
    public void flushNow() {
        synchronized (out) {
            renderDock();
        }
    }

    /** 首次编辑从状态栏上方开始，后续编辑跟随对话输出的位置。 */
    public void prepareInputLine(int inputRows) {
        synchronized (out) {
            renderDock();
            if (closed || status == null || status.size() == 0 || inputPositioned) {
                return;
            }
            int rows = TerminalCapabilities.safeSize(terminal).getRows();
            int inputRow = Math.max(0, rows - status.size() - inputRows);
            terminal.puts(InfoCmp.Capability.cursor_address, inputRow, 0);
            terminal.flush();
            inputPositioned = true;
        }
    }

    /** 输入提交后保留底部 dock；正文继续在 JLine 保留区上方滚动。 */
    public void finishInputLine() {
        flushNow();
    }

    /** 调用方持有 out 锁，保证关闭后不会再画出状态栏。 */
    private void renderDock() {
        if (current == null || status == null || closed) {
            return;
        }
        int cols = TerminalCapabilities.safeSize(terminal).getColumns();
        status.update(formatStatusLines(current, cols));
    }

    @Override
    public void close() {
        synchronized (out) {
            if (closed) {
                return;
            }
            closed = true;
            Status dock = status;
            status = null;
            if (dock != null) {
                try {
                    // JLine close() restores the scroll region but leaves the drawn rows on screen.
                    dock.hide();
                } finally {
                    dock.close();
                }
            }
        }
    }

    private static StatusInfo mergeStatus(StatusInfo next, StatusInfo previous) {
        if (next == null || previous == null) {
            return next;
        }
        String mcp = next.mcpSummary() == null || next.mcpSummary().isBlank()
                ? previous.mcpSummary()
                : next.mcpSummary();
        String skill = next.skillSummary() == null || next.skillSummary().isBlank()
                ? previous.skillSummary()
                : next.skillSummary();
        if (mcp != next.mcpSummary() || skill != next.skillSummary()) {
            next = next.withEnvironment(mcp, skill);
        }
        if (!next.hasTokenUsage() && previous.hasTokenUsage()) {
            next = next.withRequestUsage(previous);
        }
        return next;
    }

    static List<AttributedString> formatStatusLines(StatusInfo info, int cols) {
        // Keep the last terminal column blank so the status text does not trigger auto-wrap.
        int contentWidth = Math.max(0, cols - 1);
        return List.of(fitToColumns(formatSessionLine(info, contentWidth), cols));
    }

    private static AttributedString formatSessionLine(StatusInfo info, int cols) {
        String model = info.model() == null || info.model().isBlank() ? "Auto Model" : info.model().trim();
        String phase = info.phase() == null ? "" : info.phase().trim();
        if ("idle".equalsIgnoreCase(phase)) {
            phase = "";
        }
        long total = Math.max(0L, info.totalTokens());
        long window = Math.max(0L, info.contextWindow());
        double ratio = window == 0L ? 0 : Math.min(1.0, (double) total / window);
        int percent = (int) Math.round(ratio * 100);
        String contextLabel = "ctx " + percent + "%";

        AttributedString usage = formatUsage(info, cols - contextLabel.length() - GAP.length());
        int usageWidth = usage.length() == 0 ? 0 : usage.columnLength() + GAP.length();
        int room = cols - contextLabel.length() - usageWidth;
        int minModel = Math.min(MIN_MODEL_WIDTH, new AttributedString(model).columnLength()) + GAP.length();
        int minBar = 1 + GAP.length();
        int barWidth = Math.min(BAR_WIDTH, Math.max(0, room - minModel - GAP.length()));
        // Even a very narrow terminal can still show one block next to the percentage.
        if (barWidth == 0 && room >= minBar) {
            barWidth = 1;
        }
        room -= barWidth == 0 ? 0 : barWidth + GAP.length();

        AttributedStringBuilder line = new AttributedStringBuilder();
        appendField(line, shortenModel(model, room - GAP.length()), MODEL_STYLE, cols);
        AttributedStringBuilder context = new AttributedStringBuilder();
        AttributedStyle levelStyle = contextPercentStyle(percent);
        context.append(contextLabel, levelStyle);
        if (barWidth > 0) {
            context.append(GAP);
            appendProgress(context, ratio, barWidth, levelStyle);
        }
        if (line.length() == 0 && context.columnLength() > cols) {
            return fitToColumns(context.toAttributedString(), cols);
        }
        appendField(line, context.toAttributedString(), cols);

        appendField(line, "(" + formatTokens(total) + "/" + formatTokens(window) + ")",
                CONTEXT_SIZE_STYLE, cols - usageWidth);
        appendField(line, usage, cols);
        appendField(line, phase, PHASE_STYLE, cols);
        if (info.estimatedCost() != null && !info.estimatedCost().isBlank()) {
            appendField(line, info.estimatedCost().trim(), COST_STYLE, cols);
        }
        return line.toAttributedString();
    }

    private static void appendProgress(AttributedStringBuilder context, double ratio, int barWidth, AttributedStyle fill) {
        int units = ratio > 0 ? Math.max(1, (int) Math.round(ratio * barWidth * 8)) : 0;
        int full = units / 8;
        int partial = units % 8;
        context.append("█".repeat(full), fill);
        if (partial > 0) {
            context.append(PARTIAL_BLOCKS.substring(partial - 1, partial), fill);
        }
        context.append("░".repeat(barWidth - full - (partial > 0 ? 1 : 0)), TRACK_STYLE);
    }

    private static AttributedString formatUsage(StatusInfo info, int cols) {
        if (cols <= 0) {
            return AttributedString.EMPTY;
        }
        boolean measured = info.hasTokenUsage();
        String input = measured ? formatTokens(info.inputTokens()) : "—";
        String output = measured ? formatTokens(info.outputTokens()) : "—";
        String cache = measured ? formatTokens(info.cachedInputTokens()) : "—";
        AttributedString usage = new AttributedStringBuilder()
                .append("in " + input, INPUT_STYLE).append(GAP)
                .append("out " + output, OUTPUT_STYLE).append(GAP)
                .append("cache " + cache, CACHE_STYLE).toAttributedString();
        // Prefer full labels when there is still room for at least one progress block.
        if (usage.columnLength() + GAP.length() + 1 <= cols) {
            return usage;
        }
        AttributedStringBuilder compact = new AttributedStringBuilder();
        compact.append("I:" + input, INPUT_STYLE).append(' ')
                .append("O:" + output, OUTPUT_STYLE).append(' ')
                .append("C:" + cache, CACHE_STYLE);
        return compact.toAttributedString().columnSubSequence(0, cols);
    }

    private static void appendField(AttributedStringBuilder line, String value, AttributedStyle style, int cols) {
        appendField(line, new AttributedString(value, style), cols);
    }

    private static void appendField(AttributedStringBuilder line, AttributedString value, int cols) {
        int separatorWidth = line.length() == 0 ? 0 : GAP.length();
        if (value.length() == 0 || line.columnLength() + separatorWidth + value.columnLength() > cols) {
            return;
        }
        if (separatorWidth > 0) {
            line.append(GAP);
        }
        line.append(value);
    }

    private static String shortenModel(String value, int width) {
        if (width <= 0) {
            return "";
        }
        AttributedString text = new AttributedString(value);
        int length = text.columnLength();
        if (length <= width) {
            return value;
        }
        return text.columnSubSequence(0, width - 1) + "…";
    }

    private static AttributedStyle contextPercentStyle(int percent) {
        if (percent >= 90) {
            return CRITICAL_STYLE;
        }
        if (percent >= 70) {
            return WARNING_STYLE;
        }
        return CONTEXT_STYLE;
    }

    private static AttributedStyle palette(int index) {
        return style(AttributedStyle.DEFAULT.foreground(index));
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
        builder.append(" ".repeat(cols - length));
        return builder.toAttributedString();
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

}
