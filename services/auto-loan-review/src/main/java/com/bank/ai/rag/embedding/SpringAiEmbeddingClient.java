package com.bank.ai.rag.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 기반 임베딩 클라이언트 — Vertex AI text-embedding-005.
 *
 * <p>{@code ai.rag.embedding.provider=vertex} 시 활성.
 * {@code spring.ai.vertex.ai.embedding.*} 설정으로 project-id·location·model 지정.
 * 운영 provider swap 은 {@code AI_RAG_EMB_PROVIDER} 환경 변수 한 줄 변경.
 */
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "vertex")
@RequiredArgsConstructor
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        return toFloatArray(embeddingModel.embed(text));
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return embeddingModel.embed(texts).stream()
                .map(this::toFloatArray)
                .toList();
    }

    private float[] toFloatArray(List<Double> doubles) {
        float[] arr = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) arr[i] = doubles.get(i).floatValue();
        return arr;
    }
}
