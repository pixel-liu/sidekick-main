package com.sidekick.rag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorStoreTest {

    private VectorStore store;
    @TempDir
    Path tempDir;
    private String previousRagDir;
    private static final String TEST_PROJECT = "/tmp/test-project";

    @BeforeEach
    void setUp() throws Exception {
        previousRagDir = System.getProperty("Sidekick.rag.dir");
        System.setProperty("Sidekick.rag.dir", tempDir.resolve("rag").toString());
        store = new VectorStore(TEST_PROJECT);
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (store != null) {
                store.close();
            }
        } finally {
            if (previousRagDir == null) {
                System.clearProperty("Sidekick.rag.dir");
            } else {
                System.setProperty("Sidekick.rag.dir", previousRagDir);
            }
        }
    }

    @Test
    void testInsertAndSearch() throws Exception {
        CodeChunk chunk1 = CodeChunk.classChunk("Test.java", "TestClass",
                "public class TestClass {}", 1, 1);
        CodeChunk chunk2 = CodeChunk.methodChunk("Test.java", "TestClass.main",
                "public static void main(String[] args) {}", 2, 4);

        float[] emb1 = {1.0f, 0.0f, 0.0f};
        float[] emb2 = {0.0f, 1.0f, 0.0f};

        store.insertChunks(List.of(
                new VectorStore.CodeChunkEntry(chunk1, emb1),
                new VectorStore.CodeChunkEntry(chunk2, emb2)
        ));

        VectorStore.IndexStats stats = store.getStats();
        assertEquals(2, stats.chunkCount());

        float[] query = {1.0f, 0.0f, 0.0f};
        List<VectorStore.SearchResult> results = store.search(query, 2);
        assertEquals(2, results.size());
        assertEquals("TestClass", results.get(0).name());
        assertTrue(results.get(0).similarity() > 0.99);
    }

    @Test
    void testSearchByKeyword() throws Exception {
        CodeChunk chunk = CodeChunk.classChunk("Foo.java", "FooService",
                "public class FooService { public void bar() {} }", 1, 3);
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{0.5f, 0.5f})));

        List<VectorStore.SearchResult> results = store.searchByKeyword("FooService");
        assertEquals(1, results.size());
        assertEquals("FooService", results.get(0).name());
    }

    @Test
    void testRelationStorage() throws Exception {
        CodeRelation rel = new CodeRelation("A.java", "A", "B.java", "B", "extends");
        store.insertRelations(List.of(rel));

        List<CodeRelation> results = store.getRelations("A");
        assertEquals(1, results.size());
        assertEquals("extends", results.get(0).relationType());
    }

    @Test
    void testClearProject() throws Exception {
        CodeChunk chunk = CodeChunk.fileChunk("readme.md", "# Hello");
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[]{1.0f})));
        assertEquals(1, store.getStats().chunkCount());

        store.clearProject();
        assertEquals(0, store.getStats().chunkCount());
    }

    @Test
    void replacesChunksAndRelationsTogetherWithoutChangingOtherProjects() throws Exception {
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("Old.java", "old"), new float[]{1})));
        store.insertRelations(List.of(new CodeRelation("Old.java", "Old", null, "Parent", "extends")));
        try (VectorStore other = new VectorStore("other-project")) {
            other.insertChunks(List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("Other.java", "other"), new float[]{1})));
            other.insertRelations(List.of(new CodeRelation("Other.java", "Other", null, "Parent", "extends")));
            store.replaceProjectIndex(
                    List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("New.java", "new"), new float[]{1})),
                    List.of(new CodeRelation("New.java", "New", null, "Parent", "extends")));
            assertTrue(store.searchByKeyword("old").isEmpty());
            assertEquals(1, store.searchByKeyword("new").size());
            assertTrue(store.getRelations("Old").isEmpty());
            assertEquals(1, store.getRelations("New").size());
            assertEquals(1, other.getStats().chunkCount());
            assertEquals(1, other.getRelations("Other").size());
        }
    }

    @Test
    void rollsBackTheWholeReplacementWhenRelationInsertFails() throws Exception {
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("Old.java", "old"), new float[]{1})));
        store.insertRelations(List.of(new CodeRelation("Old.java", "Old", null, "Parent", "extends")));
        assertThrows(java.sql.SQLException.class, () -> store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("New.java", "new"), new float[]{1})),
                List.of(new CodeRelation("New.java", "New", null, "Parent", null))));
        assertEquals(1, store.searchByKeyword("old").size());
        assertTrue(store.searchByKeyword("new").isEmpty());
        assertEquals(1, store.getRelations("Old").size());
        assertTrue(store.getRelations("New").isEmpty());
        // 回滚后连接仍应可用于下一次成功重建。
        store.replaceProjectIndex(List.of(), List.of());
        assertEquals(0, store.getStats().chunkCount());
        assertEquals(0, store.getStats().relationCount());
    }

    @Test
    void preservesOldIndexWhenChunkPreparationThrowsARuntimeException() throws Exception {
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(CodeChunk.fileChunk("Old.java", "old"), new float[]{1})));

        assertThrows(NullPointerException.class, () -> store.replaceProjectIndex(
                List.of(new VectorStore.CodeChunkEntry(null, new float[]{1})), List.of()));

        assertEquals(1, store.searchByKeyword("old").size());
        assertEquals(1, store.getStats().chunkCount());
    }
}
