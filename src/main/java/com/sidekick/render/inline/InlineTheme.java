package com.sidekick.render.inline;

import com.sidekick.util.AnsiStyle;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedCharSequence.ForceMode;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Foreground color for conversation UI. Startup and assistant prose bypass this theme. */
final class InlineTheme {
    static final AttributedStyle TEXT = AnsiStyle.isEnabled()
            ? AttributedStyle.DEFAULT.foregroundRgb(AnsiStyle.INTERFACE_RGB)
            : AttributedStyle.DEFAULT;

    // Preserve ANSI cursor controls used by the command palette.
    private static final Pattern CURSOR_CONTROL = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-ln-~]");
    private static final Pattern ALL_TEXT = Pattern.compile("(?s).+");
    private static final String BRIGHT_WHITE_SGR = "\u001B[97m";

    private InlineTheme() {
    }

    static AttributedString foreground(AttributedString text) {
        if (!AnsiStyle.isEnabled() || text.length() == 0) {
            return text;
        }
        return new AttributedStringBuilder().append(text)
                .styleMatches(ALL_TEXT, TEXT.faintOff().inverseOff()).toAttributedString();
    }

    static String format(String text) {
        if (!AnsiStyle.isEnabled() || text.isBlank()) {
            return text;
        }
        // 启动阶段的高优先级提示会显式指定亮白色；不能再被通用的界面灰色覆盖。
        if (text.contains(BRIGHT_WHITE_SGR)) {
            return text;
        }
        var controls = CURSOR_CONTROL.matcher(text);
        StringBuilder output = new StringBuilder();
        int start = 0;
        while (controls.find()) {
            output.append(formatText(text.substring(start, controls.start()))).append(controls.group());
            start = controls.end();
        }
        return output.append(formatText(text.substring(start))).toString();
    }

    private static String formatText(String text) {
        // Leave line endings outside SGR, including for JLine's printAbove().
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return foreground(AttributedString.fromAnsi(text.substring(0, end)))
                .toAnsi(1 << 24, ForceMode.None) + text.substring(end);
    }

    static PrintStream stream(PrintStream terminalOutput) {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int count) {
                terminalOutput.print(format(new String(bytes, offset, count, StandardCharsets.UTF_8)));
            }

            @Override
            public void flush() {
                terminalOutput.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }
}
