package com.sidekick.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {
    @TempDir
    Path tempDir;
    private String previousRagDir;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        previousRagDir = System.getProperty("Sidekick.rag.dir");
        System.setProperty("Sidekick.rag.dir", tempDir.resolve("rag").toString());
        project = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(project.resolve("Sample.java"), "class Sample { void run() {} }");
    }

    @AfterEach
    void tearDown() {
        if (previousRagDir == null) {
            System.clearProperty("Sidekick.rag.dir");
        } else {
            System.setProperty("Sidekick.rag.dir", previousRagDir);
        }
    }

    private EmbeddingClient stubEmbeddingClient() {
        return new EmbeddingClient() {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f, 0.0f};
            }
        };
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex.IndexResult result = new CodeIndex(stubEmbeddingClient())
                .index(tempDir.resolve("missing").toString());
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() throws Exception {
        CodeIndex.IndexResult result = new CodeIndex(stubEmbeddingClient()).index(project.toString());
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
        try (VectorStore store = new VectorStore(project.toString())) {
            assertEquals(result.chunkCount(), store.getStats().chunkCount());
        }
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex.IndexResult result = new CodeIndex(stubEmbeddingClient(), messages::add).index(project.toString());
        assertTrue(result.chunkCount() > 0);
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    @Test
    void preservesOldIndexWhenEmbeddingFailsAfterOneChunk() throws Exception {
        CodeIndex.IndexResult old = new CodeIndex(stubEmbeddingClient()).index(project.toString());
        assertTrue(old.chunkCount() > 1);
        List<String> messages = new ArrayList<>();
        EmbeddingClient failingClient = new EmbeddingClient() {
            private int calls;

            @Override
            public float[] embed(String text) throws IOException {
                if (++calls == 2) {
                    throw new IOException("embedding unavailable");
                }
                return new float[]{1.0f, 0.0f};
            }
        };
        Files.writeString(project.resolve("Sample.java"), "class Changed { void changed() {} }");
        CodeIndex.IndexResult result = new CodeIndex(failingClient, messages::add).index(project.toString());
        assertTrue(result.message().contains("已保留原有索引"));
        assertFalse(messages.stream().anyMatch(message -> message.startsWith("✅")));
        try (VectorStore store = new VectorStore(project.toString())) {
            assertEquals(old.chunkCount(), store.getStats().chunkCount());
            assertEquals(old.relationCount(), store.getStats().relationCount());
            assertFalse(store.searchByKeyword("Sample").isEmpty());
            assertTrue(store.searchByKeyword("Changed").isEmpty());
        }
    }

    @Test
    void replacesOldIndexAfterSuccessfulRebuild() throws Exception {
        CodeIndex indexer = new CodeIndex(stubEmbeddingClient());
        indexer.index(project.toString());
        Files.writeString(project.resolve("Sample.java"), "class Changed { void changed() {} }");
        CodeIndex.IndexResult result = indexer.index(project.toString());
        assertTrue(result.message().contains("索引完成"));
        try (VectorStore store = new VectorStore(project.toString())) {
            assertFalse(store.searchByKeyword("Changed").isEmpty());
            assertTrue(store.searchByKeyword("class Sample").isEmpty());
            assertEquals(result.chunkCount(), store.getStats().chunkCount());
        }
    }

    @Test
    void indexesAnExplicitlySelectedHiddenRoot() throws Exception {
        Path hidden = Files.createDirectory(tempDir.resolve(".project"));
        Files.writeString(hidden.resolve("Sample.java"), "class Sample {}");
        CodeIndex.IndexResult result = new CodeIndex(stubEmbeddingClient()).index(hidden.toString());
        assertTrue(result.chunkCount() > 0);
    }

    @Test
    void rejectsAFileAsTheIndexRoot() {
        CodeIndex.IndexResult result = new CodeIndex(stubEmbeddingClient())
                .index(project.resolve("Sample.java").toString());
        assertTrue(result.message().contains("不是目录"));
    }
}
