package com.quant.risk.pretrade;

public record RiskDecision(boolean approved, String rejectCode, String rejectReason) {

    public static RiskDecision approve() {
        return new RiskDecision(true, null, null);
    }

    public static RiskDecision reject(String code, String reason) {
        return new RiskDecision(false, code, reason);
    }
}
