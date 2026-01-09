package com.splitttr.collab.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.splitttr.collab.message.*;
import com.splitttr.collab.security.AuthService;
import com.splitttr.collab.session.DocumentSession;
import com.splitttr.collab.session.SessionManager;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for real-time collaborative editing.
 * Requires JWT authentication - user identity extracted from token.
 */
@WebSocket(path = "/ws/docs")
public class CollaborationSocket {

    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    @Inject
    SessionManager sessionManager;

    @Inject
    AuthService authService;

    // Store connection state externally since the socket instance may not persist
    private static final Map<String, ConnectionState> connectionStates = new ConcurrentHashMap<>();

    record ConnectionState(String userId, String documentId) {}

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        // Verify authentication at connection time
        if (!authService.isAuthenticated()) {
            System.err.println("Unauthenticated WebSocket connection attempt: " + connection.id());
            connection.closeAndAwait(1008, "Authentication required");
            return;
        }
        
        String userId = authService.getCurrentUserId();
        System.out.println("Authenticated WebSocket opened: " + connection.id() + " (user: " + userId + ")");
    }

    @OnTextMessage
    public void onMessage(String messageJson, WebSocketConnection connection) {
        // Double-check authentication (token could expire mid-session)
        if (!authService.isAuthenticated()) {
            System.err.println("Authentication expired for connection: " + connection.id());
            connection.closeAndAwait(1008, "Authentication expired");
            return;
        }

        try {
            ClientMessage msg = mapper.readValue(messageJson, ClientMessage.class);
            
            // CRITICAL: Use authenticated user ID from JWT, not what client claims
            String authenticatedUserId = authService.getCurrentUserId();
            
            System.out.println("Received message: " + msg.type() + " from authenticated user: " + authenticatedUserId);

            switch (msg.type()) {
                case "join" -> handleJoin(msg, connection, authenticatedUserId);
                case "edit" -> handleEdit(msg, connection, authenticatedUserId);
                case "cursor" -> handleCursor(msg, connection, authenticatedUserId);
                case "leave" -> handleLeave(connection);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendError(connection, e.getMessage());
        }
    }

    private void handleJoin(ClientMessage msg, WebSocketConnection connection, String authenticatedUserId) {
        // Use authenticated user ID from JWT, ignore what client sent
        String userId = authenticatedUserId;
        String docId = msg.documentId();

        if (docId == null || docId.isBlank()) {
            sendError(connection, "Document ID required");
            return;
        }

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

    private void handleEdit(ClientMessage msg, WebSocketConnection connection, String authenticatedUserId) {
        ConnectionState state = connectionStates.get(connection.id());
        if (state == null) {
            sendError(connection, "Not joined to a document");
            return;
        }

        // Verify the user making the edit matches the authenticated user
        if (!state.userId().equals(authenticatedUserId)) {
            System.err.println("User ID mismatch: state=" + state.userId() + ", auth=" + authenticatedUserId);
            sendError(connection, "Authentication mismatch");
            return;
        }

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session == null) {
            sendError(connection, "Document session not found");
            return;
        }

        EditOperation edit = msg.edit();
        if (edit == null) {
            sendError(connection, "Edit operation required");
            return;
        }

        // Ensure the edit operation has the correct authenticated user ID
        EditOperation authenticatedEdit = new EditOperation(
            authenticatedUserId,  // Use authenticated ID
            edit.type(),
            edit.position(),
            edit.content(),
            edit.deleteCount(),
            edit.clientVersion()
        );

        // Apply to in-memory state
        session.applyEdit(
            authenticatedEdit.type(), 
            authenticatedEdit.position(), 
            authenticatedEdit.content(), 
            authenticatedEdit.deleteCount()
        );

        // Broadcast to others (excluding the sender)
        session.broadcast(
            ServerMessage.edit(state.documentId(), authenticatedEdit), 
            state.userId()
        );
    }

    private void handleCursor(ClientMessage msg, WebSocketConnection connection, String authenticatedUserId) {
        ConnectionState state = connectionStates.get(connection.id());
        if (state == null) return;

        if (!state.userId().equals(authenticatedUserId)) {
            return; // Silently ignore cursor updates from mismatched users
        }

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session == null) return;

        Integer cursorPos = msg.cursorPosition();
        if (cursorPos == null) return;

        session.updateCursor(state.userId(), cursorPos);
        session.broadcast(
            ServerMessage.cursor(state.documentId(), state.userId(), cursorPos),
            state.userId()
        );
    }

    private void handleLeave(WebSocketConnection connection) {
        ConnectionState state = connectionStates.remove(connection.id());
        if (state == null) return;

        DocumentSession session = sessionManager.getSession(state.documentId());
        if (session != null) {
            session.removeUser(state.userId());
            session.broadcast(
                ServerMessage.userLeft(state.documentId(), state.userId()), 
                null
            );
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
        System.err.println("WebSocket error on " + connection.id() + ": " + t.getMessage());
        handleLeave(connection);
    }

    private void sendError(WebSocketConnection conn, String message) {
        try {
            conn.sendText(mapper.writeValueAsString(ServerMessage.error(message)));
        } catch (Exception ignored) {}
    }
}
