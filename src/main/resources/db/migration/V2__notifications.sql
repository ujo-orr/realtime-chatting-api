CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_notification_recipient_id (recipient_id, id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_id) REFERENCES users(id),
    CONSTRAINT fk_notification_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id)
) ENGINE=InnoDB;
