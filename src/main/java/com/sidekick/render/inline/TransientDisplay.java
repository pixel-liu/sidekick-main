package com.sidekick.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

import java.util.List;

/** A JLine frame whose rows must never be pushed into the terminal's scrollback. */
final class TransientDisplay extends Display {
    TransientDisplay(Terminal terminal) {
        super(terminal, false);
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
