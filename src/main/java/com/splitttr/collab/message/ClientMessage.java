package com.splitttr.collab.message;

public record ClientMessage(
    String type,            // "join", "edit", "cursor", "leave"
    String documentId,
    String userId,
    EditOperation edit,
    Integer cursorPosition
) {}