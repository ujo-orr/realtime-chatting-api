package com.example.realtimechattingapi.chatmessage;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long roomId;
    @Column(nullable = false) private Long senderId;
    @Column(nullable = false, length = 2000) private String content;
    @Column(nullable = false) private Instant createdAt;
    public ChatMessage(Long roomId, Long senderId, String content) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.createdAt = Instant.now();
    }
}
