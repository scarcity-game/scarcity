package com.pessimisticit.scarcity.auth.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;

import com.pessimisticit.scarcity.auth.AuthProperties;
import com.pessimisticit.scarcity.auth.jwt.TokenService;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.UsernameTakenException;

/**
 * The register race (pre-check passes, the unique index then rejects the insert) can't be
 * hit deterministically over HTTP, so it is covered here: it writes the same audit line as
 * the pre-check path. The JSON shape of all audit lines is tested in EcsLoggingIntegrationTest.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthServiceAuditTest {

    private static final String PASSWORD = "Race-Secret-Pa55";

    @Test
    void duplicateKeyRaceIsAuditedLikeThePreCheck(CapturedOutput output) {
        UserRepository users = mock(UserRepository.class);
        when(users.existsByUsernameIgnoreCase(anyString())).thenReturn(false);
        when(users.insert(any(), anyString(), anyString())).thenThrow(new DuplicateKeyException("users_username_key"));
        AuthService auth = new AuthService(users, mock(TokenService.class),
                new AuthProperties(new AuthProperties.Jwt(null, Duration.ofHours(1)), new AuthProperties.Password(10)));

        assertThatThrownBy(() -> auth.register("Racer_1", PASSWORD)).isInstanceOf(UsernameTakenException.class);

        String audit = output.getAll().lines().filter(l -> l.contains("register failed")).findFirst().orElseThrow(
                () -> new AssertionError("no audit line in\n" + output.getAll()));
        assertThat(audit).contains("register failed: username_taken").contains("INFO")
                .doesNotContain("Racer_1").doesNotContain(PASSWORD);
    }
}
