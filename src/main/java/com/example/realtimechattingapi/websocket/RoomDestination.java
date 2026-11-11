package com.example.realtimechattingapi.websocket;

import org.springframework.security.access.AccessDeniedException;
import java.util.regex.Pattern;

public final class RoomDestination {
    private RoomDestination() {}
    private static final Pattern SEND = Pattern.compile("/app/rooms/([1-9][0-9]*)/messages");
    private static final Pattern SUBSCRIBE = Pattern.compile("/topic/rooms/([1-9][0-9]*)");

    public static long roomId(String destination, boolean send) {
        var match = (send ? SEND : SUBSCRIBE).matcher(destination == null ? "" : destination);
        if (!match.matches()) throw new AccessDeniedException("Destination denied");
        try { return Long.parseLong(match.group(1)); }
        catch (NumberFormatException ex) { throw new AccessDeniedException("Destination denied"); }
    }
}
