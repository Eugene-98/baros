package com.baros.telegram;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChatSubscriberService {

    private final Path subscribersFile;
    private final Set<Long> chatIds = ConcurrentHashMap.newKeySet();

    public ChatSubscriberService(
            @Value("${telegram.subscribers-file}") String subscribersFile
    ) {
        this.subscribersFile = Path.of(subscribersFile);
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(subscribersFile)) {
            return;
        }

        try {
            Files.readAllLines(subscribersFile)
                    .stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(Long::parseLong)
                    .forEach(chatIds::add);
        } catch (Exception exception) {
            System.out.println("Failed to load Telegram subscribers: " + exception.getMessage());
        }
    }

    public void register(Long chatId) {
        if (chatId == null) {
            return;
        }

        boolean added = chatIds.add(chatId);

        if (added) {
            save();
            System.out.println("Registered Telegram chatId=" + chatId);
        }
    }

    public Set<Long> getAllChatIds() {
        return Set.copyOf(chatIds);
    }

    private synchronized void save() {
        try {
            Path parent = subscribersFile.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            String content = chatIds.stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(Collectors.joining(System.lineSeparator()));

            Files.writeString(subscribersFile, content);
        } catch (IOException exception) {
            System.out.println("Failed to save Telegram subscribers: " + exception.getMessage());
        }
    }
}