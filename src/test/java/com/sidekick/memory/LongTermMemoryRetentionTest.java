package com.sidekick.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryRetentionTest {
    @TempDir Path tempDir;
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private MemoryEntry entry(String id, String content, double importance, double confidence,
                              long accesses, Instant updatedAt, int tokens) {
        return new MemoryEntry(id, content, MemoryEntry.MemoryType.FACT, NOW.minusSeconds(86400),
                Map.of("scope", "project", "project", "/repo/a"), tokens,
                importance, confidence, accesses, updatedAt, null);
    }

    private MemoryEntry find(LongTermMemory memory, String id) {
        return memory.getAll().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void unicodeDuplicatesMergeMaxScoresAndKeepIdentityAcrossReload() throws Exception {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        MemoryEntry original = entry("original", "caf\u00e9", 0.8, 0.3, 4, NOW.minusSeconds(100), 5);
        memory.store(original);
        memory.store(entry("duplicate", "cafe\u0301", 0.2, 0.9, 0, NOW, 20));
        memory.store(entry("lower-scores", "caf\u00e9", 0.1, 0.1, 0, NOW, 20));

        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        assertEquals(1, reloaded.size());
        MemoryEntry merged = find(reloaded, "original");
        assertEquals(original.getContent(), merged.getContent());
        assertEquals(original.getTimestamp(), merged.getTimestamp());
        assertEquals(0.8, merged.getImportance());
        assertEquals(0.9, merged.getConfidence());
        assertEquals(4, merged.getAccessCount());
        assertEquals(NOW, merged.getUpdatedAt());
        assertEquals(5, reloaded.getTokenCount());
        assertEquals(MemoryEntry.hashContent("cafe\u0301"), merged.getContentHash());
        assertTrue(merged.getContentHash().matches("[0-9a-f]{64}"));
        var json = new ObjectMapper().readTree(tempDir.resolve("long_term_memory.json").toFile()).get(0);
        assertEquals(merged.getContentHash(), json.path("content_hash").asText());
        assertEquals(4, json.path("access_count").asLong());
        assertEquals(NOW.toString(), json.path("updated_at").asText());
        assertTrue(json.has("last_accessed_at"));
    }

    @Test
    void eachRetentionFactorCanProtectAnOtherwiseEqualMemory() {
        Instant old = NOW.minusSeconds(86400L * 365);
        List<MemoryEntry> protectedEntries = List.of(
                entry("protected", "importance", 0.9, 0.5, 0, old, 5),
                entry("protected", "confidence", 0.5, 0.9, 0, old, 5),
                entry("protected", "frequency", 0.5, 0.5, 20, old, 5),
                entry("protected", "recency", 0.5, 0.5, 0, NOW, 5));
        for (int i = 0; i < protectedEntries.size(); i++) {
            LongTermMemory memory = new LongTermMemory(tempDir.resolve("factor-" + i).toFile(), 2, 100, CLOCK);
            memory.store(entry("weak", "weak", 0.5, 0.5, 0, old, 5));
            memory.store(protectedEntries.get(i));
            memory.store(entry("strong", "strong", 1, 1, 20, NOW, 5));
            assertEquals(2, memory.size());
            assertTrue(memory.getAll().stream().noneMatch(e -> e.getId().equals("weak")), "factor " + i);
            assertNotNull(find(memory, "protected"));
            assertEquals(10, memory.getTokenCount());
        }
    }

    @Test
    void tokenBudgetAndHashIndexRemainConsistentAfterEviction() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 10, CLOCK);
        memory.store(entry("weak", "weak", 0, 0, 0, NOW, 6));
        memory.store(entry("strong", "strong", 1, 1, 0, NOW, 6));
        assertEquals(List.of("strong"), memory.getAll().stream().map(MemoryEntry::getId).toList());
        assertEquals(6, memory.getTokenCount());
        memory.delete("strong");
        memory.store(entry("replacement", "weak", 1, 1, 0, NOW, 6));
        assertNotNull(find(memory, "replacement"));
        assertEquals(6, memory.getTokenCount());
    }

    @Test
    void startupMigratesLegacyJsonAndAppliesCapacity() throws Exception {
        Files.writeString(tempDir.resolve("long_term_memory.json"), """
                [
                  {"id":"old","content":"old","type":"FACT","timestamp":"2020-01-01T00:00:00Z","tokenCount":5},
                  {"id":"new","content":"new","type":"FACT","timestamp":"2026-10-08T00:00:00Z","tokenCount":5}
                ]
                """);
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 1, 100, CLOCK);
        assertEquals(1, memory.size());
        MemoryEntry survivor = find(memory, "new");
        assertEquals(0.5, survivor.getImportance());
        assertEquals(0.5, survivor.getConfidence());
        assertEquals(0, survivor.getAccessCount());
        assertEquals(survivor.getTimestamp(), survivor.getUpdatedAt());
        assertEquals(5, memory.getTokenCount());
        assertTrue(Files.readString(tempDir.resolve("long_term_memory.json")).contains("content_hash"));
    }

    @Test
    void onlyActuallyInjectedMemoriesCountAsRecalls() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        memory.store(entry("a", "Java Maven", 0.5, 0.5, 0, NOW.minusSeconds(100), 5));
        memory.store(entry("b", "Java Gradle", 0.5, 0.5, 0, NOW.minusSeconds(100), 5));
        MemoryRetriever retriever = new MemoryRetriever(new ConversationMemory(100), memory);
        memory.getAll();
        memory.getAll("/repo/a");
        assertEquals(0, find(memory, "a").getAccessCount());
        assertEquals(0, find(memory, "b").getAccessCount());
        retriever.buildContextForQuery("Java", 5, "/repo/a");
        assertEquals(1, memory.getAll().stream().mapToLong(MemoryEntry::getAccessCount).sum());
        MemoryEntry recalled = memory.getAll().stream().filter(e -> e.getAccessCount() == 1).findFirst().orElseThrow();
        assertEquals(NOW, recalled.getLastAccessedAt());
        assertEquals(NOW, recalled.getUpdatedAt());
        LongTermMemory reloaded = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        assertEquals(1, find(reloaded, recalled.getId()).getAccessCount());
        assertEquals(NOW, find(reloaded, recalled.getId()).getLastAccessedAt());
        retriever.retrieveLongTerm("Java", 1, "/repo/a");
        assertEquals(2, memory.getAll().stream().mapToLong(MemoryEntry::getAccessCount).sum());
        memory.search("Maven", 1, "/repo/a");
        assertEquals(3, memory.getAll().stream().mapToLong(MemoryEntry::getAccessCount).sum());
    }

    @Test
    void concurrentDuplicateStoresDoNotCreateExtraEntries() throws Exception {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var calls = java.util.stream.IntStream.range(0, 20).<Callable<Void>>mapToObj(i -> () -> {
                memory.store(entry("id-" + i, "same", i / 20.0, (20 - i) / 20.0, 0, NOW, 5));
                return null;
            }).toList();
            for (var result : executor.invokeAll(calls)) result.get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, memory.size());
        assertEquals(0.95, memory.getAll().get(0).getImportance());
        assertEquals(1, memory.getAll().get(0).getConfidence());
        assertEquals(5, memory.getTokenCount());
    }

    @Test
    void saveMemoryToolPassesScoresAndDefaultsThroughToPersistentMemory() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 1000, CLOCK);
        MemoryManager manager = new MemoryManager(null, 100, 1000, memory);
        com.sidekick.tool.ToolRegistry tools = new com.sidekick.tool.ToolRegistry();
        tools.setScoredMemorySaver(manager::storeFact);
        String result = tools.executeTool("save_memory", """
                {"fact":"Java 17","scope":"global","importance":0.9,"confidence":0.8}
                """);
        assertTrue(result.contains("已保存"), result);
        MemoryEntry saved = memory.getAll().get(0);
        assertEquals(0.9, saved.getImportance());
        assertEquals(0.8, saved.getConfidence());
        assertEquals("global", saved.getMetadata().get("scope"));
        tools.executeTool("save_memory", """
                {"fact":"Java 17","scope":"global"}
                """);
        assertEquals(1, memory.size());
        assertEquals(0.9, memory.getAll().get(0).getImportance());
        assertEquals(0.8, memory.getAll().get(0).getConfidence());
        tools.executeTool("save_memory", """
                {"fact":"invalid","importance":-1}
                """);
        assertEquals(1, memory.size());
    }

    @Test
    void normalizedHashStillRespectsProjectAndGlobalVisibility() {
        LongTermMemory memory = new LongTermMemory(tempDir.toFile(), 10, 100, CLOCK);
        memory.store(new MemoryEntry("a", "caf\u00e9", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/a"), 5));
        memory.store(new MemoryEntry("b", "cafe\u0301", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "project", "project", "/repo/b"), 5));
        memory.store(new MemoryEntry("global", "caf\u00e9", MemoryEntry.MemoryType.FACT,
                Map.of("scope", "global"), 5));
        assertEquals(3, memory.size());
        assertEquals(2, memory.getAll("/repo/a").size());
        assertEquals(1, memory.getAll().stream().map(MemoryEntry::getContentHash).distinct().count());
    }
    @Test
    void rejectsInvalidScoresAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> entry("x", "x", Double.NaN, 0.5, 0, NOW, 5));
        assertThrows(IllegalArgumentException.class, () -> entry("x", "x", 0.5, 1.1, 0, NOW, 5));
        assertThrows(IllegalArgumentException.class, () -> new LongTermMemory(tempDir.toFile(), 0, 100));
    }
}