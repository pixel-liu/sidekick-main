package com.sidekick.skill;

import com.sidekick.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillBuiltinExtractorTest {

    @ParameterizedTest
    @ValueSource(strings = {"debugging", "code-review", "skill-authoring"})
    void builtinIsDiscoverableAndInjectedWithoutTruncation(String name, @TempDir Path tempDir) throws IOException {
        Path cache = tempDir.resolve("cache");
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(cache);
        extractor.extractAll();

        String source;
        try (var in = getClass().getResourceAsStream("/skills/" + name + "/SKILL.md")) {
            assertNotNull(in, name + " 必须随程序打包");
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(source, Files.readString(cache.resolve(name + "/SKILL.md")));
        var parsed = SkillFrontmatterParser.parse(source);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals(name, parsed.frontmatter().get("name"));
        assertFalse(parsed.frontmatter().get("description").toString().isBlank());
        assertFalse(parsed.body().isBlank());
        assertTrue(parsed.body().length() <= 5 * 1024, "正文必须能被 load_skill 完整注入");

        SkillRegistry registry = new SkillRegistry(cache, null, null,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        assertTrue(registry.warnings().isEmpty(), registry.warnings().toString());
        Skill skill = registry.findSkill(name);
        assertNotNull(skill);
        assertEquals(Skill.Source.BUILTIN, skill.source());
        assertEquals(parsed.body(), skill.body());
        assertTrue(SkillIndexFormatter.format(registry.enabledSkills()).contains("**" + name + "**"));

        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);
        String result = tools.executeTool("load_skill", "{\"name\":\"" + name + "\"}");
        assertTrue(result.contains("已加载 skill '" + name + "'"), result);
        assertTrue(buffer.drain().contains(skill.body()), "实际注入必须包含完整正文");
        assertTrue(buffer.isEmpty(), "正文只能消费一次");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code-review", "skill-authoring"})
    void addsBuiltinBesideExistingCurrentVersionCaches(String addedName, @TempDir Path tempDir) throws IOException {
        SkillBuiltinExtractor extractor = new SkillBuiltinExtractor(tempDir);
        var existingNames = extractor.builtinSkillNames().stream().filter(name -> !name.equals(addedName)).toList();
        for (String name : existingNames) {
            Path existing = tempDir.resolve(name);
            Files.createDirectories(existing);
            Files.writeString(existing.resolve(".version"), SkillBuiltinExtractor.CURRENT_VERSION);
            Files.writeString(existing.resolve("SKILL.md"), "existing cache for " + name);
        }

        extractor.extractAll();

        for (String name : existingNames) {
            assertEquals("existing cache for " + name, Files.readString(tempDir.resolve(name + "/SKILL.md")));
        }
        assertTrue(Files.isRegularFile(tempDir.resolve(addedName + "/SKILL.md")));
        assertEquals(SkillBuiltinExtractor.CURRENT_VERSION,
                Files.readString(tempDir.resolve(addedName + "/.version")).trim());
    }

    @Test
    void authoringExampleCreatesLoadableProjectSkill(@TempDir Path tempDir) throws IOException {
        String source;
        try (var in = getClass().getResourceAsStream("/skills/skill-authoring/SKILL.md")) {
            assertNotNull(in);
            source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }
        String fence = "```markdown\n";
        int start = source.indexOf(fence);
        assertTrue(start >= 0, "编写指南应提供可执行的文件格式示例");
        start += fence.length();
        int end = source.indexOf("\n```", start);
        assertTrue(end > start);
        String example = source.substring(start, end) + "\n";
        var parsed = SkillFrontmatterParser.parse(example);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals("release-notes", parsed.frontmatter().get("name"));

        Path project = tempDir.resolve("project");
        Path skillMd = project.resolve("release-notes/SKILL.md");
        Files.createDirectories(skillMd.getParent());
        Files.writeString(skillMd, example);
        SkillRegistry registry = new SkillRegistry(null, null, project,
                new SkillStateStore(tempDir.resolve("skills.json")));
        registry.reload();
        assertTrue(registry.warnings().isEmpty(), registry.warnings().toString());
        Skill skill = registry.findSkill("release-notes");
        assertNotNull(skill);
        assertEquals(Skill.Source.PROJECT, skill.source());
        assertEquals("1.0.0", skill.version());
        assertEquals(java.util.List.of("release", "docs"), skill.tags());

        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);
        String result = tools.executeTool("load_skill", "{\"name\":\"release-notes\"}");
        assertTrue(result.contains("已加载 skill 'release-notes'"), result);
        assertEquals(parsed.body(), skill.body());
        assertTrue(buffer.drain().contains(parsed.body()), "指南中的示例应能作为项目 skill 完整加载");
    }

    @Test
    void codeReviewHonorsOverridesAndDisableStateButIgnoresBackup(@TempDir Path tempDir) throws IOException {
        Path cache = tempDir.resolve("cache");
        new SkillBuiltinExtractor(cache).extractAll();
        Path project = tempDir.resolve("project");
        Path override = project.resolve("code-review/SKILL.md");
        Files.createDirectories(override.getParent());
        Files.writeString(override.resolveSibling("SKILL.md.pre-builtin.bak"), "legacy backup");
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(cache, null, project, state);
        registry.reload();
        assertEquals(Skill.Source.BUILTIN, registry.findSkill("code-review").source());

        Files.writeString(override, "---\nname: code-review\ndescription: project review\n---\nproject body\n");
        registry.reload();
        assertEquals(Skill.Source.PROJECT, registry.findSkill("code-review").source());
        assertEquals("project body\n", registry.findSkill("code-review").body());

        state.disable("code-review");
        assertNull(registry.findSkill("code-review"));
        assertFalse(SkillIndexFormatter.format(registry.enabledSkills()).contains("**code-review**"));
        SkillContextBuffer buffer = new SkillContextBuffer();
        ToolRegistry tools = new ToolRegistry();
        tools.setSkillRegistry(registry);
        tools.setSkillContextBuffer(buffer);
        assertTrue(tools.executeTool("load_skill", "{\"name\":\"code-review\"}").contains("已被禁用"));
        assertTrue(buffer.isEmpty());

        state.enable("code-review");
        assertEquals(Skill.Source.PROJECT, registry.findSkill("code-review").source());
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
