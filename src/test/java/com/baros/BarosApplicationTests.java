package com.baros;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"esupl.token=test", "telegram.bot-token=", "telegram.daily-summary.cron=-"})
class BarosApplicationTests {

	@Test
	void contextLoads() {
	}

}
