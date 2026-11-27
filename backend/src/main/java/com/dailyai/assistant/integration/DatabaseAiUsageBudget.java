package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.AiUsageDaily;
import com.dailyai.assistant.repository.AiUsageDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Database-backed daily budget. Reservations use a short REQUIRES_NEW
 * transaction and a row lock, so a crash, restart or a different backend
 * instance cannot reset the counter. The lock is always released before the
 * provider HTTP call.
 */
@Component
public class DatabaseAiUsageBudget implements AiUsageBudget {

    private static final Logger log = LoggerFactory.getLogger(DatabaseAiUsageBudget.class);
    private static final int MAX_CREATE_RETRIES = 2;

    private final AiUsageDailyRepository repository;
    private final TransactionTemplate transactionTemplate;

    public DatabaseAiUsageBudget(AiUsageDailyRepository repository,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean tryReserve(LocalDate usageDate, int maxCalls) {
        for (int attempt = 1; attempt <= MAX_CREATE_RETRIES; attempt++) {
            try {
                Boolean reserved = transactionTemplate.execute(
                        status -> reserveInTransaction(usageDate, maxCalls));
                return Boolean.TRUE.equals(reserved);
            } catch (RowCreationConflict conflict) {
                // Another request created today's row first. Retry against the
                // committed row in a new transaction.
            } catch (RuntimeException ex) {
                log.warn("AI usage ledger unavailable; failing closed: {}", safeMessage(ex));
                return false;
            }
        }
        log.warn("AI usage ledger row could not be created or locked; failing closed");
        return false;
    }

    @Override
    public int attempts(LocalDate usageDate) {
        return repository.findById(usageDate).map(AiUsageDaily::getAttempts).orElse(0);
    }

    @Override
    public void recordSuccess(LocalDate usageDate, int inputTokens, int outputTokens) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AiUsageDaily row = findOrCreate(usageDate);
                row.setSuccesses(row.getSuccesses() + 1);
                row.setInputTokens(row.getInputTokens() + Math.max(0, inputTokens));
                row.setOutputTokens(row.getOutputTokens() + Math.max(0, outputTokens));
                row.setUpdatedAt(Instant.now());
                repository.saveAndFlush(row);
            });
        } catch (RuntimeException ex) {
            // The attempt is already reserved. Losing telemetry must not make the
            // provider result disappear.
            log.warn("Could not record successful AI usage: {}", safeMessage(ex));
        }
    }

    @Override
    public void recordFailure(LocalDate usageDate) {
        recordFailure(usageDate, 0, 0);
    }

    @Override
    public void recordFailure(LocalDate usageDate, int inputTokens, int outputTokens) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                AiUsageDaily row = findOrCreate(usageDate);
                row.setFailures(row.getFailures() + 1);
                row.setInputTokens(row.getInputTokens() + Math.max(0, inputTokens));
                row.setOutputTokens(row.getOutputTokens() + Math.max(0, outputTokens));
                row.setUpdatedAt(Instant.now());
                repository.saveAndFlush(row);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not record failed AI usage: {}", safeMessage(ex));
        }
    }

    private boolean reserveInTransaction(LocalDate usageDate, int maxCalls) {
        AiUsageDaily row = findOrCreate(usageDate);
        if (maxCalls > 0 && row.getAttempts() >= maxCalls) {
            return false;
        }
        row.setAttempts(row.getAttempts() + 1);
        row.setUpdatedAt(Instant.now());
        repository.saveAndFlush(row);
        return true;
    }

    private AiUsageDaily findOrCreate(LocalDate usageDate) {
        AiUsageDaily existing = repository.findByUsageDateForUpdate(usageDate).orElse(null);
        if (existing != null) {
            return existing;
        }
        try {
            return repository.saveAndFlush(new AiUsageDaily(usageDate));
        } catch (DataIntegrityViolationException ex) {
            throw new RowCreationConflict();
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static final class RowCreationConflict extends RuntimeException {
    }
}
