package com.baros.fund;

import com.baros.telegram.TelegramClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Profile("dvoyka")
public class FundPollingService {
    private static final Logger log = LoggerFactory.getLogger(FundPollingService.class);
    private final TelegramClient telegram;
    private final FundCommandHandler commands;
    private final FundLedger ledger;
    private Thread worker;

    public FundPollingService(TelegramClient telegram, FundCommandHandler commands, FundLedger ledger) {
        this.telegram = telegram;
        this.commands = commands;
        this.ledger = ledger;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!telegram.isConfigured()) {
            log.warn("Dvoyka polling disabled: DVOYKA_BOT_TOKEN is empty");
            return;
        }
        worker = Thread.ofVirtual().name("dvoyka-polling").start(this::loop);
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (Exception e) {
                log.error("Dvoyka update failed ({}); retrying without acknowledging it", e.getClass().getSimpleName());
                try { Thread.sleep(5000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    void pollOnce() {
        for (var update : telegram.getUpdates(ledger.offset())) {
            if (update == null || update.updateId() == null) throw new IllegalStateException("Invalid Telegram update");
            if (update.updateId() < ledger.offset()) continue;
            if (update.message() != null && update.message().chat() != null
                    && update.message().chat().id() != null && update.message().text() != null) {
                String answer = commands.handle(update);
                telegram.sendMessage(update.message().chat().id(), answer, FundCommandHandler.keyboard());
            }
            // Commit the offset only after the operation AND reply succeed.
            // Replayed writes are deduplicated by their original Telegram update ID.
            ledger.acknowledge(update.updateId());
        }
    }

    @PreDestroy
    public void stop() {
        if (worker != null) worker.interrupt();
    }
}
