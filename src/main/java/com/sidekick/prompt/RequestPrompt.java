package com.sidekick.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidekick.context.ContextProfile;
import com.sidekick.llm.LlmClient;
import com.sidekick.memory.ConversationHistoryCompactor;
import com.sidekick.memory.MemoryEntry;
import com.sidekick.memory.MemoryRetriever.RecallContext;
import com.sidekick.memory.TokenBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** System-prompt snapshot for one Agent run or plan task. */
public final class RequestPrompt {
    private static final Logger log = LoggerFactory.getLogger(RequestPrompt.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final double IMPORTANT_MEMORY = 0.8;
    // A marked block must end before the next marker or project-memory source heading.
    private static final Pattern PROJECT_MARKER = Pattern.compile(
            "(?m)^(?:(<!-- sidekick:optional -->)|(?<end><!-- /sidekick:optional -->)|### (?:[A-Za-z]:[\\\\/]|[\\\\/]).*)"
                    + "[ \\t]*(?:\\R|\\z)");

    private final String prefix;
    private final String suffix;
    private final PromptContext context;
    private final RecallContext recall;
    private final List<MemoryEntry> memories;
    private String projectMemory;
    private String skillIndex;
    private String text;
    private boolean recallsRecorded;

    RequestPrompt(String prefix, String suffix, PromptContext context, RecallContext recall) {
        this.prefix = prefix;
        this.suffix = suffix;
        this.context = context;
        this.recall = recall;
        this.memories = recall == null ? new ArrayList<>() : new ArrayList<>(recall.entries());
        this.projectMemory = context.projectMemoryContext() == null ? "" : context.projectMemoryContext();
        this.skillIndex = context.skillIndex() == null ? "" : context.skillIndex();
        this.text = render();
    }

    public String text() {
        return text;
    }

    public PreparedMessages prepareMessages(List<LlmClient.Message> history, ConversationHistoryCompactor compactor,
                                            List<LlmClient.Tool> tools, ContextProfile profile) throws IOException {
        List<LlmClient.Message> recovered = compactor.withRecoveryHistory(history);
        boolean expanded = recovered != history || (compactor.isRecovering() && compactor.hasExtraTurns(history));
        List<LlmClient.Message> messages = compactor.withPinnedConstraints(recovered);
        boolean pinned = messages != recovered;
        boolean trimmed = trimIfNeeded(messages, tools, profile);
        if (trimmed && messages != history) {
            history.set(0, messages.get(0));
        }
        if ((expanded || pinned) && exceedsInputBudget(messages, tools, profile)) {
            if (expanded) {
                log.info("Recovery history exceeds the input budget; using the default turn limit");
                messages = compactor.withPinnedConstraints(history);
            }
            if (exceedsInputBudget(messages, tools, profile) && compactor.hasExtraTurns(history)) {
                compactor.compactToDefault(history);
                messages = compactor.withPinnedConstraints(history);
            }
            if (exceedsInputBudget(messages, tools, profile)) {
                throw new IOException("上下文超出输入预算，无法发送保留的对话和工具消息"
                        + (pinned ? "（含固定条款）" : ""));
            }
        }
        return new PreparedMessages(messages, trimmed);
    }

    public record PreparedMessages(List<LlmClient.Message> messages, boolean trimmed) {}

    private boolean exceedsInputBudget(List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                                       ContextProfile profile) {
        return (long) TokenBudget.estimateMessagesTokens(messages) + estimateToolsTokens(tools) > inputBudget(profile);
    }

    private static int inputBudget(ContextProfile profile) {
        return Math.max(1, profile.maxContextWindow() - TokenBudget.DEFAULT_RESPONSE_RESERVE);
    }

    /** Apply emergency trimming after history compaction, before dispatching the request. */
    public boolean trimIfNeeded(List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                                ContextProfile profile) {
        int budget = inputBudget(profile);
        int toolsTokens = estimateToolsTokens(tools);
        long before = (long) TokenBudget.estimateMessagesTokens(messages) + toolsTokens;
        long remaining = before;
        boolean trimmed = false;

        while (remaining > budget && dropNextOptionalPart()) {
            int previousPromptTokens = MemoryEntry.estimateTokens(text);
            text = render();
            remaining += MemoryEntry.estimateTokens(text) - previousPromptTokens;
            trimmed = true;
        }
        if (trimmed) {
            messages.set(0, LlmClient.Message.system(text));
            log.info("Emergency context trim: {} -> {} estimated input tokens, budget={}", before, remaining, budget);
        }
        if (remaining > budget) {
            log.warn("Context remains above estimated input budget after optional trimming: {} > {}", remaining, budget);
        }
        return trimmed;
    }

    /** Count only memories still present when the first request of this run is dispatched. */
    public void recordRecalls() {
        if (recall != null && !recallsRecorded) {
            recall.recordAccess(memories);
            recallsRecorded = true;
        }
    }

    private boolean dropNextOptionalPart() {
        if (!skillIndex.isBlank()) {
            skillIndex = "";
            return true;
        }
        // Entries are ranked by relevance. Keep the best match and explicitly important facts.
        for (int i = memories.size() - 1; i > 0; i--) {
            if (memories.get(i).getImportance() < IMPORTANT_MEMORY) {
                memories.remove(i);
                return true;
            }
        }
        return dropLastOptionalProjectBlock();
    }

    private boolean dropLastOptionalProjectBlock() {
        Matcher marker = PROJECT_MARKER.matcher(projectMemory);
        int open = -1;
        int start = -1;
        int end = -1;
        while (marker.find()) {
            if (marker.group(1) != null) {
                open = marker.start();
            } else {
                if (open >= 0 && marker.group("end") != null) {
                    start = open;
                    end = marker.end();
                }
                open = -1;
            }
        }
        if (start < 0) {
            return false;
        }
        projectMemory = projectMemory.substring(0, start) + projectMemory.substring(end);
        return true;
    }

    private String render() {
        StringBuilder prompt = new StringBuilder(prefix);
        String memoryText = recall == null ? context.memoryContext() : RecallContext.render(memories);
        PromptAssembler.append(prompt, PromptAssembler.dynamicSection("Project Context",
                projectMemory, memoryText, context.externalContext()));
        PromptAssembler.append(prompt, PromptAssembler.dynamicSection("Skills", skillIndex));
        PromptAssembler.append(prompt, suffix);
        return prompt.toString();
    }

    public static int estimateToolsTokens(List<LlmClient.Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return 0;
        }
        try {
            return MemoryEntry.estimateTokens(mapper.writeValueAsString(tools));
        } catch (JsonProcessingException e) {
            log.debug("Failed to estimate tools schema tokens", e);
            return 0;
        }
    }
}
