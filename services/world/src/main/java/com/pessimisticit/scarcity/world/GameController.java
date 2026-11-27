package com.pessimisticit.scarcity.world;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pessimisticit.scarcity.world.model.Controller;
import com.pessimisticit.scarcity.world.model.Entity;
import com.pessimisticit.scarcity.world.model.Prototypes;
import com.pessimisticit.scarcity.world.model.WorldJson;
import com.pessimisticit.scarcity.world.model.WorldJsonException;
import com.pessimisticit.scarcity.world.seed.ArenaGame;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/world/games} and {@code GET /api/world/games/profiles} (contract: docs/resources.md,
 * "New games", #455).
 */
@RestController
public class GameController {

    private static final String PREFIX = "/api/world/";

    private final Games games;

    GameController(Games games) {
        this.games = games;
    }

    /** The body of a {@code POST}: a profile's name and its options. */
    record GameRequest(@JsonProperty(required = true) String profile, @JsonProperty(required = true) JsonNode options) {
    }

    /** A mob an arena may use, as the form shows it. */
    record Mob(String prototype, String name, String faction) {
    }

    /** The {@code arena} profile's options: which mobs, and at most how many dens of each. */
    record ArenaOptions(List<Mob> mobs, int maxDensPerMob) {
    }

    record Profile(String name, ArenaOptions options) {
    }

    record Profiles(List<Profile> profiles) {
    }

    @GetMapping(path = PREFIX + "games/profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    public Profiles profiles() {
        List<Mob> mobs = ArenaGame.MOBS.stream().map(p -> {
            Entity prototype = Prototypes.get(p);
            return new Mob(p, prototype.name(), prototype.faction());
        }).toList();
        return new Profiles(List.of(new Profile(ArenaGame.PROFILE, new ArenaOptions(mobs, ArenaGame.MAX_DENS_PER_MOB))));
    }

    @PostMapping(path = PREFIX + "games", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody JsonNode body, Authentication auth) {
        GameRequest request = WorldJson.fromJson(body, GameRequest.class, "game");
        if (!(Callers.of(auth) instanceof Controller.User user)) {
            return error(HttpStatus.FORBIDDEN, "only a user may make a game");
        }
        if (!ArenaGame.PROFILE.equals(request.profile())) {
            throw new WorldJsonException("game at 'profile': unknown profile '" + request.profile() + "', known: "
                    + List.of(ArenaGame.PROFILE), "profile", null);
        }
        ArenaGame.Options options = WorldJson.fromJson(request.options(), ArenaGame.Options.class, "options");
        Games.Made made = games.arena(options, user.sub());
        return ResponseEntity.created(URI.create(PREFIX + "resources/" + made.game()))
                .contentType(MediaType.APPLICATION_JSON).body(made);
    }

    @ExceptionHandler(WorldJsonException.class)
    ResponseEntity<Map<String, String>> invalid(WorldJsonException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "malformed body");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
