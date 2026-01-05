package com.splitttr.collab.client;

import java.time.Instant;

public record DocumentResponse(
    String id,
    String title,
    String content,
    String ownerId,
    Instant createdAt,
    Instant updatedAt,
    long version
) {}