package com.baros.telegram;

import com.baros.sales.SalesAnalyticsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class DailySummaryScheduler {

    private final TelegramClient telegramClient;
    private final SalesAnalyticsService salesAnalyticsService;
    private final ChatSubscriberService chatSubscriberService;
    private final boolean enabled;

    public DailySummaryScheduler(
            TelegramClient telegramClient,
            SalesAnalyticsService salesAnalyticsService,
            ChatSubscriberService chatSubscriberService,
            @Value("${telegram.daily-summary.enabled}") boolean enabled
    ) {
        this.telegramClient = telegramClient;
        this.salesAnalyticsService = salesAnalyticsService;
        this.chatSubscriberService = chatSubscriberService;
        this.enabled = enabled;
    }

    @Scheduled(
            cron = "${telegram.daily-summary.cron}",
            zone = "${bar.time-zone}"
    )
    public void sendDailySummary() {
        if (!enabled) {
            return;
        }

        if (!telegramClient.isConfigured()) {
            System.out.println("Daily summary skipped: TELEGRAM_BOT_TOKEN is empty");
            return;
        }

        Set<Long> chatIds = chatSubscriberService.getAllChatIds();

        if (chatIds.isEmpty()) {
            System.out.println("Daily summary skipped: no Telegram subscribers");
            return;
        }

        String report = "Утренняя сводка\n\n"
                + salesAnalyticsService.formatYesterdaySummary();

        for (Long chatId : chatIds) {
            try {
                telegramClient.sendMessage(chatId, report);
                System.out.println("Daily summary sent to chatId=" + chatId);
            } catch (Exception exception) {
                System.out.println("Failed to send daily summary to chatId="
                        + chatId
                        + ": "
                        + exception.getMessage());
            }
        }
    }
}