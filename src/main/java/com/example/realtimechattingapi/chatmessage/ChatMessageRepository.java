package com.example.realtimechattingapi.chatmessage;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Query("select m from ChatMessage m where m.roomId = :roomId and (:beforeId is null or m.id < :beforeId) order by m.id desc")
    List<ChatMessage> findHistory(@Param("roomId") Long roomId, @Param("beforeId") Long beforeId, Pageable pageable);

    @Query("select m from ChatMessage m where m.roomId = :roomId and m.id > :afterId order by m.id asc")
    List<ChatMessage> findAfter(@Param("roomId") Long roomId, @Param("afterId") long afterId, Pageable pageable);
}
