package com.bank.ai.admin;

import java.time.LocalDate;

public record ShadowDivergedResponse(
        int divergedCount,
        int totalCount,
        double agreementRate,
        double citationMissRate,
        LocalDate from,
        LocalDate to
) {}
