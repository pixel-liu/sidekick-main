package com.sidekick.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void ignoresProjectEnvByDefaultAndUsesDedicatedCredentialsFirst() throws Exception {
        Path credentials = tempDir.resolve("credentials.env");
        Path homeEnv = tempDir.resolve("home.env");
        Path projectEnv = tempDir.resolve("project.env");
        Files.writeString(credentials, "DEEPSEEK_API_KEY=dedicated-key\n");
        Files.writeString(homeEnv, "DEEPSEEK_API_KEY=home-key\n");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=project-key\n");

        assertEquals("dedicated-key", SidekickConfig.readProviderSettingFromDotEnv("DEEPSEEK_API_KEY",
                false, credentials.toFile(), homeEnv.toFile(), projectEnv.toFile()));
    }

    @Test
    void onlyReadsProjectEnvWhenExplicitlyEnabled() throws Exception {
        Path credentials = tempDir.resolve("credentials.env");
        Path homeEnv = tempDir.resolve("home.env");
        Path projectEnv = tempDir.resolve("project.env");
        Files.writeString(credentials, "");
        Files.writeString(homeEnv, "");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=project-key\n");

        assertNull(SidekickConfig.readProviderSettingFromDotEnv("DEEPSEEK_API_KEY",
                false, credentials.toFile(), homeEnv.toFile(), projectEnv.toFile()));
        assertEquals("project-key", SidekickConfig.readProviderSettingFromDotEnv("DEEPSEEK_API_KEY",
                true, credentials.toFile(), homeEnv.toFile(), projectEnv.toFile()));
    }

    @Test
    void keepsUserCredentialsAheadOfOptInProjectEnv() throws Exception {
        Path credentials = tempDir.resolve("credentials.env");
        Path homeEnv = tempDir.resolve("home.env");
        Path projectEnv = tempDir.resolve("project.env");
        Files.writeString(credentials, "");
        Files.writeString(homeEnv, "DEEPSEEK_API_KEY=home-key\n");
        Files.writeString(projectEnv, "DEEPSEEK_API_KEY=project-key\n");

        assertEquals("home-key", SidekickConfig.readProviderSettingFromDotEnv("DEEPSEEK_API_KEY",
                true, credentials.toFile(), homeEnv.toFile(), projectEnv.toFile()));
    }

    @Test
    void disablesProjectCredentialsByDefault() {
        assertFalse(new SidekickConfig().isAllowProjectEnvCredentials());
    }
}
