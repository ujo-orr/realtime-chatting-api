package com.example.realtimechattingapi.chatroom;

import com.example.realtimechattingapi.auth.JwtTokenProvider.TokenIdentity;
import com.example.realtimechattingapi.global.CursorPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class ChatRoomController {
    private final ChatRoomService service;

    @PostMapping
    public ResponseEntity<ChatRoomService.RoomView> create(
            @AuthenticationPrincipal TokenIdentity identity,
            @Valid @RequestBody ChatRoomService.CreateRoom request) {
        var room = service.create(identity.userId(), request);
        return ResponseEntity.created(URI.create("/api/rooms/" + room.id() + "/messages")).body(room);
    }

    @GetMapping
    public CursorPage<ChatRoomService.RoomView> list(
            @AuthenticationPrincipal TokenIdentity identity,
            @RequestParam(required = false) @Positive Long beforeId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.list(identity.userId(), beforeId, limit);
    }
}
