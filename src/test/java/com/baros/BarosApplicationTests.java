package com.baros;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"esupl.token=test", "telegram.bot-token=", "telegram.daily-summary.cron=-"})
class BarosApplicationTests {
    @Autowired Environment environment;

	@Test
	void contextLoads() {
		assertThat(environment.getProperty("bar.day-start-hour")).isEqualTo("3");
	}

}
