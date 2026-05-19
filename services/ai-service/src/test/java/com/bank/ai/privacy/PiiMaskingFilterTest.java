package com.bank.ai.privacy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskingFilterTest {

    private final PiiMaskingFilter filter = new PiiMaskingFilter();

    @Test
    void 주민등록번호_마스킹된다() {
        var result = filter.mask("홍길동 900101-1234567 신청");
        assertThat(result.maskedText()).doesNotContain("900101-1234567");
        assertThat(result.mapping().values()).contains("900101-1234567");
    }

    @Test
    void 휴대전화_마스킹된다() {
        var result = filter.mask("연락처 010-1234-5678 입니다");
        assertThat(result.maskedText()).doesNotContain("010-1234-5678");
    }

    @Test
    void 카드번호_마스킹된다() {
        var result = filter.mask("결제 카드 4111-1111-1111-1111");
        assertThat(result.maskedText()).doesNotContain("4111-1111-1111-1111");
    }

    @Test
    void 이메일_마스킹된다() {
        var result = filter.mask("문의 user@example.com");
        assertThat(result.maskedText()).doesNotContain("user@example.com");
    }

    @Test
    void 동일_원문은_동일_토큰으로_치환된다() {
        var result = filter.mask("홍길동님 거래내역. 홍길동님 잔액 확인.");
        // 같은 이름 두 번 등장 -> 매핑 1개
        long nameMappings = result.mapping().keySet().stream()
                .filter(k -> k.contains("NAME"))
                .count();
        assertThat(nameMappings).isEqualTo(1);
    }

    @Test
    void 역치환으로_원문_복원된다() {
        String original = "홍길동 010-1234-5678 user@example.com 문의";
        var result = filter.mask(original);
        // LLM 이 마스킹 토큰을 그대로 echo 했다고 가정
        String restored = result.unmask(result.maskedText());
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void 빈입력_안전처리() {
        assertThat(filter.mask(null).maskedText()).isNull();
        assertThat(filter.mask("").maskedText()).isEmpty();
        assertThat(filter.mask("PII 없는 일반 문장").mapping()).isEmpty();
    }

    @Test
    void 계좌번호_마스킹된다() {
        var result = filter.mask("입금계좌 123-456-789012");
        assertThat(result.maskedText()).doesNotContain("123-456-789012");
    }
}
