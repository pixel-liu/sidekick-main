package com.sidekick.prompt;

import com.sidekick.context.ContextProfile;
import com.sidekick.llm.LlmClient;
import com.sidekick.memory.ConversationMemory;
import com.sidekick.memory.LongTermMemory;
import com.sidekick.memory.MemoryEntry;
import com.sidekick.memory.MemoryRetriever;
import com.sidekick.memory.TokenBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestPromptTest {
    private static final ContextProfile PROFILE = ContextProfile.custom(8_000, 1_000);
    private static final int INPUT_BUDGET = PROFILE.maxContextWindow() - TokenBudget.DEFAULT_RESPONSE_RESERVE;
    private final PromptAssembler assembler = PromptAssembler.createDefault();

    @TempDir Path tempDir;

    @Test
    void countsToolsAndStopsAfterDroppingSkillsWhenEnough() {
        RequestPrompt prompt = assembler.prepare(PromptMode.AGENT, context(), null);
        List<LlmClient.Message> messages = request(prompt, -10);
        List<LlmClient.Tool> tools = List.of(new LlmClient.Tool("read_file", "t".repeat(160), null));
        assertFalse(prompt.trimIfNeeded(messages, null, PROFILE));
        assertTrue(prompt.trimIfNeeded(messages, tools, PROFILE));
        assertFalse(messages.get(0).content().contains("skill-index"));
        assertTrue(messages.get(0).content().contains("last-optional"));
        assertFalse(prompt.trimIfNeeded(messages, tools, PROFILE));
    }

    @Test
    void discardsLowestRankedMemoryBeforeProjectBlocks() {
        LongTermMemory store = memoryStore();
        RequestPrompt prompt = prepareWithMemories(store);
        List<LlmClient.Message> messages = request(prompt, 270);

        assertTrue(prompt.trimIfNeeded(messages, null, PROFILE));
        assertFalse(prompt.text().contains("skill-index"));
        assertFalse(prompt.text().contains("lowest-relevance"));
        assertTrue(prompt.text().contains("lower-relevance"));
        assertTrue(prompt.text().contains("last-optional"));
    }

    @Test
    void dropsProjectBlocksLastAndCountsOnlyRetainedRecalls() {
        LongTermMemory store = memoryStore();
        RequestPrompt prompt = prepareWithMemories(store);
        List<LlmClient.Message> messages = request(prompt, 470);
        List<LlmClient.Message> core = List.copyOf(messages.subList(1, messages.size()));

        assertTrue(prompt.trimIfNeeded(messages, null, PROFILE));
        assertEquals(core, messages.subList(1, messages.size()));
        assertEquals(prompt.text(), messages.get(0).content());
        assertTrue(TokenBudget.estimateMessagesTokens(messages) <= INPUT_BUDGET);
        assertFalse(prompt.text().contains("skill-index"));
        assertFalse(prompt.text().contains("lower-relevance"));
        assertFalse(prompt.text().contains("lowest-relevance"));
        assertFalse(prompt.text().contains("last-optional"));
        assertTrue(prompt.text().contains("first-optional"));
        assertTrue(prompt.text().contains("project-rule"));
        assertTrue(prompt.text().contains("external-context"));
        assertTrue(prompt.text().contains("## Language"));
        assertTrue(prompt.text().contains("exact-recall"));
        assertTrue(prompt.text().contains("important-recall"));

        assertEquals(0, store.getAll().stream().mapToLong(MemoryEntry::getAccessCount).sum());
        prompt.recordRecalls();
        prompt.recordRecalls();
        assertEquals(List.of("exact", "important"), store.getAll().stream()
                .filter(entry -> entry.getAccessCount() == 1).map(MemoryEntry::getId).sorted().toList());
        assertEquals(4, store.size());
    }

    @Test
    void preservesUnmatchedProjectMarkersAndCoreWhenStillOverBudget() {
        RequestPrompt prompt = assembler.prepare(PromptMode.TEAM_WORKER, PromptContext.builder()
                .projectMemoryContext("""
                        ### D:\\project\\SideKick.md
                        <!-- sidekick:optional -->
                        unclosed-block
                        ### D:\\project\\SideKick.local.md
                        project-rule
                        <!-- /sidekick:optional -->
                        """)
                .skillIndex("skill-index")
                .build(), null);
        List<LlmClient.Message> messages = request(prompt, 5_000);
        List<LlmClient.Message> core = List.copyOf(messages.subList(1, messages.size()));

        assertTrue(prompt.trimIfNeeded(messages, null, PROFILE));
        assertTrue(TokenBudget.estimateMessagesTokens(messages) > INPUT_BUDGET);
        assertFalse(prompt.text().contains("skill-index"));
        assertTrue(prompt.text().contains("unclosed-block"));
        assertTrue(prompt.text().contains("project-rule"));
        assertEquals(core, messages.subList(1, messages.size()));
    }

    private static PromptContext context() {
        return PromptContext.builder()
                .skillIndex("skill-index " + "s".repeat(800))
                .projectMemoryContext("project-rule\n\n" + optional("first-optional") + optional("last-optional"))
                .externalContext("external-context")
                .build();
    }

    private static String optional(String label) {
        return "<!-- sidekick:optional -->\n" + label + " " + "p".repeat(800)
                + "\n<!-- /sidekick:optional -->\n";
    }

    private static List<LlmClient.Message> request(RequestPrompt prompt, int overBudget) {
        List<LlmClient.Message> messages = new ArrayList<>(List.of(
                LlmClient.Message.system(prompt.text()),
                LlmClient.Message.user("core-user-message"),
                LlmClient.Message.assistant(null, List.of(new LlmClient.ToolCall(
                        "call-1", new LlmClient.ToolCall.Function("read_file", "{}")))),
                LlmClient.Message.tool("call-1", "core-tool-result")));
        int fillerTokens = INPUT_BUDGET + overBudget - TokenBudget.estimateMessagesTokens(messages) - 4;
        messages.add(LlmClient.Message.user("x".repeat(fillerTokens * 4)));
        return messages;
    }

    private RequestPrompt prepareWithMemories(LongTermMemory store) {
        MemoryRetriever retriever = new MemoryRetriever(new ConversationMemory(1_000), store);
        return assembler.prepare(PromptMode.AGENT, context(),
                retriever.selectContextForQuery("alpha beta gamma delta", 1_000, null));
    }

    private LongTermMemory memoryStore() {
        LongTermMemory store = new LongTermMemory(tempDir.toFile(), 10, 10_000);
        save(store, "exact", "alpha beta gamma delta exact-recall", 0.5);
        save(store, "important", "alpha beta gamma important-recall", 0.8);
        save(store, "lower", "alpha beta lower-relevance " + "a".repeat(400), 0.5);
        save(store, "lowest", "alpha lowest-relevance " + "b".repeat(400), 0.5);
        return store;
    }

    private static void save(LongTermMemory store, String id, String text, double importance) {
        store.store(new MemoryEntry(id, text, MemoryEntry.MemoryType.FACT, Instant.now(),
                Map.of("scope", "global"), MemoryEntry.estimateTokens(text)).withScores(importance, 0.5));
    }
}
