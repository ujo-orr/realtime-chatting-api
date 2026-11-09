CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    token_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE chat_rooms (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_room_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE chat_room_members (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT uk_room_user UNIQUE (room_id, user_id),
    INDEX idx_member_user_room (user_id, room_id),
    CONSTRAINT fk_member_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_message_room_id (room_id, id),
    CONSTRAINT fk_message_room FOREIGN KEY (room_id) REFERENCES chat_rooms(id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES users(id)
) ENGINE=InnoDB;
