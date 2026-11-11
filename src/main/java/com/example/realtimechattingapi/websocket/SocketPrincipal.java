package com.example.realtimechattingapi.websocket;

import com.example.realtimechattingapi.auth.JwtTokenProvider.TokenIdentity;
import java.security.Principal;

public record SocketPrincipal(TokenIdentity identity) implements Principal {
    @Override public String getName() { return identity.userId().toString(); }
}
