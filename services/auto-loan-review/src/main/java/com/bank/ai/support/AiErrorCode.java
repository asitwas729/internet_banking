package com.bank.ai.support;

import com.bank.common.web.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    INFERENCE_UNAVAILABLE("AI_001", "추론 서버에 연결할 수 없습니다", HttpStatus.SERVICE_UNAVAILABLE),
    INFERENCE_FAILED("AI_002", "추론 요청이 실패했습니다", HttpStatus.BAD_GATEWAY),
    AUTO_REVIEW_DISABLED("AI_003", "자동심사 서비스가 일시 중단 상태입니다", HttpStatus.SERVICE_UNAVAILABLE),
    INFERENCE_INVALID_FEATURE("AI_004", "추론 입력 피처가 유효하지 않습니다", HttpStatus.UNPROCESSABLE_ENTITY),
    EMBEDDING_FAILED("AI_005", "임베딩 생성에 실패했습니다", HttpStatus.BAD_GATEWAY),
    EMBEDDING_DIMENSION_MISMATCH("AI_006", "임베딩 응답 차원이 일치하지 않습니다", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    private final String code;
    private final String message;
    private final HttpStatus status;
}
