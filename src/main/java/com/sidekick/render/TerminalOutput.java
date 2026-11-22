package com.sidekick.render;

import org.jline.terminal.Terminal;
import org.jline.utils.WriterOutputStream;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Keeps Unicode intact when Windows stdout uses a legacy console encoding. */
public final class TerminalOutput {
    private TerminalOutput() {
    }

    public static PrintStream stream(Terminal terminal) {
        PrintWriter writer = terminal == null ? null : terminal.writer();
        if (writer == null) {
            return System.out;
        }
        // JLine's Windows writer uses the native Unicode console API. Going through
        // System.out or terminal.encoding() first can replace emoji with '?'.
        return new PrintStream(new WriterOutputStream(writer, StandardCharsets.UTF_8),
                true, StandardCharsets.UTF_8);
    }
}
