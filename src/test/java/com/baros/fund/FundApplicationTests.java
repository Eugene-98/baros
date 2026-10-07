package com.baros.fund;

import com.baros.telegram.DailySummaryScheduler;
import com.baros.telegram.TelegramPollingService;
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

@SpringBootTest(properties = {"esupl.token=test", "telegram.bot-token=", "fund.sync.cron=-"})
@ActiveProfiles("dvoyka")
class FundApplicationTests {
    @Autowired ApplicationContext context;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String file = Path.of(System.getProperty("java.io.tmpdir"), "baros-test-" + UUID.randomUUID(), "fund.sqlite").toString();
        properties.add("fund.database", () -> file);
    }
    @Test void fundProfileStartsAndDoesNotLaunchReportBotOrReportScheduler() {
        assertThat(context.getBeansOfType(FundPollingService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TelegramPollingService.class)).isEmpty();
        assertThat(context.getBeansOfType(DailySummaryScheduler.class)).isEmpty();
        assertThat(context.getBean(FundLedger.class).snapshot().balance()).isNull();
    }
}
