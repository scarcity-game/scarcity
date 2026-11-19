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

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder encoder;
    /** Hash of a random password, compared against when the user doesn't exist (timing). */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, TokenService tokenService, AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.encoder = new BCryptPasswordEncoder(authProperties.password().bcryptStrength());
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public User register(String username, String password) {
        // Lengths are checked on the normalised form: NFKC can change the code point and byte counts.
        String normalized = password == null ? null : PasswordNormalizer.normalize(password);
        List<FieldError> errors = CredentialsPolicy.checkRegistration(username, normalized);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(errors);
        }
        // Cheap pre-check to skip bcrypt work; the unique index is the real guard (races).
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            AuditLog.registerFailedUsernameTaken();
            throw new UsernameTakenException();
        }
        String hash = encoder.encode(normalized);
        User created;
        try {
            created = userRepository.insert(UUID.randomUUID(), username, hash);
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

        Optional<User> user = userRepository.findByUsernameIgnoreCase(username);
        if (user.isEmpty()) {
            matchesHash(PasswordNormalizer.normalize(password), dummyHash);
            AuditLog.loginFailed();
            throw new BadCredentialsException();
        }
        if (!passwordMatches(user.get(), password)) {
            AuditLog.loginFailed();
            throw new BadCredentialsException();
        }
        IssuedToken token = tokenService.mint(user.get().id(), user.get().username());
        AuditLog.loggedIn(user.get().id());
        return token;
    }

    /**
     * Always runs at least one bcrypt comparison so unknown users and wrong passwords take
     * about the same time. Passwords over 72 bytes can't have been registered (and Spring's
     * BCrypt rejects them), so a placeholder is compared instead.
     */
    private boolean passwordMatches(User user, String raw) {
        String normalized = PasswordNormalizer.normalize(raw);
        if (matchesHash(normalized, user.passwordHash())) {
            return true;
        }
        // Hashes made before #7 were taken from the raw bytes. Accept the raw form once and
        // upgrade the stored hash to the NFKC form (only possible if that form fits bcrypt).
        if (!raw.equals(normalized) && matchesHash(raw, user.passwordHash())) {
            if (CredentialsPolicy.fitsBcrypt(normalized)) {
                userRepository.updatePasswordHash(user.id(), encoder.encode(normalized));
                AuditLog.passwordHashUpgraded(user.id());
            }
            return true;
        }
        return false;
    }

    private boolean matchesHash(String password, String hash) {
        boolean fits = CredentialsPolicy.fitsBcrypt(password);
        return encoder.matches(fits ? password : "", hash) && fits;
    }

    public User currentUser(String bearerToken) {
        if (bearerToken == null) {
            throw new InvalidTokenException(true);
        }
        return tokenService.verify(bearerToken)
                .flatMap(t -> userRepository.findById(t.userId()))
                .orElseThrow(() -> new InvalidTokenException(false));
    }
}
