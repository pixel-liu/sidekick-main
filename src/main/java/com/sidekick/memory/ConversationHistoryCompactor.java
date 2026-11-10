package com.sidekick.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sidekick.llm.LlmClient;
import com.sidekick.tool.ToolRegistry.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Compacts the messages sent to the model. Each instance belongs to one conversation.
 * Turn boundaries come from the agent entry point so injected messages and tool exchanges stay in their turn.
 */
public class ConversationHistoryCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryCompactor.class);

    private static final int DEFAULT_RETAIN_RECENT_ROUNDS = 3;
    private static final int RECOVERY_EXTRA_ROUNDS = 2;
    private static final int RECOVERY_CLEAN_ROUNDS = 2;
    private static final int MAX_SUMMARY_INPUT_CHARS = 60_000;
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SUMMARY_PROMPT = """
            请把下面的对话历史压缩成简明摘要，保留：
            1. 用户提出的关键诉求与目标
            2. Agent 已经完成的关键操作（哪些工具调用了什么、返回了什么核心结果）
            3. 已经达成的共识或结论
            4. 仍未解决的问题或待办

            不要复述每条原文，不要列举所有工具调用，不要保留无关闲聊。
            固定条款的正文会单独提供。摘要可以引用条款编号，勿推测占位符所代表的正文。
            输出 1-3 段中文，不要用列表，不要加任何前缀或元描述。

            === 待压缩的对话 ===
            %s
            === 待压缩的对话（结束）===
            """;

    private LlmClient llmClient;
    private final int retainRecentRounds;
    private final List<Integer> turnStarts = new ArrayList<>();
    private final Deque<List<LlmClient.Message>> archivedTurns = new ArrayDeque<>();
    private final PinnedTaskConstraints pinnedConstraints = new PinnedTaskConstraints();
    private int summaryIndex = -1;
    private ToolOperation previousFailure;
    private int recoveryRoundsRemaining;
    private boolean anomalyInCurrentTurn;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this(llmClient, DEFAULT_RETAIN_RECENT_ROUNDS);
    }

    public ConversationHistoryCompactor(LlmClient llmClient, int retainRecentRounds) {
        this.llmClient = llmClient;
        this.retainRecentRounds = Math.max(1, retainRecentRounds);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public void startTurn(List<LlmClient.Message> history, LlmClient.Message input) {
        turnStarts.add(history.size());
        history.add(input);
        anomalyInCurrentTurn = false;
    }

    /** Accept directives only from the actual user input, before tools or agents can add messages. */
    public String registerPinnedConstraints(String userInput) {
        return pinnedConstraints.register(userInput);
    }

    public List<LlmClient.Message> withPinnedConstraints(List<LlmClient.Message> messages) {
        LlmClient.Message constraints = pinnedConstraints.requestMessage();
        if (constraints == null) return messages;
        List<LlmClient.Message> request = new ArrayList<>(messages);
        int insertAt = !request.isEmpty() && "system".equals(request.get(0).role()) ? 1 : 0;
        request.add(insertAt, constraints);
        return request;
    }

    public void completeTurn() {
        if (isRecovering() && !anomalyInCurrentTurn) {
            recoveryRoundsRemaining--;
        }
    }

    public void recordToolResult(ToolExecutionResult result) {
        if (!result.failedOrTimedOut()) {
            previousFailure = null;
            return;
        }
        String arguments = result.argumentsJson();
        Object normalizedArguments = arguments;
        if (arguments != null) {
            try {
                normalizedArguments = mapper.readTree(arguments);
            } catch (JsonProcessingException ignored) {
                // An invalid JSON call can still be retried verbatim.
            }
        }
        ToolOperation operation = new ToolOperation(result.name(), normalizedArguments);
        if (operation.equals(previousFailure)) {
            recoveryRoundsRemaining = RECOVERY_CLEAN_ROUNDS;
            anomalyInCurrentTurn = true;
        }
        previousFailure = operation;
    }

    public boolean isRecovering() {
        return recoveryRoundsRemaining > 0;
    }

    public boolean hasExtraTurns(List<LlmClient.Message> history) {
        return turnIndices(history).size() > retainRecentRounds;
    }

    public void clear() {
        turnStarts.clear();
        archivedTurns.clear();
        pinnedConstraints.clear();
        summaryIndex = -1;
        previousFailure = null;
        recoveryRoundsRemaining = 0;
        anomalyInCurrentTurn = false;
    }

    /** Adds only the archived turns needed for recovery to a temporary request. */
    public List<LlmClient.Message> withRecoveryHistory(List<LlmClient.Message> history) {
        if (!isRecovering() || archivedTurns.isEmpty()) return history;
        int count = Math.min(archivedTurns.size(), recoveryRetainRounds() - turnIndices(history).size());
        if (count <= 0) return history;

        List<LlmClient.Message> request = new ArrayList<>(history);
        LlmClient.Message summary = history.get(summaryIndex);
        request.set(summaryIndex, LlmClient.Message.user(summary.content()
                + "\n\n[接下来补回 " + count + " 轮已被此摘要覆盖的对话原文，重叠内容的细节以原文为准。]"));
        int insertAt = summaryIndex + 2;
        int skip = archivedTurns.size() - count;
        for (List<LlmClient.Message> turn : archivedTurns) {
            if (skip-- > 0) continue;
            request.addAll(insertAt, turn);
            insertAt += turn.size();
        }
        return request;
    }

    /**
     * 评估并按需压缩 history，原地修改。
     *
     * @param history       Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @param triggerTokens 触发压缩的 token 阈值（通常是 ContextProfile.compressionTriggerTokens()）
     * @return 是否真的压缩了
     */
    public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        int retain = isRecovering() ? recoveryRetainRounds() : retainRecentRounds;
        return compact(history, triggerTokens, false, retain);
    }

    public boolean compactToDefault(List<LlmClient.Message> history) {
        return compact(history, 0, true, retainRecentRounds);
    }

    /**
     * 手动压缩 history，跳过 token 阈值判断，但仍保留最近轮次和 user 边界切割保护。
     *
     * @param history Agent 主循环的 conversationHistory，调用结束后可能被替换为更短列表
     * @return 是否真的压缩了
     */
    public boolean compactNow(List<LlmClient.Message> history) {
        return compact(history, 0, true, 1);
    }

    private boolean compact(List<LlmClient.Message> history, int triggerTokens, boolean force, int retainRounds) {
        if (history == null || history.isEmpty()) return false;
        int currentTokens = TokenBudget.estimateMessagesTokens(withPinnedConstraints(history));
        if (!force && currentTokens < triggerTokens) return false;

        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;

        List<Integer> indices = turnIndices(history);
        int splitTurn = indices.size() - retainRounds;
        if (splitTurn <= 0) return false;
        int splitIdx = indices.get(splitTurn);
        if (splitIdx <= systemEnd) return false;

        List<LlmClient.Message> oldMsgs = new ArrayList<>(history.subList(systemEnd, splitIdx));
        if (oldMsgs.isEmpty()) return false;

        String summary;
        try {
            summary = summarize(oldMsgs);
        } catch (IOException e) {
            log.warn("conversation summary LLM call failed; skip compaction", e);
            return false;
        }
        if (summary == null || summary.isBlank()) {
            log.warn("conversation summary returned empty; skip compaction");
            return false;
        }

        List<LlmClient.Message> rebuilt = new ArrayList<>();
        for (int i = 0; i < systemEnd; i++) {
            rebuilt.add(history.get(i));
        }
        rebuilt.add(LlmClient.Message.user("[已压缩的历史对话摘要]\n" + summary.trim()));
        rebuilt.add(LlmClient.Message.assistant("好的，我已了解之前的上下文，请继续。"));
        rebuilt.addAll(history.subList(splitIdx, history.size()));

        int afterTokens = TokenBudget.estimateMessagesTokens(withPinnedConstraints(rebuilt));
        int beforeSize = history.size();
        archiveTurns(history, indices, splitTurn);
        if (!turnStarts.isEmpty()) {
            turnStarts.subList(0, splitTurn).clear();
            turnStarts.replaceAll(index -> index - splitIdx + systemEnd + 2);
        }
        summaryIndex = systemEnd;
        history.clear();
        history.addAll(rebuilt);
        log.info(String.format(Locale.ROOT,
                "compacted conversationHistory: tokens %d -> %d, messages %d -> %d, summary chars %d",
                currentTokens, afterTokens, beforeSize, rebuilt.size(),
                summary.length()));
        return true;
    }

    private List<Integer> turnIndices(List<LlmClient.Message> history) {
        if (!turnStarts.isEmpty()) return turnStarts;
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (i != summaryIndex && "user".equals(history.get(i).role())) {
                indices.add(i);
            }
        }
        return indices;
    }

    private void archiveTurns(List<LlmClient.Message> history, List<Integer> indices, int splitTurn) {
        for (int i = Math.max(0, splitTurn - RECOVERY_EXTRA_ROUNDS); i < splitTurn; i++) {
            // Apply the same historical image policy as the agents.
            archivedTurns.addLast(history.subList(indices.get(i), indices.get(i + 1)).stream()
                    .map(LlmClient.Message::withoutImageContent)
                    .toList());
            if (archivedTurns.size() > RECOVERY_EXTRA_ROUNDS) archivedTurns.removeFirst();
        }
    }

    private int recoveryRetainRounds() {
        return retainRecentRounds + RECOVERY_EXTRA_ROUNDS;
    }

    private record ToolOperation(String name, Object arguments) {}

    /**
     * 真正调 LLM 摘要。包可见以便测试通过子类替换。
     */
    protected String summarize(List<LlmClient.Message> messages) throws IOException {
        if (llmClient == null) {
            throw new IOException("LLM client not configured");
        }
        StringBuilder sb = new StringBuilder();
        for (LlmClient.Message m : messages) {
            sb.append(m.role().toUpperCase(Locale.ROOT)).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    sb.append("\n  TOOL_CALL ").append(tc.function().name())
                            .append(": ").append(tc.function().arguments());
                }
            }
            sb.append("\n\n");
            if (sb.length() > MAX_SUMMARY_INPUT_CHARS) {
                sb.append("...(超长内容已截断)\n");
                break;
            }
        }
        String prompt = String.format(SUMMARY_PROMPT, sb.toString());
        List<LlmClient.Message> req = List.of(
                LlmClient.Message.system("你是一个对话摘要助手，只输出摘要本身，不输出元描述。"),
                LlmClient.Message.user(prompt)
        );
        LlmClient.ChatResponse response = llmClient.chat(req, null);
        return response == null ? null : response.content();
    }

    public int retainRecentRounds() {
        return retainRecentRounds;
    }
}
