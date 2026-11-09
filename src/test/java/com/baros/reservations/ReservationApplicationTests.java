package com.baros.reservations;

import com.baros.fund.*;
import com.baros.telegram.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "reservations.token=")
@ActiveProfiles("reservations")
class ReservationApplicationTests {
    @Autowired ApplicationContext context;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("reservations.database", () -> Path.of(System.getProperty("java.io.tmpdir"), "reservations-" + UUID.randomUUID(), "test.sqlite").toString());
    }
    @Test void isolatedProfileRequiresNoEsuplTokenAndStartsNoFinancialWorkers() {
        assertThat(context.getBeansOfType(ReservationPollingService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TelegramPollingService.class)).isEmpty();
        assertThat(context.getBeansOfType(DailySummaryScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(FundPollingService.class)).isEmpty();
        assertThat(context.getBeansOfType(FundLedger.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("esupl.token")).isEmpty();
    }
}
