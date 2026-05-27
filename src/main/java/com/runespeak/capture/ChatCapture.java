package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static net.runelite.api.ChatMessageType.*;

@Slf4j
@Singleton
public class ChatCapture {
    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    @Getter
    private final List<TranslatedMessage> translatedMessages = new ArrayList<>();

    /**
     * MessageNode IDs that have already been translated.
     * client.refreshChat() re-fires ChatMessage events for existing nodes —
     * we track which node IDs we've already processed to avoid re-translating.
     */
    private final Set<Integer> translatedNodeIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Inject
    public ChatCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    private static boolean isGameMessage(ChatMessageType type) {
        return type == GAMEMESSAGE
                || type == MESBOX
                || type == DIALOG
                || type == NPC_SAY
                || type == LEVELUPMESSAGE
                || type == WELCOME
                || type == BROADCAST;
    }

    public void handleChatMessage(ChatMessage event) {
        log.debug("ChatMessage type={} text='{}'", event.getType(),
                event.getMessage() != null ? truncate(event.getMessage(), 60) : "null");

        if (isGameMessage(event.getType())) {
            if (!config.translateGameMessages()) return;
        } else {
            if (!config.translateChat()) return;
        }

        String message = event.getMessage();
        if (message == null || message.isEmpty()) return;

        MessageNode messageNode = event.getMessageNode();
        if (messageNode == null) return;

        // Guard: skip nodes we have already translated to prevent the loop where
        // client.refreshChat() re-emits ChatMessage events for nodes we just modified.
        int nodeId = messageNode.getId();
        if (translatedNodeIds.contains(nodeId)) return;

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        translator.translateAsync(message).thenAccept(translated -> {
            if (translated.startsWith("⏳")) return;
            if (!translated.equals(message)) {
                // Mark as translated BEFORE refreshChat() to block the re-fire
                translatedNodeIds.add(nodeId);
                messageNode.setRuneLiteFormatMessage(translated);
                client.refreshChat();

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
        translatedNodeIds.clear();
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

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}