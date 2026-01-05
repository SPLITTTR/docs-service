package com.splitttr.collab.message;

public record EditOperation(
    String userId,
    String type,        // "insert", "delete", "replace"
    int position,
    String content,
    int deleteCount,
    long clientVersion
) {}