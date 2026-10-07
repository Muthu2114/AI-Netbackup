package com.netbackup.service;

import java.util.List;
import java.util.Map;

/** Combines the configuration diff, local Random Forest prediction, and supporting rule-based findings. */
public class AiConfigAnalyzer {
    public record Outcome(AiAnalysisResult ai, RiskAnalyzer.Result local, int changedLines) {}

    private final WekaRandomForestModel model;
    private final RiskAnalyzer risk = new RiskAnalyzer();

    public AiConfigAnalyzer(WekaRandomForestModel model) {
        this.model = model;
    }

    public String getModel() { return WekaRandomForestModel.MODEL_NAME; }

    public Outcome analyze(String deviceName, int fromVersion, int toVersion, String oldConfig, String newConfig) {
        List<Map<String, String>> diff = DiffUtil.diff(oldConfig, newConfig);
        int changed = (int) diff.stream().filter(line -> !line.get("type").equals("same")).count();
        if (changed == 0) throw new AiException(400, "These two versions are identical - there is nothing to analyze.");
        RiskAnalyzer.Result local = risk.analyze(oldConfig, newConfig);
        WekaRandomForestModel.Prediction prediction = model.predict(oldConfig, newConfig);
        String versions = "v" + fromVersion + " to v" + toVersion;
        String subject = deviceName.isBlank() ? "The configuration change" : "The change on " + deviceName;
        String explanation = subject + " from " + versions + " was classified as " + prediction.riskLevel()
                + " by a locally trained Random Forest model. " + prediction.summary();
        String impact = switch (prediction.riskLevel()) {
            case "HIGH" -> "This change may expose management access or affect device availability, connectivity, or access controls.";
            case "MEDIUM" -> "This change may alter network behavior or access policy and should be checked against the intended design.";
            default -> "No high-impact security or availability signals were detected in the changed configuration lines.";
        };
        String action = switch (prediction.riskLevel()) {
            case "HIGH" -> "Review the flagged configuration changes, validate the intended security and connectivity impact, and confirm a rollback path before applying.";
            case "MEDIUM" -> "Verify the changed settings against the approved network design and monitor the device after applying.";
            default -> "Proceed with the normal change review and confirm the resulting configuration.";
        };
        AiAnalysisResult result = new AiAnalysisResult(prediction.riskLevel(), explanation, impact, action,
                prediction.keyChanges(), getModel());
        return new Outcome(result, local, changed);
    }
}
