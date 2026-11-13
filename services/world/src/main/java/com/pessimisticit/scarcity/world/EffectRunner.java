package com.pessimisticit.scarcity.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pessimisticit.scarcity.resourcemodel.ResourceType;

final class EffectRunner {
    /** Starts an action on an entity at `time`; implemented by Ticker. `cause` is the seq of the effect entry that started it. */
    interface ActionStarter {
        void start(String id, String type, JsonNode params, double time, long cause, long root);
    }

    private sealed interface Work permits Run, Start {}
    private record Run(String type, ObjectNode params, double time, long cause, long root) implements Work {}
    private record Start(String id, String type, JsonNode params, double time, long cause, long root) implements Work {}
    private record Op(String kind, String id, ResourceType type, ObjectNode state, String parent) {}   // kind: "created" | "changed" | "destroyed"
    private record Cancelled(String id, ObjectNode action) {}

    private final TickWorld w;
    private final TickLog tl;
    private final Map<String, EffectType> effects;
    private final RandomGenerator random;
    private final ActionStarter starter;
    private final Deque<Work> stack = new ArrayDeque<>();
    private boolean draining;

    EffectRunner(TickWorld w, TickLog tl, Map<String, EffectType> effects, RandomGenerator random, ActionStarter starter) {
        this.w = w;
        this.tl = tl;
        this.effects = effects;
        this.random = random;
        this.starter = starter;
    }

    static String newId(RandomGenerator g) {
        long msb = (g.nextLong() & ~0xF000L) | 0x4000L;
        long lsb = (g.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return ResourceType.ENTITY.prefix() + new java.util.UUID(msb, lsb);
    }

    void runAll(List<EffectCall> calls, double time, long cause, long root) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            stack.push(new Run(calls.get(i).type(), calls.get(i).params(), time, cause, root));
        }
        drain();
    }

    private void drain() {
        if (draining) return;
        draining = true;
        try {
            while (!stack.isEmpty()) {
                Work item = stack.pop();
                if (item instanceof Run r) {
                    execute(r);
                } else if (item instanceof Start s) {
                    w.count();
                    starter.start(s.id(), s.type(), s.params(), s.time(), s.cause(), s.root());
                }
            }
        } finally {
            draining = false;
            stack.clear();
        }
    }

    private void execute(Run r) {
        EffectType type = effects.get(r.type());
        if (type == null) throw new IllegalArgumentException("unknown effect type " + r.type());
        w.count();
        long seq = tl.nextSeq();
        Ctx ctx = new Ctx(r.time(), seq, r.root());
        type.run(r.params(), ctx);

        ObjectNode effectNode = JsonNodeFactory.instance.objectNode();
        effectNode.put("type", r.type());
        effectNode.set("params", r.params().deepCopy());
        tl.effect(seq, r.cause(), r.root(), r.time(), effectNode, ctx.result, ctx.draws, ctx.changes());

        for (Cancelled c : ctx.cancelled) {
            tl.actionCancelled(seq, r.root(), r.time(), c.id(), c.action());
        }

        // Push follow-ups in reverse order so they run in call order
        for (int i = ctx.followUps.size() - 1; i >= 0; i--) {
            stack.push(ctx.followUps.get(i));
        }
    }

    private final class Ctx implements EffectContext {
        final double time;
        final long seq;
        final long root;
        ObjectNode result = null;
        final ArrayNode draws = JsonNodeFactory.instance.arrayNode();
        final List<Op> ops = new ArrayList<>();
        final List<Cancelled> cancelled = new ArrayList<>();
        final List<Work> followUps = new ArrayList<>();

        Ctx(double time, long seq, long root) {
            this.time = time;
            this.seq = seq;
            this.root = root;
        }

        @Override
        public double time() {
            return time;
        }

        @Override
        public RandomGenerator random() {
            return random;
        }

        @Override
        public String areaId() {
            return w.areaId;
        }

        @Override
        public boolean exists(String id) {
            return id.equals(w.areaId) || w.entities.containsKey(id);
        }

        @Override
        public ObjectNode state(String id) {
            if (id.equals(w.areaId)) {
                w.areaDirty = true;
                markChanged(id, ResourceType.AREA, w.areaState);
                return w.areaState;
            } else {
                ObjectNode s = w.entities.get(id);
                if (s == null) throw new IllegalArgumentException("no such resource: " + id);
                if (!w.created.contains(id)) w.dirty.add(id);
                markChanged(id, ResourceType.ENTITY, s);
                return s;
            }
        }

        @Override
        public String create(String parentId, ObjectNode state) {
            if (!w.areaId.equals(parentId)) throw new IllegalArgumentException("entities can only be created in the area, not in " + parentId);
            String id = newId(random);
            while (w.entities.containsKey(id) || w.destroyed.contains(id)) {
                id = newId(random);
            }
            ObjectNode copy = state.deepCopy();
            w.entities.put(id, copy);
            w.created.add(id);
            w.parents.put(id, parentId);
            ops.add(new Op("created", id, ResourceType.ENTITY, copy, parentId));
            return id;
        }

        @Override
        public void destroy(String id) {
            if (id.equals(w.areaId)) throw new IllegalArgumentException("the area cannot be destroyed");
            ObjectNode s = w.entities.remove(id);
            if (s == null) throw new IllegalArgumentException("no such resource: " + id);
            w.destroyed.add(id);
            w.dirty.remove(id);
            w.blocked.remove(id);
            if (s.path("action").isObject()) {
                cancelled.add(new Cancelled(id, ((ObjectNode) s.get("action")).deepCopy()));
            }
            ops.add(new Op("destroyed", id, ResourceType.ENTITY, null, null));
        }

        @Override
        public void startAction(String id, String type, JsonNode params) {
            followUps.add(new Start(id, type, params, time, seq, root));
        }

        @Override
        public void trigger(String type, ObjectNode params) {
            followUps.add(new Run(type, params, time, seq, root));
        }

        @Override
        public void result(ObjectNode r) {
            this.result = r;
        }

        @Override
        public void draw(ObjectNode d) {
            draws.add(d);
        }

        private void markChanged(String id, ResourceType type, ObjectNode state) {
            // If we already have an Op for this id and it's not "destroyed", don't add another one
            for (Op op : ops) {
                if (op.id.equals(id) && !op.kind.equals("destroyed")) {
                    return;
                }
            }
            ops.add(new Op("changed", id, type, state, null));
        }

        List<LogChange> changes() {
            List<LogChange> result = new ArrayList<>();
            for (Op o : ops) {
                if (o.kind.equals("destroyed")) {
                    result.add(new LogChange(o.id, o.type.wire(), "destroyed", null, null));
                } else {
                    result.add(new LogChange(o.id, o.type.wire(), o.kind, null, o.state.deepCopy(), o.parent));
                }
            }
            return result;
        }
    }
}