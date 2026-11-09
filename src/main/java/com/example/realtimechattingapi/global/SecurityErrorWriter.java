package com.example.realtimechattingapi.global;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

@Component @RequiredArgsConstructor
public class SecurityErrorWriter {
    private final ObjectMapper mapper;
    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getWriter(), ApiError.of(code, message, request.getRequestURI()));
    }
}
