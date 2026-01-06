package com.splitttr.collab.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.splitttr.collab.message.*;
import com.splitttr.collab.session.DocumentSession;
import com.splitttr.collab.session.SessionManager;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/docs")
public class CollaborationSocket {

    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Inject
    SessionManager sessionManager;

    // Store connection state externally since the socket instance may not persist
    private static final Map<String, ConnectionState> connectionStates = new ConcurrentHashMap<>();

    record ConnectionState(String userId, String documentId) {}

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        // Wait for join message
        System.out.println("WebSocket opened: " + connection.id());
    }

    @OnTextMessage
    public void onMessage(String messageJson, WebSocketConnection connection) {
        try {
            ClientMessage msg = mapper.readValue(messageJson, ClientMessage.class);
            System.out.println("Received message: " + msg.type() + " from connection " + connection.id());

            switch (msg.type()) {
                case "join" -> handleJoin(msg, connection);
                case "edit" -> handleEdit(msg, connection);
                case "cursor" -> handleCursor(msg, connection);
                case "leave" -> handleLeave(connection);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(connection, e.getMessage());
        }
    }

    private void handleJoin(ClientMessage msg, WebSocketConnection connection) {
        String userId = msg.userId();
        String docId = msg.documentId();

        // Store state for this connection
        connectionStates.put(connection.id(), new ConnectionState(userId, docId));

        DocumentSession session = sessionManager.getOrCreateSession(docId);
        session.addUser(userId, connection);

        System.out.println("User " + userId + " joined document " + docId);
        System.out.println("Active users: " + session.getActiveUsers());

        // Send initial state to joining user
        var initMsg = ServerMessage.init(
            docId,
            session.getContent(),
            session.getVersion(),
            session.getActiveUsers()
        );
        session.sendTo(userId, initMsg);

        // Notify others
        session.broadcast(
            ServerMessage.userJoined(docId, userId, session.getActiveUsers()),
            userId
        );
    }

    private void handleEdit(ClientMessage msg, WebSocketConnection connection) {
        ConnectionState state = connectionStates.get(connection.id());
        if (state == null) {
            sendError(connection, "Not joined to a document");
            return;
        }

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session == null) return;

        EditOperation edit = msg.edit();

        // Apply to in-memory state
        session.applyEdit(edit.type(), edit.position(), edit.content(), edit.deleteCount());

        // Broadcast to others
        session.broadcast(ServerMessage.edit(state.documentId(), edit), state.userId());
    }

    private void handleCursor(ClientMessage msg, WebSocketConnection connection) {
        ConnectionState state = connectionStates.get(connection.id());
        if (state == null) return;

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session == null) return;

        session.updateCursor(state.userId(), msg.cursorPosition());
        session.broadcast(
            ServerMessage.cursor(state.documentId(), state.userId(), msg.cursorPosition()),
            state.userId()
        );
    }

    private void handleLeave(WebSocketConnection connection) {
        ConnectionState state = connectionStates.remove(connection.id());
        if (state == null) return;

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session != null) {
            session.removeUser(state.userId());
            session.broadcast(ServerMessage.userLeft(state.documentId(), state.userId()), null);
            sessionManager.removeSessionIfEmpty(state.documentId());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        System.out.println("WebSocket closed: " + connection.id());
        handleLeave(connection);
    }

    @OnError
    public void onError(WebSocketConnection connection, Throwable t) {
        System.err.println("WebSocket error: " + t.getMessage());
        handleLeave(connection);
    }

    private void sendError(WebSocketConnection conn, String message) {
        try {
            conn.sendText(mapper.writeValueAsString(ServerMessage.error(message)));
        } catch (Exception ignored) {}
    }
}