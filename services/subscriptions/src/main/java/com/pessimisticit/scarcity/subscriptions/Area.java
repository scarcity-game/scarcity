package com.pessimisticit.scarcity.subscriptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.socket.TextMessage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ChangeBatch;
import com.pessimisticit.scarcity.resourcemodel.VersionChecksum;

/**
 * One area of {@link AreaRegistry}: its sockets, version map, log head and who is up. Its own monitor guards all
 * of it (the locking is described on {@link AreaRegistry}).
 */
final class Area {
    final String id;
    final Set<Client> clients;
    final Map<String, Long> versions;
    /** Highest ChangeBatch.logSeq seen for the area; null until one is seen. Guarded by the area monitor. */
    Long logSeq;
    /** The area's {@code activeEntity} (null: nobody, or not known yet), and the area version it is from. */
    JsonNode activeEntity;
    long upVersion;
    final Object loadLock;
    boolean loaded;
    /** Subscribers that are loading and will join; keeps the area alive. */
    int pending;
    boolean retired;
    /** Whether this area holds a {@link AreaListens#listen} of its channel. Guarded by the area monitor. */
    boolean listened;

    Area(String id) {
        this.clients = new HashSet<>();
        this.versions = new HashMap<>();
        this.upVersion = -1;
        this.loadLock = new Object();
        this.id = id;
    }

    /**
     * Caller holds this monitor. Merges the batch's versions, logSeq and who is the active entity; true if the
     * area must be read again (a stub of it: its {@code activeEntity} is unknown).
     */
    boolean merge(ChangeBatch batch) {
        boolean stale = false;
        for (ChangeBatch.Change ch : batch.changes()) {
            if (ch.kind() == ChangeBatch.Change.Kind.DELETED) {
                versions.remove(ch.id());
                continue;
            }
            versions.merge(ch.id(), ch.version(), Math::max);
            if (id.equals(ch.id())) {
                stale |= mergeArea(ch);
            }
        }
        mergeLogSeq(batch.logSeq());
        return stale;
    }

    /** Caller holds this monitor. Keeps the area update's {@code activeEntity}; true if it is a stub. */
    private boolean mergeArea(ChangeBatch.Change ch) {
        if (ch.kind() == ChangeBatch.Change.Kind.STUB) {
            return true;
        }
        if (ch.set().has("activeEntity")) {
            setActiveEntity(ch.set().get("activeEntity"), ch.version());
        } else if (ch.unset().contains("activeEntity")) {
            setActiveEntity(null, ch.version());
        }
        return false;
    }

    /**
     * Caller holds this monitor. Whether this service missed a batch of the area: the batch starts past the
     * log head it has seen (the logSeq chain; a later part of a split batch starts at the same place).
     */
    boolean missed(ChangeBatch batch) {
        return loaded && logSeq != null && batch.fromLogSeq() != null && batch.fromLogSeq() > logSeq;
    }

    /** Caller holds this monitor. */
    void mergeLogSeq(Long seq) {
        if (seq != null && (logSeq == null || seq > logSeq)) {
            logSeq = seq;
        }
    }

    /**
     * Caller holds this monitor. Keeps {@code next} (null or not an object: nobody is active) if
     * its area version is no older than what is kept.
     */
    boolean setActiveEntity(JsonNode next, long version) {
        if (version < upVersion) {
            return false;
        }
        activeEntity = next != null && next.isObject() ? next.deepCopy() : null;
        upVersion = version;
        return true;
    }

    /** Caller holds this monitor. The {@code checksum} frame of the version map, with the log head if known. */
    TextMessage checksum(ObjectMapper json) {
        ObjectNode frame = json.createObjectNode();
        frame.put("type", "checksum");
        frame.put("area", id);
        frame.put("checksum", VersionChecksum.hex(VersionChecksum.of(versions)));
        if (logSeq != null) {
            frame.put("logSeq", logSeq.longValue());
        }
        return new TextMessage(frame.toString());
    }

    /** Caller holds this monitor. */
    List<TextMessage> turnFrames(ObjectMapper json, Client c) {
        return TurnFrames.of(json, id, activeEntity, versions, c);
    }
}
