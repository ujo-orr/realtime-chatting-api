package com.example.realtimechattingapi.global;

import java.util.List;

public record CursorPage<T>(List<T> items, Long nextCursor, boolean hasMore) {}
