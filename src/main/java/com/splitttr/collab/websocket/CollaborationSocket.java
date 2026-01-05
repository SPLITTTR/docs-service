package com.splitttr.collab.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.splitttr.collab.message.*;
import com.splitttr.collab.session.DocumentSession;
import com.splitttr.collab.session.SessionManager;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;

@WebSocket(path = "/ws/docs")
public class CollaborationSocket {

    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Inject
    SessionManager sessionManager;

    // Connection state
    private String userId;
    private String documentId;

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        // Wait for join message
    }

    @OnTextMessage
    public void onMessage(String messageJson, WebSocketConnection connection) {
        try {
            ClientMessage msg = mapper.readValue(messageJson, ClientMessage.class);

            switch (msg.type()) {
                case "join" -> handleJoin(msg, connection);
                case "edit" -> handleEdit(msg);
                case "cursor" -> handleCursor(msg);
                case "leave" -> handleLeave();
            }
        } catch (Exception e) {
            sendError(connection, e.getMessage());
        }
    }

    private void handleJoin(ClientMessage msg, WebSocketConnection connection) {
        userId = msg.userId();
        documentId = msg.documentId();

        DocumentSession session = sessionManager.getOrCreateSession(documentId);
        session.addUser(userId, connection);

        // Send initial state to joining user
        var initMsg = ServerMessage.init(
            documentId,
            session.getContent(),
            session.getVersion(),
            session.getActiveUsers()
        );
        session.sendTo(userId, initMsg);

        // Notify others
        session.broadcast(
            ServerMessage.userJoined(documentId, userId, session.getActiveUsers()),
            userId
        );
    }

    private void handleEdit(ClientMessage msg) {
        DocumentSession session = sessionManager.getSession(documentId);
        if (session == null) return;

        EditOperation edit = msg.edit();

        // Apply to in-memory state
        session.applyEdit(edit.type(), edit.position(), edit.content(), edit.deleteCount());

        // Broadcast to others
        session.broadcast(ServerMessage.edit(documentId, edit), userId);
    }

    private void handleCursor(ClientMessage msg) {
        DocumentSession session = sessionManager.getSession(documentId);
        if (session == null) return;

        session.updateCursor(userId, msg.cursorPosition());
        session.broadcast(
            ServerMessage.cursor(documentId, userId, msg.cursorPosition()),
            userId
        );
    }

    private void handleLeave() {
        if (documentId == null) return;

        DocumentSession session = sessionManager.getSession(documentId);
        if (session != null) {
            session.removeUser(userId);
            session.broadcast(ServerMessage.userLeft(documentId, userId), null);
            sessionManager.removeSessionIfEmpty(documentId);
        }
    }

    @OnClose
    public void onClose() {
        handleLeave();
    }

    @OnError
    public void onError(Throwable t) {
        handleLeave();
    }

    private void sendError(WebSocketConnection conn, String message) {
        try {
            conn.sendText(mapper.writeValueAsString(ServerMessage.error(message)));
        } catch (Exception ignored) {}
    }
}