package com.bank.ai.drift;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriftAlertService {

    private final MeterRegistry registry;

    /** CRITICAL PSI → 메트릭 카운터 + WARN 로그. */
    public void alert(PsiDriftReport report) {
        if (report.status() == PsiStatus.CRITICAL) {
            Counter.builder("ai.drift.psi.critical.total")
                .tag("feature", report.featureName())
                .register(registry)
                .increment();
            log.warn("[Drift] PSI CRITICAL feature={} psi={}", report.featureName(), report.psiValue());
        }
        // 최신 PSI Gauge 등록
        registry.gauge("ai.drift.psi.value",
            List.of(io.micrometer.core.instrument.Tag.of("feature", report.featureName())),
            report.psiValue());
    }
}
