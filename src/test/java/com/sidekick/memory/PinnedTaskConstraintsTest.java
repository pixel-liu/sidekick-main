package com.sidekick.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PinnedTaskConstraintsTest {
    @Test
    void preservesVerbatimTextAndReplacesOrRemovesById() {
        PinnedTaskConstraints constraints = new PinnedTaskConstraints();
        String original = "  保留缩进  \r\n第二行  ";
        String history = constraints.register("普通讨论\r\n<!-- sidekick:pin R1 -->\r\n" + original
                + "\r\n<!-- /sidekick:pin -->\r\n继续讨论");
        assertTrue(history.contains("普通讨论\r\n[固定条款 R1"));
        assertTrue(history.endsWith("继续讨论"));
        assertFalse(history.contains(original));
        assertTrue(constraints.requestMessage().content().contains(original));

        constraints.register(pin("R1", "替换后的决定"));
        assertTrue(constraints.requestMessage().content().contains("替换后的决定"));
        assertFalse(constraints.requestMessage().content().contains(original));

        constraints.register("<!-- sidekick:unpin R1 -->");
        assertNull(constraints.requestMessage());
    }

    @Test
    void rejectsOverLimitOrUnclosedBatchesWithoutApplyingAnyDirective() {
        PinnedTaskConstraints constraints = new PinnedTaskConstraints();
        constraints.register(pin("R1", "保留这条"));
        String original = constraints.requestMessage().content();

        assertThrows(IllegalArgumentException.class, () -> constraints.register(
                "<!-- sidekick:unpin R1 -->\n" + pin("R2", "界".repeat(2_731))));
        assertThrows(IllegalArgumentException.class, () -> constraints.register(
                pin("R1", "不应覆盖") + "\n<!-- sidekick:pin R2 -->\n缺少结束标记"));
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            tooMany.append(pin("x" + i, "另一条")).append('\n');
        }
        assertThrows(IllegalArgumentException.class, () -> constraints.register(tooMany.toString()));
        assertEquals(original, constraints.requestMessage().content());
    }

    private static String pin(String id, String body) {
        return "<!-- sidekick:pin " + id + " -->\n" + body + "\n<!-- /sidekick:pin -->";
    }
}
