package com.sidekick.render;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TerminalOutputTest {
    @Test
    void preservesMultibyteCharactersAcrossWritesAndFlushes() {
        Terminal terminal = Mockito.mock(Terminal.class);
        StringWriter console = new StringWriter();
        Mockito.when(terminal.writer()).thenReturn(new PrintWriter(console));
        PrintStream stream = TerminalOutput.stream(terminal);
        String text = "⏳ 启动中 ⚠️ ✅ ❯";
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            stream.write(value & 0xff);
            stream.flush();
        }
        assertEquals(text, console.toString());
    }

    @Test
    void withoutTerminalUsesExistingStdout() {
        assertSame(System.out, TerminalOutput.stream(null));
    }
}
