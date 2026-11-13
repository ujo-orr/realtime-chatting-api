package com.example.realtimechattingapi.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query("select n from Notification n where n.recipientId = :userId and n.id > :afterId order by n.id asc")
    List<Notification> findAfter(@Param("userId") Long userId, @Param("afterId") long afterId, Pageable pageable);
}
