package com.dailyai.assistant.integration;

import java.time.LocalDate;

/**
 * Authoritative daily provider-attempt budget. Implementations must reserve
 * before an HTTP call and must not throw through the AI client; callers treat
 * an unavailable ledger as no budget (fail closed).
 */
public interface AiUsageBudget {

    /** Reserves one attempt, or returns false when the cap is exhausted. */
    boolean tryReserve(LocalDate usageDate, int maxCalls);

    /** Number of attempts already recorded for the date. */
    int attempts(LocalDate usageDate);

    /** Records a successful provider call and its usage. */
    void recordSuccess(LocalDate usageDate, int inputTokens, int outputTokens);

    /** Records a failed provider attempt. */
    void recordFailure(LocalDate usageDate);

    /** Records token usage for an unsuccessful but completed provider response. */
    default void recordFailure(LocalDate usageDate, int inputTokens, int outputTokens) {
        recordFailure(usageDate);
    }
}
