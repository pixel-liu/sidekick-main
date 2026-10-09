package com.sidekick.memory;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Map;

/**
 * 记忆条目 - Memory 系统的基础数据单元
 */
public class MemoryEntry {
    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;
    private final String contentHash;
    private final double importance;
    private final double confidence;
    private final long accessCount;
    private final Instant updatedAt;
    private final Instant lastAccessedAt;

    public enum MemoryType {
        CONVERSATION,  // 对话记忆
        FACT,          // 事实记忆（用户偏好、项目信息等）
        SUMMARY,       // 摘要记忆
        TOOL_RESULT    // 工具执行结果
    }

    public MemoryEntry(String id, String content, MemoryType type, Map<String, String> metadata, int tokenCount) {
        this(id, content, type, Instant.now(), metadata, tokenCount);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount) {
        this(id, content, type, timestamp, metadata, tokenCount, 0.5, 0.5, 0, timestamp, null);
    }

    public MemoryEntry(String id, String content, MemoryType type, Instant timestamp,
                       Map<String, String> metadata, int tokenCount, double importance,
                       double confidence, long accessCount, Instant updatedAt, Instant lastAccessedAt) {
        validateScore(importance);
        validateScore(confidence);
        if (accessCount < 0 || tokenCount < 0) {
            throw new IllegalArgumentException("Memory counts must be non-negative");
        }
        this.id = id;
        this.content = content;
        this.type = type;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? metadata : Map.of();
        this.tokenCount = tokenCount;
        this.contentHash = hashContent(content);
        this.importance = importance;
        this.confidence = confidence;
        this.accessCount = accessCount;
        this.updatedAt = updatedAt == null ? this.timestamp : updatedAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public MemoryType getType() { return type; }
    public Instant getTimestamp() { return timestamp; }
    public Map<String, String> getMetadata() { return metadata; }
    public int getTokenCount() { return tokenCount; }
    public String getContentHash() { return contentHash; }
    public double getImportance() { return importance; }
    public double getConfidence() { return confidence; }
    public long getAccessCount() { return accessCount; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }

    public MemoryEntry withScores(double importance, double confidence) {
        return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                importance, confidence, accessCount, updatedAt, lastAccessedAt);
    }

    MemoryEntry mergeScores(MemoryEntry incoming, Instant now) {
        return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                Math.max(importance, incoming.importance), Math.max(confidence, incoming.confidence),
                accessCount, now, lastAccessedAt);
    }

    MemoryEntry recalled(Instant now) {
        return new MemoryEntry(id, content, type, timestamp, metadata, tokenCount,
                importance, confidence, accessCount == Long.MAX_VALUE ? accessCount : accessCount + 1,
                now, now);
    }

    /** Canonical Unicode equivalence only; case and whitespace remain significant. */
    public static String hashContent(String content) {
        try {
            String normalized = Normalizer.normalize(content == null ? "" : content, Normalizer.Form.NFC);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validateScore(double score) {
        if (!Double.isFinite(score) || score < 0 || score > 1) {
            throw new IllegalArgumentException("Memory importance/confidence must be between 0 and 1");
        }
    }

    /**
     * 粗略估算 token 数（中文约 1.5 字/token，英文约 4 字符/token）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long chineseChars = text.chars().filter(c -> c > 0x4E00 && c < 0x9FFF).count();//筛选所有中文汉字
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }

    @Override
    public String toString() {
        return "[%s] %s: %s".formatted(type, id,
                content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
