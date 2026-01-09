package com.splitttr.collab.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class AuthService {

    @Inject
    JsonWebToken jwt;

    /**
     * Get the current user's Clerk ID from JWT.
     * Returns null if not authenticated.
     */
    public String getCurrentUserId() {
        try {
            return jwt.getSubject();
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isAuthenticated() {
        return getCurrentUserId() != null;
    }
}