package com.bank.aigateway.agent;

import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgenticLoopTest {

    ObjectMapper objectMapper = new ObjectMapper();
    ToolAwareLlmClient llmClient = mock(ToolAwareLlmClient.class);
    AgenticLoop loop;

    @BeforeEach
    void setUp() {
        loop = new AgenticLoop(llmClient, objectMapper);
    }

    @Test
    void 첫_응답이_end_turn이면_1턴으로_반환() {
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(endTurnResponse("최종 감사 의견", 100, 80));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), call -> "");

        assertThat(result.text()).isEqualTo("최종 감사 의견");
        assertThat(result.turnsUsed()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(80);
        verify(llmClient, times(1)).completeWithTools(any(), any(), any());
    }

    @Test
    void tool_use_후_end_turn이면_2턴으로_반환() {
        ToolCall call = new ToolCall("id-1", "get_policy_citation", objectMapper.createObjectNode());
        AtomicInteger executorCallCount = new AtomicInteger(0);

        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30))
                .thenReturn(endTurnResponse("tool 조회 후 의견", 120, 90));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> {
            executorCallCount.incrementAndGet();
            return "정책 문서 내용";
        });

        assertThat(result.text()).isEqualTo("tool 조회 후 의견");
        assertThat(result.turnsUsed()).isEqualTo(2);
        assertThat(result.inputTokens()).isEqualTo(170);
        assertThat(result.outputTokens()).isEqualTo(120);
        assertThat(executorCallCount.get()).isEqualTo(1);
    }

    @Test
    void maxTurns_초과_시_INSUFFICIENT_DATA_반환() {
        ToolCall call = new ToolCall("id-x", "get_similar_cases", objectMapper.createObjectNode());
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> "some result");

        assertThat(result.text()).contains("INSUFFICIENT_DATA");
        assertThat(result.turnsUsed()).isEqualTo(AgenticLoop.MAX_TURNS);
        verify(llmClient, times(AgenticLoop.MAX_TURNS)).completeWithTools(any(), any(), any());
    }

    @Test
    void tool_실행_예외_시_빈_결과로_루프_계속() {
        ToolCall call = new ToolCall("id-2", "get_reviewer_history", objectMapper.createObjectNode());

        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30))
                .thenReturn(endTurnResponse("예외 후 계속된 의견", 100, 70));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> {
            throw new RuntimeException("네트워크 오류");
        });

        assertThat(result.text()).isEqualTo("예외 후 계속된 의견");
        assertThat(result.turnsUsed()).isEqualTo(2);
    }

    @Test
    void 한_응답에_복수_tool_call이면_모두_실행() {
        ToolCall call1 = new ToolCall("id-a", "get_policy_citation", objectMapper.createObjectNode());
        ToolCall call2 = new ToolCall("id-b", "get_cohort_stats", objectMapper.createObjectNode());
        AtomicInteger executorCallCount = new AtomicInteger(0);

        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(multiToolUseResponse(List.of(call1, call2), 60, 40))
                .thenReturn(endTurnResponse("복수 tool 후 의견", 150, 100));

        loop.run("system", "user msg", List.of(), tc -> {
            executorCallCount.incrementAndGet();
            return "결과";
        });

        assertThat(executorCallCount.get()).isEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ClaudeAgenticResponse endTurnResponse(String text, int input, int output) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        return new ClaudeAgenticResponse("end_turn", content, text, List.of(), input, output);
    }

    private ClaudeAgenticResponse toolUseResponse(ToolCall call, int input, int output) {
        return multiToolUseResponse(List.of(call), input, output);
    }

    private ClaudeAgenticResponse multiToolUseResponse(List<ToolCall> calls, int input, int output) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", "도구를 사용해 추가 조사합니다.");
        for (ToolCall call : calls) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.set("input", call.input());
        }
        return new ClaudeAgenticResponse("tool_use", content, null, calls, input, output);
    }
}
