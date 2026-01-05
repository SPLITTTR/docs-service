package com.splitttr.collab.session;

import com.splitttr.collab.client.DocumentClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SessionManager {

    private final ConcurrentHashMap<String, DocumentSession> sessions = new ConcurrentHashMap<>();

    @Inject
    @RestClient
    DocumentClient documentClient;

    public DocumentSession getOrCreateSession(String documentId) {
        return sessions.computeIfAbsent(documentId, id -> {
            var session = new DocumentSession(id);

            // Load initial content from document-service
            try {
                var doc = documentClient.getById(id);
                session.initContent(doc.content(), doc.version());
            } catch (Exception e) {
                session.initContent("", 0);
            }

            return session;
        });
    }

    public DocumentSession getSession(String documentId) {
        return sessions.get(documentId);
    }

    public void removeSessionIfEmpty(String documentId) {
        sessions.computeIfPresent(documentId, (id, session) -> {
            if (session.isEmpty()) {
                // Persist final state before removing
                persistSession(session, documentId);
                return null; // removes from map
            }
            return session;
        });
    }

    public void persistSession(DocumentSession session, String documentId) {
        try {
            documentClient.update(documentId,
                new com.splitttr.collab.client.DocumentUpdateRequest(null, session.getContent()));
        } catch (Exception e) {
            // Log error, maybe retry later
            System.err.println("Failed to persist document: " + e.getMessage());
        }
    }
}