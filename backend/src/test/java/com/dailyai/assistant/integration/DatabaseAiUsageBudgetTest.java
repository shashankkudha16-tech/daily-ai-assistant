package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.AiUsageDaily;
import com.dailyai.assistant.repository.AiUsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(DatabaseAiUsageBudget.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatabaseAiUsageBudgetTest {

    @Autowired
    private DatabaseAiUsageBudget budget;

    @Autowired
    private AiUsageDailyRepository repository;

    @BeforeEach
    void cleanLedger() {
        repository.deleteAll();
    }

    @Test
    void reservesOnlyUpToTheDailyCapAndKeepsDaysSeparate() {
        LocalDate day = LocalDate.of(2026, 11, 19);

        assertThat(budget.tryReserve(day, 2)).isTrue();
        assertThat(budget.tryReserve(day, 2)).isTrue();
        assertThat(budget.tryReserve(day, 2)).isFalse();
        assertThat(budget.attempts(day)).isEqualTo(2);
        assertThat(budget.tryReserve(day.plusDays(1), 2)).isTrue();
        assertThat(budget.attempts(day.plusDays(1))).isEqualTo(1);
    }

    @Test
    void recordsSuccessAndFailureTelemetryOnTheReservedRow() {
        LocalDate day = LocalDate.of(2026, 11, 19);
        budget.tryReserve(day, 10);
        budget.tryReserve(day, 10);

        budget.recordSuccess(day, 120, 80);
        budget.recordFailure(day, 30, 20);

        AiUsageDaily row = repository.findById(day).orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getSuccesses()).isEqualTo(1);
        assertThat(row.getFailures()).isEqualTo(1);
        assertThat(row.getInputTokens()).isEqualTo(150);
        assertThat(row.getOutputTokens()).isEqualTo(100);
    }

    @Test
    void concurrentReservationsCannotExceedTheCap() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 19);
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                Callable<Boolean> task = () -> {
                    ready.countDown();
                    start.await();
                    return budget.tryReserve(day, 3);
                };
                results.add(executor.submit(task));
            }
            ready.await();
            start.countDown();

            long granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    granted++;
                }
            }
            assertThat(granted).isEqualTo(3);
            assertThat(budget.attempts(day)).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }
}
