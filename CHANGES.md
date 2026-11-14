# Ticket #92: subscriptions: world call timeouts as properties

## Summary of Changes

This ticket implements configurable timeouts for calls to the world service in the subscriptions module. Previously, the connect and read timeouts were hard-coded values (3s connect, 10s read). Now these are configurable through application properties.

### Files Modified:

1. **`services/subscriptions/src/main/java/com/pessimisticit/scarcity/subscriptions/SubscriptionsProperties.java`**:
   - Added `worldConnectTimeout` and `worldReadTimeout` properties with default values of 3s and 10s respectively
   - Added corresponding getters and setters

2. **`services/subscriptions/src/main/java/com/pessimisticit/scarcity/subscriptions/WorldClient.java`**:
   - Modified to use the configurable properties instead of hard-coded values
   - Changed `f.setConnectTimeout(3000)` to `f.setConnectTimeout((int) props.getWorldConnectTimeout().toMillis())`
   - Changed `f.setReadTimeout(10000)` to `f.setReadTimeout((int) props.getWorldReadTimeout().toMillis())`

3. **`services/subscriptions/src/main/resources/application.yml`**:
   - Added the new properties with appropriate comments
   ```
   # timeouts for calls to world
   world-connect-timeout: 3s
   world-read-timeout: 10s
   ```

### Verification:
- All existing functionality preserved (same default values)
- Properties follow established patterns in the codebase
- No breaking changes or external dependencies added
- Implementation compiles successfully and follows project conventions

This change provides operators with the ability to tune these timeouts based on their environment while maintaining backward compatibility.