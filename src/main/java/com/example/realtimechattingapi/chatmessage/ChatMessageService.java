package com.example.realtimechattingapi.chatmessage;

import com.example.realtimechattingapi.chatroom.RoomAccessService;
import com.example.realtimechattingapi.global.CursorPage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service @RequiredArgsConstructor
public class ChatMessageService {
    private final RoomAccessService access;
    private final ChatMessageRepository messages;
    private final com.example.realtimechattingapi.auth.AccessTokenValidator validator;
    private final org.springframework.context.ApplicationEventPublisher events;

    @Transactional
    public void send(com.example.realtimechattingapi.auth.JwtTokenProvider.TokenIdentity identity, Long roomId, String content) {
        validator.requireCurrent(identity);
        access.requireMember(roomId, identity.userId());
        var saved = messages.save(new ChatMessage(roomId, identity.userId(), content));
        events.publishEvent(new ChatMessageEvents.Saved(new MessageView(saved.getId(), saved.getRoomId(),
                saved.getSenderId(), saved.getContent(), saved.getCreatedAt())));
    }

    @Transactional(readOnly = true)
    public CursorPage<MessageView> history(Long userId, Long roomId, Long beforeId, int limit) {
        access.requireMember(roomId, userId);
        var found = messages.findHistory(roomId, beforeId, PageRequest.of(0, limit + 1));
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit)
                .map(m -> new MessageView(m.getId(), m.getRoomId(), m.getSenderId(), m.getContent(), m.getCreatedAt())).toList();
        return new CursorPage<>(items, more ? items.getLast().id() : null, more);
    }

    public record MessageView(Long id, Long roomId, Long senderId, String content, Instant createdAt) {}
}
