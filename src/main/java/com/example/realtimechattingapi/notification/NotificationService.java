package com.example.realtimechattingapi.notification;

import com.example.realtimechattingapi.global.CursorPage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Collection;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;

    @Transactional
    public void roomCreated(Long roomId, Long creatorId, Collection<Long> participantIds) {
        for (Long userId : participantIds) {
            if (userId.equals(creatorId)) continue;
            var saved = notifications.save(new Notification(userId, roomId));
            events.publishEvent(new Created(View.of(saved), userId));
        }
    }

    @Transactional(readOnly = true)
    public CursorPage<View> history(Long userId, long afterId, int limit) {
        var found = notifications.findAfter(userId, afterId, PageRequest.of(0, limit + 1));
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit).map(View::of).toList();
        return new CursorPage<>(items, more ? items.getLast().id() : null, more);
    }

    public record Created(View notification, Long recipientId) {}
    public record View(Long id, Long roomId, Notification.Type type, Instant createdAt) {
        static View of(Notification n) { return new View(n.getId(), n.getRoomId(), n.getType(), n.getCreatedAt()); }
    }
}
