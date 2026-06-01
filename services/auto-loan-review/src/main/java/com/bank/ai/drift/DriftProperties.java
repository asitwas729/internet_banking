package com.bank.ai.drift;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ai.drift")
public record DriftProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("v1") String modelVersion,
    @DefaultValue("0.10") double psiWarningThreshold,
    @DefaultValue("0.20") double psiCriticalThreshold,
    @DefaultValue("0.05") double fairnessGapThreshold,
    @DefaultValue("6") int bucketCount
) {}
