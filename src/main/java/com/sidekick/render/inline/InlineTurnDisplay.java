package com.sidekick.render.inline;

import com.sidekick.util.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transcript still owned by the current turn. The caller serializes paints with the status dock. */
final class InlineTurnDisplay {
    static final String FOLDED_THINKING = thinkingLabel(" . . . >");
    static final AttributedString THINKING = AttributedString.fromAnsi(FOLDED_THINKING);

    private final Terminal terminal;
    private final Display display;
    private final List<AttributedString> lines = new ArrayList<>();
    private final List<AttributedString> answerLines = new ArrayList<>();
    private final List<String> transcript = new ArrayList<>();
    private int publishedLines;
    private boolean painted;

    InlineTurnDisplay(Terminal terminal) {
        this.terminal = terminal;
        this.display = new TransientDisplay(terminal);
    }

    void append(String text, boolean showThinking, boolean assistant) {
        text.lines().forEach(raw -> {
            transcript.add(raw);
            AttributedString line = AttributedString.fromAnsi(raw);
            lines.add(line);
            if (assistant && (!answerLines.isEmpty() || !line.toString().isBlank())) {
                answerLines.add(line);
            }
        });
        paint(showThinking);
    }

    List<AttributedString> lines() {
        return List.copyOf(lines.subList(publishedLines, lines.size()));
    }

    List<AttributedString> answerLines() {
        return List.copyOf(answerLines);
    }

    static String thinkingLabel(String suffix) {
        return AnsiStyle.brightWhite("Thinking") + InlineTheme.format(suffix);
    }

    void publishOverflow(PrintStream out, Size size, int room) {
        int width = Math.max(1, size.getColumns() - 1);
        int start = lines.size();
        while (start > publishedLines && room > 0) {
            int rows = wrap(lines.get(start - 1), width, terminal).size();
            if (rows > room) {
                break;
            }
            room -= rows;
            start--;
        }
        if (start > publishedLines) {
            publishThrough(out, start);
        }
    }

    void paint(boolean showThinking) {
        Size size = TerminalCapabilities.safeSize(terminal);
        int width = Math.max(1, size.getColumns() - 1);
        // Leave room for the cursor and the largest transient activity dock.
        int limit = Math.max(1, size.getRows() - InlineActivityDisplay.MAX_ROWS - 2);
        List<AttributedString> reversed = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && reversed.size() < limit; i--) {
            var wrapped = wrap(lines.get(i), width, terminal);
            for (int row = wrapped.size() - 1; row >= 0 && reversed.size() < limit; row--) {
                reversed.add(wrapped.get(row));
            }
        }
        if (showThinking && reversed.size() < limit) {
            reversed.add(THINKING.columnSubSequence(0, width, terminal));
        }
        Collections.reverse(reversed);
        display.resize(size.getRows(), size.getColumns());
        display.update(reversed, size.cursorPos(reversed.size(), 0));
        painted = true;
    }

    void unpaint() {
        if (painted) {
            display.update(List.of(), 0);
            painted = false;
        }
    }

    void commit(PrintStream out, boolean includeThinking) {
        unpaint();
        if (includeThinking) {
            out.println(FOLDED_THINKING);
        }
        publishThrough(out, transcript.size());
    }

    private void publishThrough(PrintStream out, int end) {
        for (; publishedLines < end; publishedLines++) {
            out.println(transcript.get(publishedLines));
        }
        out.flush();
    }

    static List<AttributedString> wrap(AttributedString line, int width, Terminal terminal) {
        return line.length() == 0 ? List.of(AttributedString.EMPTY)
                : line.columnSplitLength(width, false, true, terminal);
    }
}
