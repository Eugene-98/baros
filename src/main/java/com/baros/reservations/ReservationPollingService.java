package com.baros.reservations;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

import static com.baros.reservations.ReservationStore.*;

@Service
@Profile("reservations")
public class ReservationPollingService {
    private static final Logger log = LoggerFactory.getLogger(ReservationPollingService.class);
    private final ReservationTelegram telegram;
    private final ReservationStore store;
    private final ReservationBot bot;
    private Thread worker;

    public ReservationPollingService(ReservationTelegram telegram, ReservationStore store, ReservationBot bot) {
        this.telegram = telegram; this.store = store; this.bot = bot;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!telegram.configured()) { log.warn("Reservation bot disabled: RESERVATION_BOT_TOKEN is empty"); return; }
        try { telegram.verify(); }
        catch (RuntimeException e) {
            throw new IllegalStateException("Reservation Telegram verification failed (" + e.getClass().getSimpleName() + "). Check the new test token, network and webhook.");
        }
        worker = Thread.ofVirtual().name("reservation-polling").start(this::loop);
    }
    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try { pollOnce(); }
            catch (Exception e) {
                // Exception messages from HTTP clients can contain the token URL or guest data.
                log.warn("Reservation polling deferred ({})", e.getClass().getSimpleName());
                try { Thread.sleep(3000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
    void pollOnce() {
        bot.reminders();
        flush();
        for (var update : telegram.updates(store.offset())) {
            bot.process(update);
            flush();
        }
    }
    void flush() {
        for (var item : store.outbox(Instant.now().getEpochSecond())) {
            long id = number(item, "id");
            if (!store.current(item, Instant.now().getEpochSecond())) { store.delivered(id); continue; }
            try {
                telegram.call(str(item, "method"), ReservationBot.map(JSON.readValue(str(item, "body"), Map.class)));
                store.delivered(id);
            } catch (ReservationTelegram.Failure e) {
                if (e.code == 400 || e.code == 403) {
                    // An expired callback or a blocked recipient must not block other bookings.
                    store.delivered(id);
                    log.warn("Reservation notification undeliverable, outbox ID {}, code {}", id, e.code);
                } else {
                    store.retry(id, Instant.now().getEpochSecond() + Math.max(e.retryAfter, backoff(item)));
                    if (e.code == 429) break;
                }
            } catch (RuntimeException e) {
                store.retry(id, Instant.now().getEpochSecond() + backoff(item));
            }
        }
    }
    private long backoff(Map<String, Object> item) { return Math.min(300, 5L << Math.min(6, number(item, "attempts"))); }
    @PreDestroy public void stop() { if (worker != null) worker.interrupt(); }
}
