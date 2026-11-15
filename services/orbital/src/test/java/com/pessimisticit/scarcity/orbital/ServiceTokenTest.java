package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServiceTokenTest {

    private final StubWorld auth = new StubWorld();
    private final TestClock clock = new TestClock();

    ServiceTokenTest() throws IOException {
    }

    @AfterEach
    void stop() {
        auth.close();
    }

    @Test
    void theTokenIsKeptForTwoThirdsOfItsLifetime() {
        ServiceToken token = Fixtures.token(auth.url(), Fixtures.KEY, clock);
        assertThat(auth.tokensIssued()).isZero(); // fetched when first needed
        assertThat(token.current()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(599));
        assertThat(token.current()).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(1)); // 600 of 900
        assertThat(token.current()).isEqualTo("token-2");
        assertThat(auth.requests).allSatisfy(r -> assertThat(r.header("X-Api-Key")).isEqualTo(Fixtures.KEY));
    }

    @Test
    void renewFetchesANewOneNextTime() {
        ServiceToken token = Fixtures.token(auth.url(), Fixtures.KEY, clock);
        assertThat(token.current()).isEqualTo("token-1");
        token.renew();
        assertThat(auth.tokensIssued()).isEqualTo(1);
        assertThat(token.current()).isEqualTo("token-2");
    }

    @Test
    void aWrongKeyIsRefusedAndNeverLogged() {
        ServiceToken token = Fixtures.token(auth.url(), "wrong-" + Fixtures.KEY, clock);
        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(token::current).isInstanceOfSatisfying(ServiceToken.Refused.class,
                    e -> assertThat(e.status()).isEqualTo(401));
            Fixtures.token(auth.url(), Fixtures.KEY, clock).current();
            assertThat(logs.lines()).noneSatisfy(line -> assertThat(line).contains(Fixtures.KEY))
                    .noneSatisfy(line -> assertThat(line).contains("token-1"));
        }
    }
}
