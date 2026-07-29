package com.baros.telegram;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TelegramPollingService {

    private final TelegramClient telegramClient;
    private final TelegramCommandHandler commandHandler;
    private final ChatSubscriberService chatSubscriberService;

    private long offset = 0;

    public TelegramPollingService(
            TelegramClient telegramClient,
            TelegramCommandHandler commandHandler,
            ChatSubscriberService chatSubscriberService
    ) {
        this.telegramClient = telegramClient;
        this.commandHandler = commandHandler;
        this.chatSubscriberService = chatSubscriberService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!telegramClient.isConfigured()) {
            System.out.println("Telegram bot is disabled: TELEGRAM_BOT_TOKEN is empty");
            return;
        }

        Thread.ofVirtual()
                .name("telegram-polling")
                .start(this::pollLoop);

        System.out.println("Telegram bot polling started");
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<TelegramUpdateResponse.Update> updates = telegramClient.getUpdates(offset);

                for (TelegramUpdateResponse.Update update : updates) {
                    if (update.updateId() != null) {
                        offset = Math.max(offset, update.updateId() + 1);
                    }

                    handleUpdate(update);
                }
            } catch (Exception exception) {
                System.out.println("Telegram polling error: " + exception.getMessage());
                sleep(3000);
            }
        }
    }

    private void handleUpdate(TelegramUpdateResponse.Update update) {
        if (update == null || update.message() == null || update.message().chat() == null) {
            return;
        }

        Long chatId = update.message().chat().id();
        String text = update.message().text();

        chatSubscriberService.register(chatId);

        System.out.println("Telegram message from chatId=" + chatId + ": " + text);

        String answer = commandHandler.handle(text);
        telegramClient.sendMessage(chatId, answer);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}