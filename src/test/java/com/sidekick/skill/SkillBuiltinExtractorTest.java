package com.sidekick.skill;

import com.sidekick.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillBuiltinExtractorTest {

    @Test
    void debuggingIsDiscoverableAndInjectedWithoutTruncation(@TempDir Path tempDir) throws IOException {
        Path cache = tempDir.resolve("cache");
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(cache);
        extractor.extractAll();

        String source;
        try (var in = getClass().getResourceAsStream("/skills/debugging/SKILL.md")) {
            assertNotNull(in, "debugging 必须随程序打包");
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(source, Files.readString(cache.resolve("debugging/SKILL.md")));
        var parsed = SkillFrontmatterParser.parse(source);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals("debugging", parsed.frontmatter().get("name"));
        assertFalse(parsed.frontmatter().get("description").toString().isBlank());
        assertFalse(parsed.body().isBlank());
        assertTrue(parsed.body().length() <= 5 * 1024, "正文必须能被 load_skill 完整注入");

        SkillRegistry registry = new SkillRegistry(cache, null, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        assertTrue(registry.warnings().isEmpty(), registry.warnings().toString());
        Skill skill = registry.findSkill("debugging");
        assertNotNull(skill);
        assertEquals(Skill.Source.BUILTIN, skill.source());
        assertEquals(parsed.body(), skill.body());

        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);
        String result = tools.executeTool("load_skill", "{\"name\":\"debugging\"}");
        assertTrue(result.contains("已加载 skill 'debugging'"), result);
        assertTrue(buffer.drain().contains(skill.body()), "实际注入必须包含完整正文");
        assertTrue(buffer.isEmpty(), "正文只能消费一次");
    }

    @Test
    void addsDebuggingBesideExistingCurrentVersionCache(@TempDir Path tempDir) throws IOException {
        Path existing = tempDir.resolve("web-access");
        Files.createDirectories(existing);
        Files.writeString(existing.resolve(".version"), SkillBuiltinExtractor.CURRENT_VERSION);
        Files.writeString(existing.resolve("SKILL.md"), "existing cache");

        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        assertEquals("existing cache", Files.readString(existing.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(tempDir.resolve("debugging/SKILL.md")));
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(tempDir.resolve("debugging/.version")).trim());
    }

    @Test
    void extractsBuiltinSkillFromClasspath(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        Path skillDir = tempDir.resolve("web-access");
        assertTrue(Files.isDirectory(skillDir));
        assertTrue(Files.isRegularFile(skillDir.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve("references/cdp-cheatsheet.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve("references/site-patterns/github.com.md")));
        assertTrue(Files.isRegularFile(skillDir.resolve(".version")));
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(skillDir.resolve(".version")).trim());
    }

    @Test
    void skipsExtractionWhenVersionMatches(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        // 用一个标记文件验证：第二次 extractAll 不会清理整个目录
        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "preserved");

        extractor.extractAll();
        assertTrue(Files.exists(marker), "版本一致时不应清空缓存目录");
    }

    @Test
    void rebuildsWhenVersionMismatch(@TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        extractor.extractAll();

        // 模拟旧版本：把 .version 改成历史值
        Path versionFile = tempDir.resolve("web-access/.version");
        Files.writeString(versionFile, "0.0.0-old");
        Path marker = tempDir.resolve("web-access/.user-marker");
        Files.writeString(marker, "should be wiped");

        extractor.extractAll();
        assertFalse(Files.exists(marker), "版本变化时应清空缓存目录");
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(versionFile).trim());
    }
}
