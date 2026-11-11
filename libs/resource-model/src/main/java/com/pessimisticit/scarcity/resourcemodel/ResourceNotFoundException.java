package com.pessimisticit.scarcity.resourcemodel;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String id) {
        super("resource not found: " + id);
    }
}
