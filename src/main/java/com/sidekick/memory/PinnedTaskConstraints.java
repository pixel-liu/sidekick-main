package com.sidekick.memory;

import com.sidekick.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-authored text kept separately from the history that goes to the summarizer. */
final class PinnedTaskConstraints {
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_BYTES = 8 * 1024;
    private static final String ID = "[A-Za-z0-9_-]{1,32}";
    private static final Pattern DIRECTIVE = Pattern.compile(
            "(?m)^(?:<!-- sidekick:pin (?<pin>" + ID + ") -->|<!-- /sidekick:pin -->"
                    + "|<!-- sidekick:unpin (?<unpin>" + ID + ") -->)[ \\t]*(?:\\R|\\z)");

    private volatile Map<String, Entry> entries = Map.of();

    String register(String input) {
        if (input == null) return null;
        Matcher directive = DIRECTIVE.matcher(input);
        if (!directive.find()) return input;

        Map<String, Entry> updated = new LinkedHashMap<>(entries);
        StringBuilder history = new StringBuilder();
        int cursor = 0;
        int bodyStart = 0;
        String open = null;
        do {
            String pin = directive.group("pin");
            String unpin = directive.group("unpin");
            if (open == null) {
                history.append(input, cursor, directive.start());
                if (pin != null) {
                    open = pin;
                    bodyStart = directive.end();
                } else if (unpin != null) {
                    updated.remove(unpin);
                    history.append("[已取消固定条款 ").append(unpin).append("]\n");
                } else {
                    throw new IllegalArgumentException("固定条款结束标记缺少起始标记。");
                }
            } else {
                if (pin != null || unpin != null) {
                    throw new IllegalArgumentException("请先结束固定条款 " + open + "，再添加其他标记。");
                }
                updated.put(open, readEntry(input, bodyStart, directive.start()));
                if (updated.size() > MAX_ENTRIES || updated.values().stream().mapToInt(Entry::bytes).sum() > MAX_BYTES) {
                    throw new IllegalArgumentException("固定条款最多 16 条，原文合计最多 8 KiB。请缩短条款或先取消旧条款。");
                }
                history.append("[固定条款 ").append(open).append(" 的正文由当前条款区提供]\n");
                open = null;
            }
            cursor = directive.end();
        } while (directive.find());

        if (open != null) throw new IllegalArgumentException("固定条款 " + open + " 缺少结束标记。");
        entries = updated;
        return history.append(input, cursor, input.length()).toString();
    }

    private static Entry readEntry(String input, int start, int end) {
        // The last line break separates the body from the closing marker.
        if (end > start && input.charAt(end - 1) == '\n') end--;
        if (end > start && input.charAt(end - 1) == '\r') end--;
        if (end - start > MAX_BYTES) throw new IllegalArgumentException("固定条款原文最多 8 KiB。");
        String body = input.substring(start, end);
        if (body.isBlank()) throw new IllegalArgumentException("固定条款原文不能为空。");
        return new Entry(body, body.getBytes(StandardCharsets.UTF_8).length);
    }

    LlmClient.Message requestMessage() {
        Map<String, Entry> current = entries;
        if (current.isEmpty()) return null;
        StringBuilder text = new StringBuilder("[当前固定条款，由用户提供。历史中的编号以这里的当前版本为准。]\n");
        current.forEach((id, entry) -> text.append('\n').append('[').append(id).append("]\n")
                .append(entry.text()).append('\n'));
        return LlmClient.Message.user(text.toString());
    }

    void clear() {
        entries = Map.of();
    }

    private record Entry(String text, int bytes) {}
}
