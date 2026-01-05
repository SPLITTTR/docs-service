package com.splitttr.collab.message;

import java.util.List;

public record ServerMessage(
    String type,
    String documentId,
    String content,
    long version,
    EditOperation edit,
    String userId,
    Integer cursorPosition,
    List<ActiveUser> activeUsers,
    String error
) {
    public record ActiveUser(String userId, int cursorPosition) {}

    public static ServerMessage init(String docId, String content, long version, List<ActiveUser> users) {
        return new ServerMessage("init", docId, content, version, null, null, null, users, null);
    }

    public static ServerMessage edit(String docId, EditOperation op) {
        return new ServerMessage("edit", docId, null, 0, op, op.userId(), null, null, null);
    }

    public static ServerMessage cursor(String docId, String userId, int position) {
        return new ServerMessage("cursor", docId, null, 0, null, userId, position, null, null);
    }

    public static ServerMessage userJoined(String docId, String userId, List<ActiveUser> users) {
        return new ServerMessage("user_joined", docId, null, 0, null, userId, null, users, null);
    }

    public static ServerMessage userLeft(String docId, String userId) {
        return new ServerMessage("user_left", docId, null, 0, null, userId, null, null, null);
    }

    public static ServerMessage error(String message) {
        return new ServerMessage("error", null, null, 0, null, null, null, null, message);
    }
}
