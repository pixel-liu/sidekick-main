package com.sidekick.render.inline;

import com.sidekick.render.StatusInfo;
import com.sidekick.util.AnsiStyle;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineActivityDisplayTest {

    @Test
    void wrappedThinkingLivesInReservedRowsAndCollapseClearsThem() throws Exception {
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        try (Terminal terminal = new DumbTerminal("test", "xterm-256color",
                InputStream.nullInputStream(), screen, StandardCharsets.UTF_8);
             BottomStatusBar dock = new BottomStatusBar(terminal);
             InlineActivityDisplay display = new InlineActivityDisplay(terminal, dock)) {
            terminal.setSize(new Size(32, 7));
            dock.start();
            dock.update(StatusInfo.idle("test-model", 10_000, false));
            display.begin("Thinking");
            screen.reset();
            String reasoning = "起始".repeat(2300) + "\n末尾".repeat(30);
            display.appendThinking(reasoning);

            Status status = Status.getStatus(terminal);
            assertEquals(5, status.size());
            String rendered = screen.toString(StandardCharsets.UTF_8);
            assertTrue(rendered.contains("末尾"), rendered);
            if (AnsiStyle.isEnabled()) {
                AttributedString gray = AttributedString.fromAnsi(AnsiStyle.readableGray("末尾"));
                assertTrue(rendered.contains(gray.toAnsi(terminal).substring(0, gray.toAnsi(terminal).indexOf("末尾"))));
            }
            assertFalse(rendered.contains("\u001B[2m"), rendered);

            screen.reset();
            assertEquals(reasoning, display.collapse());
            assertEquals(1, status.size());
            String cleared = screen.toString(StandardCharsets.UTF_8);
            assertTrue(cleared.contains(AnsiSeq.setScrollRegion(1, 6)), cleared);
            assertTrue(cleared.contains(AnsiSeq.CLEAR_TO_EOL), cleared);
            assertFalse(cleared.contains("末尾"), cleared);
            assertEquals("", display.collapse());
        }
    }
}
