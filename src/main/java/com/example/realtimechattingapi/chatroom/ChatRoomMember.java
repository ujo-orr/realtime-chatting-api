package com.example.realtimechattingapi.chatroom;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "chat_room_members", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long roomId;

    @Column(nullable = false)
    private Long userId;

    public ChatRoomMember(Long roomId, Long userId) { this.roomId = roomId; this.userId = userId; }
}
