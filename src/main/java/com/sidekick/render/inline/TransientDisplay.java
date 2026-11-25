package com.sidekick.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;

/** A JLine frame whose rows must never be pushed into the terminal's scrollback. */
final class TransientDisplay extends Display {
    TransientDisplay(Terminal terminal) {
        super(terminal, false);
    }

    @Override
    public void resize(int rows, int columns) {
        if (columns > 0 && this.columns > 0 && columns != this.columns && !oldLines.isEmpty()) {
            // WINCH arrives after the terminal reflows the frame. Display.resize reflows
            // oldLines, but leaves cursorPos expressed in the old grid. Map the same text
            // prefix into the new grid before any relative cursor movement or erasure.
            int cursorRow = cursorPos / columns1;
            int cursorColumn = cursorPos % columns1;
            AttributedStringBuilder prefix = new AttributedStringBuilder();
            for (int row = 0; row < Math.min(cursorRow, oldLines.size()); row++) {
                prefix.append(oldLines.get(row));
            }
            if (cursorRow < oldLines.size()) {
                prefix.append(oldLines.get(cursorRow).columnSubSequence(0, cursorColumn, terminal));
            }
            List<AttributedString> reflowed = prefix.columnSplitLength(columns, false, delayLineWrap(), terminal);
            int lastRow = reflowed.size() - 1;
            cursorPos = lastRow < 0 ? 0
                    : lastRow * (columns + 1) + reflowed.get(lastRow).columnLength(terminal);
        }
        super.resize(rows, columns);
    }

    @Override
    protected boolean can(Capability capability, Capability parameterized) {
        if (capability == Capability.insert_line || capability == Capability.delete_line) {
            return false;
        }
        return super.can(capability, parameterized);
    }

    @Override
    public void update(List<AttributedString> lines, int cursor, boolean flush) {
        if (!oldLines.isEmpty() && lines.size() > oldLines.size()) {
            // Growing near the bottom may scroll. Erase the old frame before adding rows.
            super.update(List.of(), 0, false);
        }
        super.update(lines, cursor, flush);
    }
}
