package com.sidekick.render.inline;

import com.sidekick.util.AnsiStyle;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.AbstractWindowsTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Keeps the inline chat frame in JLine's display so its rows move with the editable buffer. */
public final class ChatLineReader extends LineReaderImpl {

    private static final AttributedStyle MUTED = AnsiStyle.isEnabled()
            ? AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK)
            : AttributedStyle.DEFAULT;
    private static final AttributedString RULE = new AttributedString("─", MUTED);
    private static final AttributedString PLACEHOLDER = new AttributedString("试试「解释 @pom.xml」", MUTED);
    private static final AttributedString SHORT_PLACEHOLDER = new AttributedString("输入任务", MUTED);
    private static final int WHEEL_ROWS = 3;

    private final BottomStatusBar statusBar;
    private boolean chatInput;
    private List<AttributedString> turnLines;
    private List<AttributedString> turnAnswer = List.of();
    private boolean turnHasThinking;
    private List<AttributedString> preview = List.of();
    private List<AttributedString> wrappedPreview = List.of();
    private String previewTitle;
    private String previewShortcut;
    private int previewWidth;
    private int previewStart;
    private int previewMaxStart;
    private boolean wheelTracking;

    public ChatLineReader(Terminal terminal) {
        this(terminal, null);
    }

    ChatLineReader(Terminal terminal, BottomStatusBar statusBar) {
        super(terminal, null, null);
        this.statusBar = statusBar;
        getWidgets().put("sidekick-scroll-up", () -> scrollPreview(-1));
        getWidgets().put("sidekick-scroll-down", () -> scrollPreview(1));
        Stream.of(MAIN, EMACS, VIINS, VICMD).map(getKeyMaps()::get).filter(map -> map != null).distinct()
                .forEach(map -> {
                    map.bind(new Reference(MOUSE), "\u001b[<", "\u001b[M");
                    map.bind(new Reference("sidekick-scroll-up"), "\u001b[1;3A");
                    map.bind(new Reference("sidekick-scroll-down"), "\u001b[1;3B");
                });
    }

    public String readChatLine(String prompt) {
        boolean eraseOnFinish = isSet(Option.ERASE_LINE_ON_FINISH);
        chatInput = true;
        option(Option.ERASE_LINE_ON_FINISH, true);
        try {
            return readLine(prompt);
        } finally {
            setWheelTracking(false);
            option(Option.ERASE_LINE_ON_FINISH, eraseOnFinish);
            chatInput = false;
            turnLines = null;
            turnAnswer = List.of();
            turnHasThinking = false;
            clearPreview();
        }
    }

    boolean togglePreview(String title, String shortcut, List<AttributedString> lines) {
        if (!chatInput || !reading) {
            return false;
        }
        boolean wasOpen = shortcut.equals(previewShortcut);
        // Complete the old frame's erase before mouse mode and the frame height change.
        display.update(List.of(), 0);
        clearPreview();
        if (!wasOpen) {
            previewTitle = title;
            previewShortcut = shortcut;
            preview = lines;
            previewStart = "Ctrl+O".equals(shortcut) ? 0 : Integer.MAX_VALUE;
        }
        return true;
    }

    void setTurnLines(List<AttributedString> lines, List<AttributedString> answer, boolean hasThinking) {
        turnLines = lines;
        turnAnswer = answer;
        turnHasThinking = hasThinking;
        clearPreview();
    }

    private void clearPreview() {
        preview = List.of();
        wrappedPreview = List.of();
        previewTitle = null;
        previewShortcut = null;
        previewWidth = 0;
        previewStart = Integer.MAX_VALUE;
        previewMaxStart = 0;
    }

    @Override
    public boolean mouse() {
        MouseEvent event = readMouseEvent();
        if (wheelTracking && event.getType() == MouseEvent.Type.Wheel) {
            scrollPreview(event.getButton() == MouseEvent.Button.WheelUp ? -WHEEL_ROWS : WHEEL_ROWS);
        }
        return true;
    }

    private boolean scrollPreview(int rows) {
        if (previewMaxStart > 0) {
            previewStart = Math.max(0, Math.min(previewMaxStart, previewStart + rows));
        }
        return true;
    }

    private void setWheelTracking(boolean enabled) {
        if (enabled == wheelTracking) {
            return;
        }
        if (!terminal.trackMouse(enabled ? Terminal.MouseTracking.Normal : Terminal.MouseTracking.Off)) {
            return;
        }
        wheelTracking = enabled;
        if (terminal instanceof AbstractWindowsTerminal && "windows-vtp".equals(terminal.getType())) {
            // JLine's Windows backend updates the native console mode but does not notify VT frontends.
            terminal.writer().write(enabled ? "\u001b[?1006h\u001b[?1000h" : "\u001b[?1000l\u001b[?1006l");
            terminal.flush();
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

    int foldedTranscriptRows(Size size) {
        return Math.max(0, conversationRows(size) - inputRows(size));
    }

    private int conversationRows(Size size) {
        int dock = Status.getExistingStatus(terminal).map(Status::size).orElse(0);
        // Leave a physical row for terminal wrap and cursor movement at the lower margin.
        return size.getRows() - dock - 1;
    }

    @Override
    protected void redisplay(boolean flush) {
        lock.lock();
        AttributedString originalPrompt = prompt;
        try {
            ensureTransientDisplay();
            if (showFrame()) {
                prompt = new AttributedStringBuilder().append(rule()).append('\n')
                        .append(originalPrompt).toAttributedString();
            }
            previewMaxStart = 0;
            if (chatInput && reading && (turnLines != null || !preview.isEmpty())) {
                AttributedString prefix = conversationPrefix(inputFrame(new ArrayList<>()));
                if (prefix.length() > 0) {
                    // Put the transcript in the prompt so JLine also counts it when positioning the caret.
                    prompt = new AttributedStringBuilder().append(prefix).append('\n').append(prompt)
                            .toAttributedString();
                }
            }
            setWheelTracking(previewMaxStart > 0);
            super.redisplay(flush);
        } finally {
            prompt = originalPrompt;
            lock.unlock();
        }
    }

    @Override
    public AttributedString getDisplayedBufferWithPrompts(List<AttributedString> secondaryPrompts) {
        // super.redisplay can replace the display when the status first appears, after
        // our redisplay entry point. Install the transient display before it paints.
        ensureTransientDisplay();
        return inputFrame(secondaryPrompts);
    }

    private void ensureTransientDisplay() {
        if (chatInput && display != null && !(display instanceof TransientDisplay)) {
            display.update(List.of(), 0, false);
            var inline = new TransientDisplay(terminal);
            inline.setDelayLineWrap(display.delayLineWrap());
            inline.resize(size.getRows(), size.getColumns());
            display = inline;
        }
    }

    private AttributedString inputFrame(List<AttributedString> secondaryPrompts) {
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
        frame.append('\n').append(rule());
        return frame.toAttributedString();
    }

    private AttributedString conversationPrefix(AttributedString inputFrame) {
        int cols = size.getColumns();
        int inputRows = inputFrame.columnSplitLength(cols, false, true, terminal).size();
        int room = conversationRows(size) - inputRows;
        if (room <= 0 || cols < 2) {
            return AttributedString.EMPTY;
        }
        int width = cols - 1;
        if (previewWidth != width) {
            rebuildPreview(width);
            previewWidth = width;
        }
        int maxRows = turnLines == null ? Math.min(room, Math.max(1, size.getRows() / 2)) : room;
        int visibleRows = Math.min(maxRows, wrappedPreview.size());
        previewMaxStart = wrappedPreview.size() - visibleRows;
        previewStart = Math.min(previewStart, previewMaxStart);
        return joinRows(wrappedPreview.subList(previewStart, previewStart + visibleRows));
    }

    private void rebuildPreview(int width) {
        boolean thinkingOpen = isThinkingPreview();
        List<AttributedString> ordered = new ArrayList<>();
        if (thinkingOpen) {
            ordered.add(AttributedString.fromAnsi(InlineTurnDisplay.thinkingLabel(" . . . < · Ctrl+O 收起")));
            ordered.addAll(preview);
        } else if (turnLines != null && turnHasThinking) {
            ordered.add(InlineTurnDisplay.THINKING);
        }
        if (thinkingOpen && !turnAnswer.isEmpty()) {
            ordered.addAll(turnAnswer);
        } else if (turnLines != null) {
            ordered.addAll(turnLines);
        }
        if (!thinkingOpen && !preview.isEmpty()) {
            addPreview(ordered);
        }
        wrappedPreview = ordered.stream().flatMap(line -> InlineTurnDisplay.wrap(line, width, terminal).stream()).toList();
    }

    private boolean isThinkingPreview() {
        return "Ctrl+O".equals(previewShortcut) && "思考正文".equals(previewTitle);
    }

    private AttributedString joinRows(List<AttributedString> rows) {
        AttributedStringBuilder prefix = new AttributedStringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                prefix.append('\n');
            }
            prefix.append(rows.get(i));
        }
        return prefix.toAttributedString();
    }

    private void addPreview(List<AttributedString> lines) {
        lines.add(new AttributedString(previewTitle + " · " + previewShortcut + " 收起", InlineTheme.TEXT));
        lines.addAll(preview);
    }

    @Override
    protected void cleanup() {
        // ERASE_LINE_ON_FINISH clears the whole editable area, including the retained transcript.
        boolean wasChatInput = chatInput;
        chatInput = false;
        try {
            super.cleanup();
        } finally {
            chatInput = wasChatInput;
        }
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        if (signal != Terminal.Signal.WINCH) {
            super.handleSignal(signal);
            return;
        }
        lock.lock();
        try {
            Size resized = terminal.getBufferSize();
            if (chatInput && reading && display instanceof TransientDisplay) {
                if (resized.getRows() <= 0 || resized.getColumns() <= 0) {
                    return;
                }
                if (!size.equals(resized)) {
                    doAutosuggestion = false;
                    // Keep the display's frame and cursor records. JLine's default WINCH
                    // handler replaces them, and erasing at the old width leaves wrapped rules.
                    display.resize(resized.getRows(), resized.getColumns());
                    display.update(List.of(), 0, false);
                    size.copy(resized);
                    Status.getExistingStatus(terminal).ifPresent(status -> status.resize(resized));
                    if (statusBar != null) {
                        statusBar.flushNow();
                    }
                    redisplay();
                }
                return;
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
