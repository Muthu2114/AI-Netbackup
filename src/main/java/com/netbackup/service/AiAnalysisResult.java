package com.netbackup.service;

import java.util.List;

/** Validated answer of the AI assistant. riskLevel is always LOW, MEDIUM or HIGH. */
public record AiAnalysisResult(String riskLevel, String explanation, String impact,
                               String recommendedAction, List<String> keyChanges, String model) {}
