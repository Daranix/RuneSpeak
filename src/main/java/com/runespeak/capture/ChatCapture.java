package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Singleton
public class ChatCapture {
    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    @Getter
    private final List<TranslatedMessage> translatedMessages = new ArrayList<>();

    @Inject
    public ChatCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void handleChatMessage(ChatMessage event) {
        if (!config.translateChat()) return;

        String message = event.getMessage();
        if (message == null || message.isEmpty()) return;

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        translator.translateAsync(message).thenAccept(translated -> {
            if (!translated.equals(message)) {
                synchronized (translatedMessages) {
                    translatedMessages.add(new TranslatedMessage(
                            event.getType(),
                            event.getName(),
                            message,
                            translated,
                            System.currentTimeMillis()
                    ));
                    if (translatedMessages.size() > 200) {
                        translatedMessages.remove(0);
                    }
                }
            }
        });
    }

    public void clear() {
        synchronized (translatedMessages) {
            translatedMessages.clear();
        }
    }

    @Getter
    public static class TranslatedMessage {
        private final ChatMessageType type;
        private final String sender;
        private final String original;
        private final String translation;
        private final long timestamp;

        public TranslatedMessage(ChatMessageType type, String sender, String original, String translation, long timestamp) {
            this.type = type;
            this.sender = sender;
            this.original = original;
            this.translation = translation;
            this.timestamp = timestamp;
        }
    }
}
