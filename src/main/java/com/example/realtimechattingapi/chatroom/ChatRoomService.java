package com.example.realtimechattingapi.chatroom;

import com.example.realtimechattingapi.global.*;
import com.example.realtimechattingapi.user.UserRepository;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class ChatRoomService {
    private final ChatRoomRepository rooms;
    private final ChatRoomMemberRepository members;
    private final UserRepository users;
    private final com.example.realtimechattingapi.notification.NotificationService notifications;

    @Transactional
    public RoomView create(Long creatorId, CreateRoom request) {
        Set<Long> ids = new LinkedHashSet<>(request.participantIds());
        ids.add(creatorId);
        if (users.countByIdIn(ids) != ids.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "참여할 사용자를 찾을 수 없습니다.");
        }
        ChatRoom room = rooms.save(new ChatRoom(request.name().strip(), creatorId));
        members.saveAll(ids.stream().map(id -> new ChatRoomMember(room.getId(), id)).toList());
        notifications.roomCreated(room.getId(), creatorId, ids);
        return RoomView.of(room);
    }

    @Transactional(readOnly = true)
    public CursorPage<RoomView> list(Long userId, Long beforeId, int limit) {
        List<ChatRoom> found = rooms.findMyRooms(userId, beforeId, PageRequest.of(0, limit + 1));
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit).map(RoomView::of).toList();
        return new CursorPage<>(items, more ? items.getLast().id() : null, more);
    }

    public record CreateRoom(@NotBlank @Size(max = 100) String name,
                             @NotNull @Size(max = 100) List<@NotNull @Positive Long> participantIds) {}
    public record RoomView(Long id, String name, Long createdBy, Instant createdAt) {
        static RoomView of(ChatRoom room) { return new RoomView(room.getId(), room.getName(), room.getCreatedBy(), room.getCreatedAt()); }
    }
}
