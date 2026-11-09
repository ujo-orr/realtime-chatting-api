package com.example.realtimechattingapi.chatroom;

import com.example.realtimechattingapi.global.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class RoomAccessService {
    private final ChatRoomRepository rooms;
    private final ChatRoomMemberRepository members;

    @Transactional(readOnly = true)
    public void requireMember(Long roomId, Long userId) {
        if (!rooms.existsById(roomId)) throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다.");
        if (!members.existsByRoomIdAndUserId(roomId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROOM_FORBIDDEN", "채팅방 참여자만 접근할 수 있습니다.");
        }
    }
}
