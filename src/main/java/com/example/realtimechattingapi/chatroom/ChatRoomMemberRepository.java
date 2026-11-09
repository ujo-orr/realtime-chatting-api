package com.example.realtimechattingapi.chatroom;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {
    boolean existsByRoomIdAndUserId(Long roomId, Long userId);
}
