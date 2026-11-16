package com.sidekick.render.inline;

import com.sidekick.util.AnsiStyle;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Macro;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Status;

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

    private final BottomStatusBar statusBar;
    private boolean chatInput;
    private List<AttributedString> preview = List.of();
    private List<AttributedString> wrappedPreview = List.of();
    private String previewTitle;
    private String previewShortcut;
    private int previewWidth;
    private int previewStart;
    private int previewPageRows;

    public ChatLineReader(Terminal terminal) {
        this(terminal, null);
    }

    ChatLineReader(Terminal terminal, BottomStatusBar statusBar) {
        super(terminal, null, null);
        this.statusBar = statusBar;
        bindPreviewPage(Capability.key_ppage, -1);
        bindPreviewPage(Capability.key_npage, 1);
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
            clearPreview();
        }
    }

    boolean togglePreview(String title, String shortcut, List<AttributedString> lines) {
        if (!chatInput || !reading) {
            return false;
        }
        boolean wasOpen = shortcut.equals(previewShortcut);
        clearPreview();
        if (!wasOpen) {
            previewTitle = title;
            previewShortcut = shortcut;
            preview = lines;
        }
        return true;
    }

    private void clearPreview() {
        preview = List.of();
        wrappedPreview = List.of();
        previewTitle = null;
        previewShortcut = null;
        previewWidth = 0;
        previewStart = 0;
        previewPageRows = 0;
    }

    private void bindPreviewPage(Capability key, int direction) {
        String sequence = KeyMap.key(terminal, key);
        if (sequence == null || sequence.isEmpty()) {
            return;
        }
        Stream.of(MAIN, EMACS, VIINS).map(getKeyMaps()::get).filter(map -> map != null).distinct()
                .forEach(map -> {
                    Binding previous = map.getBound(sequence);
                    String widget = "sidekick-preview-" + key + "-" + getWidgets().size();
                    getWidgets().put(widget, () -> {
                        if (previewPageRows > 0) {
                            int last = Math.max(0, wrappedPreview.size() - previewPageRows);
                            previewStart = Math.max(0, Math.min(last, previewStart + direction * previewPageRows));
                            return true;
                        }
                        if (previous instanceof Reference ref) {
                            callWidget(ref.name());
                        } else if (previous instanceof Macro macro) {
                            runMacro(macro.getSequence());
                        }
                        return true;
                    });
                    map.bind(new Reference(widget), sequence);
                });
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
        frame.append('\n').append(rule());
        appendPreview(frame);
        return frame.toAttributedString();
    }

    private void appendPreview(AttributedStringBuilder frame) {
        previewPageRows = 0;
        if (preview.isEmpty()) {
            return;
        }
        int cols = size.getColumns();
        int dock = Status.getExistingStatus(terminal).map(Status::size).orElse(0);
        int inputRows = frame.toAttributedString().columnSplitLength(cols, false, true, terminal).size();
        int room = size.getRows() - dock - inputRows - 1;
        if (room <= 0) {
            return;
        }
        int width = cols - 1;
        if (previewWidth != width) {
            wrappedPreview = preview.stream()
                    .flatMap(line -> line.columnSplitLength(width, false, true, terminal).stream())
                    .toList();
            previewWidth = width;
        }
        previewPageRows = Math.min(Math.min(room, Math.max(1, size.getRows() / 2)), wrappedPreview.size());
        previewStart = Math.min(previewStart, wrappedPreview.size() - previewPageRows);
        String heading = previewTitle + " · " + previewShortcut + " 收起";
        if (wrappedPreview.size() > previewPageRows) {
            heading += " · PgUp/PgDn";
        }
        AttributedString title = new AttributedString(heading, InlineActivityDisplay.THINKING_STYLE);
        frame.append('\n').append(title.columnSubSequence(0, width, terminal));
        for (int i = previewStart; i < previewStart + previewPageRows; i++) {
            frame.append('\n').append(wrappedPreview.get(i));
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
