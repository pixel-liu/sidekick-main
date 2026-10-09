package com.sidekick.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 长期记忆 - 跨对话持久化的关键信息
 *
 * 职责：
 * 1. 持久化用户偏好、项目事实、关键决策等
 * 2. 支持关键词检索
 * 3. 在相同作用域和项目内按归一化内容哈希去重
 * 4. 按保留分控制容量，变更后即时持久化到磁盘
 */
public class LongTermMemory implements Memory {
    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);
    private static final String STORAGE_DIR_PROPERTY = "Sidekick.memory.dir";
    private static final String STORAGE_DIR_ENV = "Sidekick_MEMORY_DIR";
    private static final String STORAGE_FILE = "long_term_memory.json";
    private final Map<String, MemoryEntry> entries;
    private final AtomicInteger tokenCounter;
    private final ObjectMapper mapper;
    private final File storageFile;
    private final int maxEntries;
    private final int maxTokens;
    private final Clock clock;

    private record DedupKey(String scope, String project, String contentHash) {}
    private final Map<DedupKey, String> hashIndex = new HashMap<>();

    public LongTermMemory() {
        this(resolveStorageDir());
    }

    public LongTermMemory(File storageDir) {
        this(storageDir, positiveProperty("Sidekick.memory.max.entries", 1000),
                positiveProperty("Sidekick.memory.max.tokens", Integer.MAX_VALUE));
    }

    public LongTermMemory(File storageDir, int maxEntries, int maxTokens) {
        this(storageDir, maxEntries, maxTokens, Clock.systemUTC());
    }

    LongTermMemory(File storageDir, int maxEntries, int maxTokens, Clock clock) {
        if (maxEntries <= 0 || maxTokens <= 0) {
            throw new IllegalArgumentException("Memory capacity must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxTokens = maxTokens;
        this.clock = Objects.requireNonNull(clock);
        this.entries = new ConcurrentHashMap<>();
        this.tokenCounter = new AtomicInteger(0);
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);

        // 确保存储目录存在
        File dir = storageDir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storageFile = new File(dir, STORAGE_FILE);

        // 启动时加载已有记忆
        loadFromDisk();
    }

    @Override
    public synchronized void store(MemoryEntry entry) {
        Objects.requireNonNull(entry);
        DedupKey key = dedupKey(entry);
        String existingId = hashIndex.get(key);
        if (existingId != null) {
            MemoryEntry existing = entries.get(existingId);
            entries.put(existingId, existing.mergeScores(entry, clock.instant()));
        } else {
            MemoryEntry replaced = entries.put(entry.getId(), entry);
            if (replaced != null) {
                hashIndex.remove(dedupKey(replaced));
            }
            hashIndex.put(key, entry.getId());
        }
        enforceCapacity();
        saveToDisk();
    }

    @Override
    public synchronized Optional<MemoryEntry> retrieve(String id) {
        recordAccess(List.of(id));
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public List<MemoryEntry> search(String query, int limit) {
        return search(query, limit, null);
    }

    public synchronized List<MemoryEntry> search(String query, int limit, String projectKey) {
        Set<String> queryTokens = MemoryQueryTokenizer.tokenize(query);

        List<MemoryEntry> results = entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .filter(entry -> {
                    if (MemoryQueryTokenizer.matches(entry.getContent(), queryTokens)) {
                        return true;
                    }
                    return entry.getMetadata().values().stream()
                            .anyMatch(value -> MemoryQueryTokenizer.matches(value, queryTokens));
                })
                .limit(limit)
                .collect(Collectors.toList());
        recordAccess(results.stream().map(MemoryEntry::getId).toList());
        return results.stream().map(entry -> entries.get(entry.getId())).toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public List<MemoryEntry> getAll(String projectKey) {
        return entries.values().stream()
                .filter(entry -> isVisibleInProject(entry, projectKey))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean delete(String id) {
        MemoryEntry removed = entries.remove(id);
        if (removed != null) {
            hashIndex.remove(dedupKey(removed));
            tokenCounter.addAndGet(-removed.getTokenCount());
            saveToDisk();
            return true;
        }
        return false;
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        hashIndex.clear();
        tokenCounter.set(0);
        saveToDisk();
    }

    @Override
    public int getTokenCount() {
        return tokenCounter.get();
    }

    @Override
    public int size() {
        return entries.size();
    }

    /**
     * 按类型筛选记忆
     */
    public List<MemoryEntry> getByType(MemoryEntry.MemoryType type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type)
                .collect(Collectors.toList());
    }

    public static boolean isVisibleInProject(MemoryEntry entry, String projectKey) {
        String scope = scopeOf(entry);
        if ("global".equals(scope)) {
            return true;
        }
        String entryProject = entry.getMetadata().get("project");
        return projectKey != null && !projectKey.isBlank() && Objects.equals(entryProject, projectKey);
    }

    public static String scopeOf(MemoryEntry entry) {
        String scope = entry.getMetadata().get("scope");
        if ("project".equalsIgnoreCase(scope)) {
            return "project";
        }
        return "global";
    }

    /**
     * 持久化到磁盘
     */
    private void saveToDisk() {
        Path temporary = null;
        try {
            List<Map<String, Object>> dataList = entries.values().stream()
                    .map(this::entryToMap)
                    .collect(Collectors.toList());
            Path target = storageFile.toPath();
            temporary = Files.createTempFile(target.getParent(), STORAGE_FILE + ".", ".tmp");
            mapper.writeValue(temporary.toFile(), dataList);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("长期记忆持久化失败: {}", e.getMessage(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debug("清理长期记忆临时文件失败: {}", temporary, e);
                }
            }
        }
    }

    private static File resolveStorageDir() {
        String configuredDir = System.getProperty(STORAGE_DIR_PROPERTY);
        if (configuredDir == null || configuredDir.isBlank()) {
            configuredDir = System.getenv(STORAGE_DIR_ENV);
        }
        if (configuredDir != null && !configuredDir.isBlank()) {
            return new File(configuredDir);
        }
        return new File(new File(System.getProperty("user.home"), ".sidekick"), "memory");
    }

    /**
     * 从磁盘加载
     */
    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        if (!storageFile.exists()) return;

        try {
            List<Map<String, Object>> dataList = mapper.readValue(storageFile, List.class);
            for (Map<String, Object> data : dataList) {
                MemoryEntry entry = mapToEntry(data);
                if (entry != null) {
                    DedupKey key = dedupKey(entry);
                    String existingId = hashIndex.get(key);
                    if (existingId != null) {
                        MemoryEntry existing = entries.get(existingId);
                        Instant newest = existing.getUpdatedAt().isAfter(entry.getUpdatedAt())
                                ? existing.getUpdatedAt() : entry.getUpdatedAt();
                        entries.put(existingId, existing.mergeScores(entry, newest));
                    } else {
                        MemoryEntry replaced = entries.put(entry.getId(), entry);
                        if (replaced != null) hashIndex.remove(dedupKey(replaced));
                        hashIndex.put(key, entry.getId());
                    }
                }
            }
            enforceCapacity();
            saveToDisk();
            log.info("加载了 {} 条长期记忆", entries.size());
        } catch (IOException e) {
            log.warn("加载长期记忆失败: {}", e.getMessage(), e);
        }
    }

    private static Instant parseInstant(Object value, Instant fallback) {
        return value instanceof String text && !text.isBlank() ? Instant.parse(text) : fallback;
    }

    private static int positiveProperty(String name, int fallback) {
        try {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static DedupKey dedupKey(MemoryEntry entry) {
        String scope = scopeOf(entry);
        return new DedupKey(scope, "project".equals(scope) ? entry.getMetadata().get("project") : null,
                entry.getContentHash());
    }

    /** Count only returned recalls, never administrative list/scoring scans. */
    public synchronized List<MemoryEntry> recordAccess(Collection<String> ids) {
        boolean changed = false;
        Instant now = clock.instant();
        List<MemoryEntry> recalled = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            MemoryEntry entry = entries.get(id);
            if (entry != null) {
                MemoryEntry updated = entry.recalled(now);
                entries.put(id, updated);
                recalled.add(updated);
                changed = true;
            }
        }
        if (changed) saveToDisk();
        return recalled;
    }

    private void enforceCapacity() {
        long tokens = entries.values().stream().mapToLong(MemoryEntry::getTokenCount).sum();
        if (entries.size() <= maxEntries && tokens <= maxTokens) {
            tokenCounter.set((int) tokens);
            return;
        }
        Instant now = clock.instant();
        List<MemoryEntry> weakestFirst = entries.values().stream()
                .sorted(Comparator.comparingDouble((MemoryEntry entry) -> retentionScore(entry, now))
                        .thenComparing(MemoryEntry::getUpdatedAt)
                        .thenComparing(MemoryEntry::getTimestamp)
                        .thenComparing(MemoryEntry::getId))
                .toList();
        for (MemoryEntry entry : weakestFirst) {
            if (entries.size() <= maxEntries && tokens <= maxTokens) break;
            entries.remove(entry.getId());
            hashIndex.remove(dedupKey(entry));
            tokens -= entry.getTokenCount();
        }
        tokenCounter.set((int) tokens);
    }

    /** Bounded components prevent very frequent recalls from overwhelming quality scores. */
    private static double retentionScore(MemoryEntry entry, Instant now) {
        double frequency = entry.getAccessCount() / (entry.getAccessCount() + 5.0);
        double ageDays = Math.max(0, Duration.between(entry.getUpdatedAt(), now).toSeconds() / 86400.0);
        double recency = 1.0 / (1.0 + ageDays / 30.0);
        return 0.4 * entry.getImportance() + 0.3 * entry.getConfidence()
                + 0.15 * frequency + 0.15 * recency;
    }

    private Map<String, Object> entryToMap(MemoryEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("content", entry.getContent());
        map.put("type", entry.getType().name());
        map.put("timestamp", entry.getTimestamp().toString());
        map.put("metadata", entry.getMetadata());
        map.put("tokenCount", entry.getTokenCount());
        map.put("content_hash", entry.getContentHash());
        map.put("importance", entry.getImportance());
        map.put("confidence", entry.getConfidence());
        map.put("access_count", entry.getAccessCount());
        map.put("updated_at", entry.getUpdatedAt().toString());
        map.put("last_accessed_at", entry.getLastAccessedAt() == null ? null : entry.getLastAccessedAt().toString());
        return map;
    }

    @SuppressWarnings("unchecked")
    private MemoryEntry mapToEntry(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String content = (String) map.get("content");
            MemoryEntry.MemoryType type = MemoryEntry.MemoryType.valueOf((String) map.get("type"));
            Instant timestamp = null;
            Object timestampObj = map.get("timestamp");
            if (timestampObj instanceof String timestampValue && !timestampValue.isBlank()) {
                timestamp = Instant.parse(timestampValue);
            }
            Map<String, String> metadata = new HashMap<>();
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                ((Map<String, Object>) metaObj).forEach((k, v) -> metadata.put(k, String.valueOf(v)));
            }
            int tokenCount = map.get("tokenCount") instanceof Number n ? n.intValue() : MemoryEntry.estimateTokens(content);
            double importance = map.get("importance") instanceof Number n ? n.doubleValue() : 0.5;
            double confidence = map.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
            long accessCount = map.get("access_count") instanceof Number n ? n.longValue() : 0;
            Instant updatedAt = parseInstant(map.get("updated_at"), timestamp);
            Instant lastAccessedAt = parseInstant(map.get("last_accessed_at"), null);
            // Recompute the hash from content rather than trusting a stale persisted hash.
            return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                    importance, confidence, accessCount, updatedAt, lastAccessedAt);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成记忆状态摘要
     */
    public String getStatusSummary() {
        Map<MemoryEntry.MemoryType, Long> typeCounts = entries.values().stream()
                .collect(Collectors.groupingBy(MemoryEntry::getType, Collectors.counting()));

        return String.format("长期记忆: %d条 / %d tokens (事实: %d, 摘要: %d, 工具结果: %d)",
                entries.size(), tokenCounter.get(),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.FACT, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.SUMMARY, 0L),
                typeCounts.getOrDefault(MemoryEntry.MemoryType.TOOL_RESULT, 0L));
    }
}
