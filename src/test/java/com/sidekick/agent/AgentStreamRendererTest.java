package com.sidekick.agent;

import com.sidekick.llm.LlmClient;
import com.sidekick.hitl.ApprovalRequest;
import com.sidekick.hitl.ApprovalResult;
import com.sidekick.render.Renderer;
import com.sidekick.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStreamRendererTest {

    @Test
    void shouldNotPrintEmptyReasoningHeadingBeforeTextIsFlushable() throws Exception {
        LlmClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            renderer.onReasoningDelta("继续查看 src 目录下的包结构");
            String afterDelta = output.toString(StandardCharsets.UTF_8);
            assertFalse(afterDelta.contains("思考过程"),
                    "没有完整行时不应先打印空的思考标题: " + afterDelta);

            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("思考过程"));
        assertTrue(rendered.contains("继续查看 src 目录下的包结构"));
    }

    @Test
    void shouldIgnoreWhitespaceOnlyReasoningAcrossIterationReset() throws Exception {
        LlmClient.StreamListener renderer = newStreamRenderer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.onReasoningDelta("  \n");
            invokeNoArg(renderer, "resetBetweenIterations");
        } finally {
            System.setOut(originalOut);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertFalse(rendered.contains("思考过程"),
                "空白 reasoning 不应打印空的思考标题: " + rendered);
    }


    @Test
    void inlineThinkingPanelReceivesReasoningInsteadOfTranscriptHeading() throws Exception {
        ThinkingRenderer thinkingRenderer = new ThinkingRenderer();
        LlmClient.StreamListener renderer = newStreamRenderer(thinkingRenderer);

        invokeNoArg(renderer, "beginThinking");
        renderer.onReasoningDelta("我先判断用户意图。\n");
        renderer.onContentDelta("好的");
        renderer.onReasoningDelta("这段思考在正文开始后才到达。");
        invokeNoArg(renderer, "finish");

        String transcript = thinkingRenderer.transcript();
        assertTrue(thinkingRenderer.started);
        assertTrue(thinkingRenderer.collapsed);
        assertTrue(thinkingRenderer.thinking().contains("我先判断用户意图。"));
        assertFalse(transcript.contains("思考过程"), transcript);
        assertTrue(transcript.contains("Thinking . . . >"), transcript);
        assertFalse(transcript.contains("我先判断用户意图。"), transcript);
        assertFalse(transcript.contains("这段思考在正文开始后才到达。"), transcript);
        assertTrue(thinkingRenderer.lateThinking.contains("这段思考在正文开始后才到达。"));
        assertFalse(transcript.contains("π 回复"), transcript);
        assertTrue(transcript.contains("好的"), transcript);
    }

    private LlmClient.StreamListener newStreamRenderer() throws Exception {
        Class<?> rendererClass = Class.forName("com.sidekick.agent.Agent$StreamRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (LlmClient.StreamListener) constructor.newInstance();
    }

    private LlmClient.StreamListener newStreamRenderer(Renderer renderer) throws Exception {
        Class<?> rendererClass = Class.forName("com.sidekick.agent.Agent$StreamRenderer");
        Constructor<?> constructor = rendererClass.getDeclaredConstructor(Renderer.class);
        constructor.setAccessible(true);
        return (LlmClient.StreamListener) constructor.newInstance(renderer);
    }

    private void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class ThinkingRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final StringBuilder thinking = new StringBuilder();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        private boolean started;
        private boolean ended;
        private boolean collapsed;
        private String lateThinking;

        @Override
        public void start() {
        }

        @Override
        public boolean supportsThinkingPanel() {
            return true;
        }

        @Override
        public void beginThinking(String label) {
            started = true;
        }

        @Override
        public void appendThinking(String delta) {
            thinking.append(delta);
        }

        @Override
        public void endThinking() {
            ended = true;
        }

        @Override
        public void collapseThinking() {
            collapsed = true;
            stream.println("Thinking . . . >");
        }

        @Override
        public void appendCollapsedThinking(String reasoning) {
            lateThinking = reasoning;
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public void close() {
            stream.close();
        }

        private String transcript() {
            return output.toString(StandardCharsets.UTF_8);
        }

        private String thinking() {
            return thinking.toString();
        }
    }
}
