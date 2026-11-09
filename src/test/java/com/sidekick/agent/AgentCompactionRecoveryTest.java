package com.sidekick.agent;

import com.sidekick.llm.LlmClient;
import com.sidekick.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentCompactionRecoveryTest {
    @Test
    void secondFailureRestoresArchivedTurnInNextRequest(@TempDir Path project) {
        RecordingClient llm = new RecordingClient();
        ToolRegistry tools = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                return "命令执行完成 (exit code: 1)\nfailed";
            }
        };
        tools.setProjectPath(project.toString());
        Agent agent = new Agent(llm, tools);

        for (int turn = 1; turn <= 6; turn++) {
            agent.run("turn-" + turn + " " + "x".repeat(turn == 6 ? 24_000 : 12_000));
        }
        assertFalse(hasTurn(llm.requests.get(5), 3), "turn 3 should already be summarized");
        assertTrue(hasTurn(llm.requests.get(5), 4));

        agent.run("turn-7");

        assertEquals(9, llm.requests.size());
        assertFalse(hasTurn(llm.requests.get(6), 3));
        assertFalse(hasTurn(llm.requests.get(7), 3), "a single failure must not restore old turns");
        List<LlmClient.Message> recovery = llm.requests.get(8);
        assertFalse(hasTurn(recovery, 2));
        for (int turn = 3; turn <= 7; turn++) {
            assertTrue(hasTurn(recovery, turn), "missing turn " + turn);
        }
        assertTrue(recovery.stream().anyMatch(message -> message.content() != null
                && message.content().contains("old conversation summary")
                && message.content().contains("重叠内容的细节以原文为准")));
        assertEquals(List.of("retry-1", "retry-2"), recovery.stream()
                .filter(message -> "tool".equals(message.role())).map(LlmClient.Message::toolCallId).toList());
        assertFalse(hasTurn(agent.getConversationHistory(), 3), "restored messages are temporary");
    }

    private static boolean hasTurn(List<LlmClient.Message> messages, int turn) {
        return messages.stream().anyMatch(message -> "user".equals(message.role())
                && (message.content().equals("turn-" + turn) || message.content().startsWith("turn-" + turn + " ")));
    }

    private static class RecordingClient implements LlmClient {
        private final List<List<Message>> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return new ChatResponse("assistant", "old conversation summary", null, 1, 1);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            requests.add(List.copyOf(messages));
            int call = requests.size();
            if (call == 7 || call == 8) {
                String args = call == 7 ? "{\"command\":\"check\",\"timeout\":10}"
                        : "{ \"timeout\": 10, \"command\": \"check\" }";
                var toolCall = new ToolCall("retry-" + (call - 6), new ToolCall.Function("execute_command", args));
                return new ChatResponse("assistant", "", List.of(toolCall), 1, 1);
            }
            return new ChatResponse("assistant", "done", null, 1, 1);
        }

        @Override public String getModelName() { return "test"; }
        @Override public String getProviderName() { return "test"; }
        @Override public int maxContextWindow() { return 32_000; }
    }
}
