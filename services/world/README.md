# World Service

This service owns games, areas and entities and serves them through the generic resource API.

## Key Features

- Implements the game tick loop for managing entities and their behaviors
- Handles action start/end events with proper timing and effects
- Manages entity behavior through repeat and random patterns
- Provides area logging functionality

## Behavior

Entities with behaviors that cannot start (unknown action type, non-finite duration, etc.) will now log warnings only once per unique blocking reason, rather than on every tick. This prevents excessive logging while maintaining visibility of problematic behaviors.