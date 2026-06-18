package com.bank.ai.admin;

import jakarta.validation.constraints.NotBlank;

public record RegenerateOpinionRequest(@NotBlank String reason) {}
