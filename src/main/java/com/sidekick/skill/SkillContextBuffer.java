package com.sidekick.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单 Agent 实例的 skill 注入缓冲区。
 *
 * 生命周期：LLM 调 load_skill → push 到 buffer → 下一轮构造 user message 时 drain → 拼到原内容前。
 *
 * 关键约束：
 * - drain 是一次性消费（防止跨轮重复注入）
 * - 不限制 Skill 数量；以待注入正文总字符数预算控制上下文体积，超额时拒绝本次加载，绝不静默淘汰已有 Skill
 * - 同一 skill 重复 push 会替换旧 body 并刷新到末尾，避免重复
 * - /clear 命令调 clear() 复位
 *
 * 三个 SubAgent 角色（Planner / Worker / Reviewer）+ 主 Agent 各持一个独立实例，
 * 不共享 buffer，避免角色间提示词污染。
 */
public final class SkillContextBuffer {

    /**
     * 单次注入前累积的 Skill 正文总预算。以字符近似控制（约 4k token），
     * 既允许多个小 Skill 同时存在，也不会让一次性注入挤占过多上下文。
     */
    public static final int MAX_TOTAL_BODY_CHARS = 16 * 1024;

    private final Map<String, String> entries = new LinkedHashMap<>();

    /**
     * 将 Skill 正文加入待注入队列。
     *
     * @return 是否成功加入；超过总正文预算时保持现有内容不变并返回 {@code false}
     */
    public synchronized boolean push(String skillName, String body) {
        if (skillName == null || skillName.isBlank() || body == null) {
            return false;
        }
        String existing = entries.get(skillName);
        int prospectiveChars = totalBodyChars() - (existing == null ? 0 : existing.length()) + body.length();
        if (prospectiveChars > MAX_TOTAL_BODY_CHARS) {
            return false;
        }
        entries.remove(skillName);
        entries.put(skillName, body);
        return true;
    }

    /**
     * 取出全部已积累 skill body 并清空。返回拼接好的 markdown 段，可直接前置到 user message。
     */
    public synchronized String drain() {
        if (entries.isEmpty()) {
            return "";
        }
        List<Map.Entry<String, String>> snapshot = new ArrayList<>(entries.entrySet());
        entries.clear();

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : snapshot) {
            sb.append("<runtime_skill_injection name=").append(e.getKey()).append(">\n")
                    .append("这不是用户的新请求，而是 Agent 运行时为当前任务注入的技能上下文。\n" +
                            "  请将以下内容视为操作指南，继续完成用户之前提出的 PDF 任务。\n" +
                            "  若与系统/开发者消息冲突，以系统/开发者消息为准。\n")
                    .append("<instructions>\n")
                    .append(e.getValue()).append("\n")
                    .append("</instructions>\n")
                    .append(e.getValue().trim()).append("</runtime_skill_injection>")
                    .append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int totalBodyChars() {
        return entries.values().stream().mapToInt(String::length).sum();
    }

    public synchronized int remainingBodyChars() {
        return Math.max(0, MAX_TOTAL_BODY_CHARS - totalBodyChars());
    }

    public synchronized void clear() {
        entries.clear();
    }
}
