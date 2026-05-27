package com.bank.loan.advisory.gateway;

import java.util.List;

public record GatewayAnalysisRequest(
        String analysisType,
        Long revId,
        Long reviewerId,
        String reviewOpinionText,
        List<GatewaySignalSummary> signals
) {
    public record GatewaySignalSummary(
            String ruleCd,
            String severityCd,
            String signalMetric,
            double observedValue,
            double thresholdValue
    ) {}
}
