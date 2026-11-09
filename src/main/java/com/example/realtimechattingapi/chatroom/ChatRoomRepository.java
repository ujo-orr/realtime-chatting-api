package com.example.realtimechattingapi.chatroom;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    @Query("select r from ChatRoom r where r.id in (select m.roomId from ChatRoomMember m where m.userId = :userId) and (:beforeId is null or r.id < :beforeId) order by r.id desc")
    List<ChatRoom> findMyRooms(@Param("userId") Long userId, @Param("beforeId") Long beforeId, Pageable pageable);
}
