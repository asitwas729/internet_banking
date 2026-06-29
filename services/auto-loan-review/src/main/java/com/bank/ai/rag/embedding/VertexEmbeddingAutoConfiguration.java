package com.bank.ai.rag.embedding;

import org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Vertex AI 임베딩 자동구성을 {@code ai.rag.embedding.provider=vertex} 일 때만 활성화.
 *
 * <p>{@code application.yml} 의 {@code spring.autoconfigure.exclude} 로
 * {@link VertexAiTextEmbeddingAutoConfiguration} 을 전역 제외해 둔다 — 그래야 stub/test 부팅 시
 * {@code EmbeddingModel} 자동구성이 돌지 않아 GCP project-id·자격증명 없이도 기동된다.
 *
 * <p>provider=vertex 인 경우에만 본 설정이 {@link ImportAutoConfiguration} 으로 해당 자동구성을
 * 다시 들여와 {@code EmbeddingModel} 빈을 생성한다. {@code @ImportAutoConfiguration} 은
 * {@code spring.autoconfigure.exclude}(= {@code @EnableAutoConfiguration} 전용) 의 영향을 받지
 * 않으므로, 전역 제외와 조건부 재적재가 충돌 없이 공존한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "vertex")
@ImportAutoConfiguration(VertexAiTextEmbeddingAutoConfiguration.class)
public class VertexEmbeddingAutoConfiguration {
}
