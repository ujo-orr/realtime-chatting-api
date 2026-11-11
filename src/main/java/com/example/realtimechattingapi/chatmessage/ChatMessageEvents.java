package com.example.realtimechattingapi.chatmessage;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component @RequiredArgsConstructor
public class ChatMessageEvents {
    private final SimpMessagingTemplate messaging;
    public record Saved(ChatMessageService.MessageView message) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deliver(Saved event) {
        messaging.convertAndSend("/topic/rooms/" + event.message().roomId(), event.message());
    }
}
