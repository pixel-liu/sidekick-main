package com.sidekick.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fixed-height transient activity area for model thinking.
 *
 * <p>The preview belongs to the same JLine dock as the status row so no temporary
 * thinking text enters the scrolling transcript.
 */
final class InlineActivityDisplay implements AutoCloseable {

    private static final int MAX_PREVIEW_CHARS = 4096;
    private static final int MAX_REASONING_ROWS = 4;
    private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final AttributedStyle STATUS_STYLE = InlineTheme.TEXT.italic();

    private final Terminal terminal;
    private final BottomStatusBar statusBar;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder reasoning = new StringBuilder();
    private ScheduledFuture<?> tickTask;
    private boolean active;
    private boolean closed;
    private String label = "Thinking";
    private boolean showCancelHint = true;
    private long startedNanos;
    private int frame;

    InlineActivityDisplay(Terminal terminal, BottomStatusBar statusBar) {
        this.terminal = terminal;
        this.statusBar = statusBar;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Sidekick-activity-display");
            t.setDaemon(true);
            return t;
        });
    }

    /** 当 renderer 状态变化时，如果 thinking 正在显示，则刷新 spinner 和 reasoning 预览。 */
    synchronized void refreshIfActive() {
        if (active && !closed) {
            renderLocked();
        }
    }

    synchronized void begin(String label) {
        if (closed) {
            return;
        }
        reasoning.setLength(0);
        this.label = (label == null || label.isBlank()) ? "Thinking" : label.trim();
        this.showCancelHint = true;
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void beginActivity(String label) {
        if (closed) {
            return;
        }
        reasoning.setLength(0);
        this.label = (label == null || label.isBlank()) ? "Working" : label.trim();
        this.showCancelHint = false;
        this.startedNanos = System.nanoTime();
        this.frame = 0;
        this.active = true;
        renderLocked();
        restartTickLocked();
    }

    synchronized void appendThinking(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        if (!active) {
            this.label = "Thinking";
            this.showCancelHint = true;
            this.startedNanos = System.nanoTime();
            this.frame = 0;
            this.active = true;
            restartTickLocked();
        }
        reasoning.append(delta);
        renderLocked();
    }

    synchronized void end() {
        if (closed) {
            return;
        }
        active = false;
        cancelTickLocked();
        reasoning.setLength(0);
        statusBar.showActivity(List.of());
    }

    /** 清除活动区域并返回完整思考内容，供 transcript 收起为可展开入口。 */
    synchronized String collapse() {
        if (closed || !active || !showCancelHint) {
            return "";
        }
        String completed = reasoning.toString();
        end();
        return completed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        active = false;
        cancelTickLocked();
        reasoning.setLength(0);
        statusBar.showActivity(List.of());
        scheduler.shutdownNow();
    }

    private void restartTickLocked() {
        cancelTickLocked();
        tickTask = scheduler.scheduleAtFixedRate(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void cancelTickLocked() {
        if (tickTask != null) {
            tickTask.cancel(false);
            tickTask = null;
        }
    }

    private void tick() {
        synchronized (this) {
            if (!active || closed) {
                return;
            }
            frame++;
            renderLocked();
        }
    }

    private void renderLocked() {
        if (!active || closed) {
            return;
        }
        statusBar.showActivity(buildLines());
    }

    private List<AttributedString> buildLines() {
        var size = TerminalCapabilities.safeSize(terminal);
        int cols = Math.max(1, size.getColumns() - 1);
        List<AttributedString> lines = new ArrayList<>();
        if (!showCancelHint) {
            lines.add(fit("  ✢ " + label + "...", cols, STATUS_STYLE));
            lines.add(fit("    " + progressBar(cols) + " " + progressPercent() + "%", cols, STATUS_STYLE));
            return lines;
        }
        String suffix = " (Esc/Ctrl+C to cancel, " + elapsedSeconds() + "s)";
        lines.add(fit("  " + spinner() + " " + label + "..." + suffix, cols, STATUS_STYLE));

        List<String> quoteLines = reasoningLines();
        int quoteWidth = Math.max(1, cols - 4);
        int maxRows = Math.min(MAX_REASONING_ROWS, Math.max(0, size.getRows() - 4));
        var preview = new ArrayDeque<AttributedString>();
        for (int i = quoteLines.size() - 1; i >= 0 && preview.size() < maxRows; i--) {
            var wrapped = new AttributedString(quoteLines.get(i))
                    .columnSplitLength(quoteWidth, false, true, terminal);
            for (int j = wrapped.size() - 1; j >= 0 && preview.size() < maxRows; j--) {
                preview.addFirst(fit("  │ " + wrapped.get(j), cols, InlineTheme.TEXT));
            }
        }
        lines.addAll(preview);
        return lines;
    }

    private List<String> reasoningLines() {
        String content = reasoning.substring(Math.max(0, reasoning.length() - MAX_PREVIEW_CHARS))
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (content.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\\R+")) {
            String normalized = InlineRenderer.cleanThinkingLine(line).replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private AttributedString fit(String text, int cols, AttributedStyle style) {
        AttributedString attributed = new AttributedString(text == null ? "" : text, style);
        if (attributed.columnLength(terminal) <= cols) {
            return attributed;
        }
        if (cols <= 3) {
            return new AttributedString(".".repeat(Math.max(0, cols)), style);
        }
        return new AttributedString(
                attributed.columnSubSequence(0, cols - 3, terminal).toString() + "...",
                style);
    }

    private String spinner() {
        return SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)];
    }

    private String progressBar(int cols) {
        int width = Math.max(10, Math.min(40, cols - 12));
        int percent = progressPercent();
        int filled = Math.max(1, Math.min(width - 1, (int) Math.round(width * percent / 100.0)));
        return "▰".repeat(filled) + "▱".repeat(width - filled);
    }

    private int progressPercent() {
        long elapsedMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        double curve = 1.0 - Math.exp(-elapsedMillis / 15_000.0);
        return Math.max(1, Math.min(95, (int) Math.round(curve * 95)));
    }

    private long elapsedSeconds() {
        return Math.max(0, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos));
    }

}
