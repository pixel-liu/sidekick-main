package com.sidekick.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SidekickConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsEmptyDuplicateKeyAndUsesLaterProjectValue() throws Exception {
        Path projectEnv = tempDir.resolve("project.env");
        Path homeEnv = tempDir.resolve("home.env");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=\nOTHER=value\nDEEPSEEK_API_KEY=project-key\n");
        Files.writeString(homeEnv, "DEEPSEEK_API_KEY=home-key\n");

        assertEquals("project-key", SidekickConfig.readFromDotEnv("DEEPSEEK_API_KEY",
                projectEnv.toFile(), homeEnv.toFile()));
    }

    @Test
    void fallsBackToHomeEnvWhenProjectValueIsEmpty() throws Exception {
        Path projectEnv = tempDir.resolve("project.env");
        Path homeEnv = tempDir.resolve("home.env");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=  \n");
        Files.writeString(homeEnv, "DEEPSEEK_API_KEY=home-key\n");

        assertEquals("home-key", SidekickConfig.readFromDotEnv("DEEPSEEK_API_KEY",
                projectEnv.toFile(), homeEnv.toFile()));
    }

    @Test
    void returnsNullWhenEveryDefinitionIsEmpty() throws Exception {
        Path projectEnv = tempDir.resolve("project.env");
        Path homeEnv = tempDir.resolve("home.env");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=\n");
        Files.writeString(homeEnv, "DEEPSEEK_API_KEY=\n");

        assertNull(SidekickConfig.readFromDotEnv("DEEPSEEK_API_KEY",
                projectEnv.toFile(), homeEnv.toFile()));
    }
}
