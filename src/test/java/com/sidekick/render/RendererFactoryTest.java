package com.sidekick.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererFactoryTest {

    @ParameterizedTest
    @EnumSource(RendererFactory.Mode.class)
    void terminalOutputPreservesSymbolsWithLegacyWindowsStdout(RendererFactory.Mode mode) {
        Terminal terminal = Mockito.mock(Terminal.class);
        StringWriter console = new StringWriter();
        Mockito.when(terminal.writer()).thenReturn(new PrintWriter(console));
        Mockito.when(terminal.getType()).thenReturn("windows-vtp");
        Mockito.when(terminal.getSize()).thenReturn(new Size(120, 40));
        String progress = "⏳ chrome-devtools stdio 启动中...（已等待 5s）";
        String warning = "⚠️ MCP 启动超过 8s，先进入 CLI；后台继续启动: chrome-devtools";
        ByteArrayOutputStream legacyBytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream legacy = new PrintStream(legacyBytes, true, Charset.forName("GBK"))) {
            System.setOut(legacy);
            try (Renderer renderer = RendererFactory.create(mode, terminal)) {
                renderer.stream().println(progress);
                renderer.stream().println(warning);
                renderer.stream().flush();
                assertTrue(console.toString().contains(progress), console.toString());
                assertTrue(console.toString().contains(warning), console.toString());
                assertEquals(0, legacyBytes.size(), "终端输出应绕开会丢失符号的 System.out 编码");
            }
        } finally {
            System.setOut(original);
        }
    }

    private String savedProp;

    @BeforeEach
    void saveProp() {
        savedProp = System.getProperty("Sidekick.renderer");
    }

    @AfterEach
    void restoreProp() {
        if (savedProp == null) {
            System.clearProperty("Sidekick.renderer");
        } else {
            System.setProperty("Sidekick.renderer", savedProp);
        }
    }

    @Test
    void defaultsToInlineWhenUnset() {
        System.clearProperty("Sidekick.renderer");
        // We can't easily clear env vars in tests; only verify property path
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void propertyValueLanternaResolves() {
        System.setProperty("Sidekick.renderer", "lanterna");
        assertEquals(RendererFactory.Mode.LANTERNA, RendererFactory.resolveMode());
    }

    @Test
    void propertyValuePlainResolves() {
        System.setProperty("Sidekick.renderer", "plain");
        assertEquals(RendererFactory.Mode.PLAIN, RendererFactory.resolveMode());
    }

    @Test
    void propertyValueIsCaseInsensitive() {
        System.setProperty("Sidekick.renderer", "LANTERNA");
        assertEquals(RendererFactory.Mode.LANTERNA, RendererFactory.resolveMode());
    }

    @Test
    void unknownValueFallsBackToInline() {
        System.setProperty("Sidekick.renderer", "weird");
        assertEquals(RendererFactory.Mode.INLINE, RendererFactory.resolveMode());
    }

    @Test
    void tuiAliasResolvesToLanterna() {
        System.setProperty("Sidekick.renderer", "tui");
        assertEquals(RendererFactory.Mode.LANTERNA, RendererFactory.resolveMode());
    }

    @Test
    void createPlainReturnsPlainRenderer() {
        Renderer renderer = RendererFactory.create(RendererFactory.Mode.PLAIN, null);
        assertInstanceOf(PlainRenderer.class, renderer);
    }

    @Test
    void createInlineReturnsRendererInstance() {
        // Day 1 stub still returns PlainRenderer; Day 2 will swap to InlineRenderer.
        Renderer renderer = RendererFactory.create(RendererFactory.Mode.INLINE, null);
        assertInstanceOf(PlainRenderer.class, renderer);
    }
}
