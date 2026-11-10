package com.pessimisticit.scarcity.auth.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.pessimisticit.scarcity.auth.AuthProperties;
import com.pessimisticit.scarcity.auth.jwt.TokenService;
import com.pessimisticit.scarcity.auth.jwt.TokenService.IssuedToken;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.BadCredentialsException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.InvalidRequestException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.InvalidTokenException;
import com.pessimisticit.scarcity.auth.user.AuthExceptions.UsernameTakenException;
import com.pessimisticit.scarcity.auth.user.CredentialsPolicy.FieldError;

@Service
public class AuthService {

    private final UserRepository users;
    private final TokenService tokens;
    private final BCryptPasswordEncoder encoder;
    /** Hash of a random password, compared against when the user doesn't exist (timing). */
    private final String dummyHash;

    public AuthService(UserRepository users, TokenService tokens, AuthProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = new BCryptPasswordEncoder(properties.password().bcryptStrength());
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public User register(String username, String password) {
        List<FieldError> errors = CredentialsPolicy.checkRegistration(username, password);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        // Cheap pre-check to skip bcrypt work; the unique index is the real guard (races).
        if (users.existsByUsernameIgnoreCase(username)) {
            AuditLog.registerFailedUsernameTaken();
            throw new UsernameTakenException();
        }
        String hash = encoder.encode(password);
        User created;
        try {
            created = users.insert(UUID.randomUUID(), username, hash);
        } catch (DuplicateKeyException e) {
            AuditLog.registerFailedUsernameTaken();
            throw new UsernameTakenException();
        }
        AuditLog.registered(created.id());
        return created;
    }

    public IssuedToken login(String username, String password) {
        List<FieldError> missing = new ArrayList<>();
        if (username == null) {
            missing.add(new FieldError("username", "is required"));
        }
        if (password == null) {
            missing.add(new FieldError("password", "is required"));
        }
        if (!missing.isEmpty()) {
            throw new InvalidRequestException(missing);
        }

        Optional<User> user = users.findByUsernameIgnoreCase(username);
        // Always run exactly one bcrypt comparison so unknown users and wrong passwords take
        // about the same time. Passwords over 72 bytes can't have been registered (and Spring's
        // BCrypt rejects them), so compare a placeholder instead.
        boolean fits = CredentialsPolicy.fitsBcrypt(password);
        String hash = user.map(User::passwordHash).orElse(dummyHash);
        boolean matches = encoder.matches(fits ? password : "", hash);
        if (user.isEmpty() || !fits || !matches) {
            AuditLog.loginFailed();
            throw new BadCredentialsException();
        }
        IssuedToken token = tokens.mint(user.get().id(), user.get().username());
        AuditLog.loggedIn(user.get().id());
        return token;
    }

    public User currentUser(String bearerToken) {
        if (bearerToken == null) {
            throw new InvalidTokenException(true);
        }
        return tokens.verify(bearerToken)
                .flatMap(t -> users.findById(t.userId()))
                .orElseThrow(() -> new InvalidTokenException(false));
    }
}
