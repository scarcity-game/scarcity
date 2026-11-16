package com.pessimisticit.scarcity.orbital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServiceTokenTest {

    private final StubWorld stubWorld;
    private final TestClock testClock;

    ServiceTokenTest() throws IOException {
        this.stubWorld = new StubWorld();
        this.testClock = new TestClock();
    }

    @AfterEach
    void stop() {
        stubWorld.close();
    }

    @Test
    void theTokenIsKeptForTwoThirdsOfItsLifetime() {
        ServiceToken token = Fixtures.token(stubWorld.url(), Fixtures.KEY, testClock);
        assertThat(stubWorld.tokensIssued()).isZero(); // fetched when first needed
        assertThat(token.current()).isEqualTo("token-1");
        testClock.advance(Duration.ofSeconds(599));
        assertThat(token.current()).isEqualTo("token-1");
        testClock.advance(Duration.ofSeconds(1)); // 600 of 900
        assertThat(token.current()).isEqualTo("token-2");
        assertThat(stubWorld.requests).allSatisfy(r -> assertThat(r.header("X-Api-Key")).isEqualTo(Fixtures.KEY));
    }

    @Test
    void renewFetchesANewOneNextTime() {
        ServiceToken token = Fixtures.token(stubWorld.url(), Fixtures.KEY, testClock);
        assertThat(token.current()).isEqualTo("token-1");
        token.renew();
        assertThat(stubWorld.tokensIssued()).isEqualTo(1);
        assertThat(token.current()).isEqualTo("token-2");
    }

    @Test
    void aWrongKeyIsRefusedAndNeverLogged() {
        ServiceToken token = Fixtures.token(stubWorld.url(), "wrong-" + Fixtures.KEY, testClock);
        try (CapturedLogs logs = new CapturedLogs()) {
            assertThatThrownBy(token::current).isInstanceOfSatisfying(ServiceToken.Refused.class,
                    e -> assertThat(e.status()).isEqualTo(401));
            Fixtures.token(stubWorld.url(), Fixtures.KEY, testClock).current();
            assertThat(logs.lines()).noneSatisfy(line -> assertThat(line).contains(Fixtures.KEY))
                    .noneSatisfy(line -> assertThat(line).contains("token-1"));
        }
    }
}
