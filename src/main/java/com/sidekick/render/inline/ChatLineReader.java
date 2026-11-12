package com.sidekick.render.inline;

import com.sidekick.util.AnsiStyle;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.List;

/** Keeps the inline chat frame in JLine's display so its rows move with the editable buffer. */
public final class ChatLineReader extends LineReaderImpl {

    private static final AttributedStyle MUTED = AnsiStyle.isEnabled()
            ? AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK)
            : AttributedStyle.DEFAULT;
    private static final AttributedString RULE = new AttributedString("─", MUTED);
    private static final AttributedString PLACEHOLDER = new AttributedString("试试「解释 @pom.xml」", MUTED);
    private static final AttributedString SHORT_PLACEHOLDER = new AttributedString("输入任务", MUTED);

    private final BottomStatusBar statusBar;
    private boolean chatInput;

    public ChatLineReader(Terminal terminal) {
        this(terminal, null);
    }

    ChatLineReader(Terminal terminal, BottomStatusBar statusBar) {
        super(terminal, null, null);
        this.statusBar = statusBar;
    }

    public String readChatLine(String prompt) {
        boolean eraseOnFinish = isSet(Option.ERASE_LINE_ON_FINISH);
        chatInput = true;
        option(Option.ERASE_LINE_ON_FINISH, true);
        try {
            return readLine(prompt);
        } finally {
            option(Option.ERASE_LINE_ON_FINISH, eraseOnFinish);
            chatInput = false;
        }
    }

    @Override
    public boolean beginPaste() {
        // JLine converts every CR to LF. Fold CRLF first to preserve pasted Windows line breaks.
        String pasted = doReadStringUntil("\u001b[201~");
        regionActive = RegionType.PASTE;
        regionMark = getBuffer().cursor();
        getBuffer().write(pasted.replace("\r\n", "\n").replace('\r', '\n'));
        return true;
    }

    static int inputRows(Size size) {
        return size.getRows() >= 5 && size.getColumns() >= 20 ? 3 : 1;
    }

    @Override
    protected void redisplay(boolean flush) {
        lock.lock();
        AttributedString originalPrompt = prompt;
        try {
            if (showFrame()) {
                prompt = new AttributedStringBuilder().append(rule()).append('\n')
                        .append(originalPrompt).toAttributedString();
            }
            super.redisplay(flush);
        } finally {
            prompt = originalPrompt;
            lock.unlock();
        }
    }

    @Override
    public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
        if (!showFrame()) {
            return super.getDisplayedBufferWithPrompts(secondaryPrompts);
        }
        AttributedStringBuilder frame = new AttributedStringBuilder();
        if (getBuffer().length() == 0 && post == null && searchTerm == null) {
            setTailTip("");
            // The prompt includes the top rule. Measure only the line that contains the cursor.
            int inputStart = prompt.toString().lastIndexOf('\n') + 1;
            int room = Math.max(0, size.getColumns() - 1 - prompt.subSequence(inputStart, prompt.length())
                    .columnLength(terminal));
            frame.append(prompt).append(room >= PLACEHOLDER.columnLength(terminal) ? PLACEHOLDER : SHORT_PLACEHOLDER);
        } else {
            frame.append(super.getDisplayedBufferWithPrompts(secondaryPrompts));
        }
        return frame.append('\n').append(rule()).toAttributedString();
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        if (signal != Terminal.Signal.WINCH) {
            super.handleSignal(signal);
            return;
        }
        lock.lock();
        try {
            if (chatInput && reading && display != null && !size.equals(terminal.getBufferSize())) {
                // JLine replaces its display on resize. Erase the old frame before it loses those rows.
                display.update(List.of(), 0, false);
            }
            super.handleSignal(signal);
            if (statusBar != null) {
                statusBar.flushNow();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean showFrame() {
        return chatInput && reading && inputRows(size) == 3;
    }

    private AttributedString rule() {
        int ruleWidth = RULE.columnLength(terminal);
        return new AttributedString(RULE.toString().repeat((size.getColumns() - 1) / ruleWidth), MUTED);
    }
}
