package org.crafterscr.crafterscollect;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/**
 * Estado persistente del minijuego. Todo se guarda en el Overworld, pero las
 * posiciones pueden pertenecer a cualquier dimensión. Toda modificación debe
 * llamar a setDirty(). Los datos se indexan por UUID, nunca por nombre visible.
 */
public final class CollectData extends SavedData {
    public static final SavedData.Factory<CollectData> FACTORY =
            new SavedData.Factory<>(CollectData::new, CollectData::load, null);

    public final Map<String, Game> games = new LinkedHashMap<>();
    public final Map<String, Map<UUID, Progress>> players = new LinkedHashMap<>();

    public static CollectData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "crafterscollect");
    }

    public Progress progress(String game, UUID uuid) {
        return players.computeIfAbsent(game, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(uuid, ignored -> new Progress());
    }

    public Progress existing(String game, UUID uuid) {
        return players.getOrDefault(game, Collections.emptyMap()).get(uuid);
    }

    /** Punto permanente del mundo, no es una entidad real. */
    public record Point(int id, String dimension, double x, double y, double z) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("id", id); t.putString("dim", dimension);
            t.putDouble("x", x); t.putDouble("y", y); t.putDouble("z", z);
            return t;
        }
        static Point load(CompoundTag t) {
            return new Point(t.getInt("id"), t.getString("dim"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
        }
    }

    /** Un rango tiene varios ítems y/o comandos, y no puede solaparse con otros. */
    public static final class RewardTier {
        public int min, max;
        public final List<ItemStack> items = new ArrayList<>();
        public final List<String> commands = new ArrayList<>();
        RewardTier(int min, int max) { this.min = min; this.max = max; }

        CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag t = new CompoundTag();
            t.putInt("min", min); t.putInt("max", max);
            ListTag stacks = new ListTag();
            for (ItemStack item : items) stacks.add(item.save(registries));
            t.put("items", stacks);
            ListTag commandsTag = new ListTag();
            for (String command : commands) {
                CompoundTag ct = new CompoundTag(); ct.putString("text", command); commandsTag.add(ct);
            }
            t.put("commands", commandsTag);
            return t;
        }
        static RewardTier load(CompoundTag t, HolderLookup.Provider registries) {
            RewardTier tier = new RewardTier(t.getInt("min"), t.getInt("max"));
            for (Tag tag : t.getList("items", Tag.TAG_COMPOUND))
                tier.items.add(ItemStack.parseOptional(registries, (CompoundTag) tag));
            for (Tag tag : t.getList("commands", Tag.TAG_COMPOUND))
                tier.commands.add(((CompoundTag)tag).getString("text"));
            return tier;
        }
    }

    public static final class Game {
        public String id;
        public int durationSeconds = 300;
        public int attempts = 3;
        public double radius = 1.30;
        public int nextPointId = 1;
        public final List<Point> points = new ArrayList<>();
        public final List<ItemStack> appearances = new ArrayList<>();
        public final List<RewardTier> rewards = new ArrayList<>();
        Game(String id) { this.id = id; }

        public RewardTier tier(int score) {
            for (RewardTier t : rewards) if (score >= t.min && score <= t.max) return t;
            return null;
        }
        public RewardTier tierExact(int min, int max) {
            for (RewardTier t : rewards) if (t.min == min && t.max == max) return t;
            return null;
        }
        /** Retorna null si hay un rango conflictivo. */
        public RewardTier defineTier(int min, int max) {
            RewardTier old = tierExact(min, max);
            if (old != null) return old;
            for (RewardTier t : rewards) if (min <= t.max && t.min <= max) return null;
            RewardTier result = new RewardTier(min, max);
            rewards.add(result);
            rewards.sort(Comparator.comparingInt(t -> t.min));
            return result;
        }
        CompoundTag save(HolderLookup.Provider registries) {
            CompoundTag t = new CompoundTag();
            t.putString("id", id); t.putInt("duration", durationSeconds);
            t.putInt("attempts", attempts); t.putDouble("radius", radius);
            t.putInt("nextPoint", nextPointId);
            ListTag p = new ListTag(); for (Point point : points) p.add(point.save()); t.put("points", p);
            ListTag a = new ListTag(); for (ItemStack stack : appearances) a.add(stack.save(registries)); t.put("appearances", a);
            ListTag r = new ListTag(); for (RewardTier reward : rewards) r.add(reward.save(registries)); t.put("rewards", r);
            return t;
        }
        static Game load(CompoundTag t, HolderLookup.Provider registries) {
            Game g = new Game(t.getString("id"));
            g.durationSeconds = Math.max(1, t.getInt("duration"));
            g.attempts = Math.max(1, t.getInt("attempts"));
            g.radius = Math.max(0.25, t.getDouble("radius"));
            g.nextPointId = Math.max(1, t.getInt("nextPoint"));
            for (Tag p : t.getList("points", Tag.TAG_COMPOUND)) g.points.add(Point.load((CompoundTag) p));
            for (Tag a : t.getList("appearances", Tag.TAG_COMPOUND)) {
                ItemStack stack = ItemStack.parseOptional(registries, (CompoundTag) a);
                if (!stack.isEmpty()) g.appearances.add(stack);
            }
            for (Tag r : t.getList("rewards", Tag.TAG_COMPOUND)) g.rewards.add(RewardTier.load((CompoundTag)r, registries));
            return g;
        }
    }

    public enum State { ACTIVE, FINISHED, CLAIMED, REVIEW }

    /** Cada intento usa un ID que nunca se recicla (incluso al reiniciar intentos). */
    public static final class Attempt {
        public int serial;
        public State state = State.ACTIVE;
        public long startedAtMillis;
        public int durationSeconds;
        public long seed;
        public int score;
        public final Set<Integer> collected = new HashSet<>();
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("serial", serial); t.putString("state", state.name());
            t.putLong("started", startedAtMillis); t.putInt("duration", durationSeconds);
            t.putLong("seed", seed); t.putInt("score", score);
            t.putIntArray("collected", collected.stream().mapToInt(Integer::intValue).toArray());
            return t;
        }
        static Attempt load(CompoundTag t) {
            Attempt a = new Attempt();
            a.serial = t.getInt("serial");
            try { a.state = State.valueOf(t.getString("state")); } catch (Exception ignored) { a.state = State.REVIEW; }
            a.startedAtMillis = t.getLong("started"); a.durationSeconds = t.getInt("duration");
            a.seed = t.getLong("seed"); a.score = t.getInt("score");
            for (int id : t.getIntArray("collected")) a.collected.add(id);
            return a;
        }
        public long secondsLeft(long now) {
            long millis = (startedAtMillis + durationSeconds * 1000L) - now;
            return Math.max(0, (millis + 999) / 1000);
        }
    }

    public static final class Progress {
        public int used = 0;
        public int bonus = 0;
        public int nextSerial = 1;
        public int claimedCount = 0;
        public Attempt last;
        // Intentos reclamados anteriores: no se pierden al empezar otra partida.
        public final List<Attempt> history = new ArrayList<>();
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("used", used); t.putInt("bonus", bonus);
            t.putInt("nextSerial", nextSerial); t.putInt("claimedCount", claimedCount);
            if (last != null) t.put("last", last.save());
            ListTag old = new ListTag(); for (Attempt a : history) old.add(a.save());
            t.put("history", old);
            return t;
        }
        static Progress load(CompoundTag t) {
            Progress p = new Progress();
            p.used = t.getInt("used"); p.bonus = t.getInt("bonus");
            p.nextSerial = Math.max(1, t.getInt("nextSerial"));
            p.claimedCount = t.getInt("claimedCount");
            if (t.contains("last", Tag.TAG_COMPOUND)) p.last = Attempt.load(t.getCompound("last"));
            for (Tag h : t.getList("history", Tag.TAG_COMPOUND)) p.history.add(Attempt.load((CompoundTag)h));
            return p;
        }
    }

    @Override public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("schema", 1);
        ListTag g = new ListTag(); for (Game game : games.values()) g.add(game.save(registries)); root.put("games", g);
        ListTag p = new ListTag();
        for (var group : players.entrySet()) for (var pair : group.getValue().entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("game", group.getKey()); entry.putUUID("uuid", pair.getKey());
            entry.put("progress", pair.getValue().save()); p.add(entry);
        }
        root.put("players", p);
        return root;
    }

    public static CollectData load(CompoundTag root, HolderLookup.Provider registries) {
        CollectData data = new CollectData();
        for (Tag tag : root.getList("games", Tag.TAG_COMPOUND)) {
            Game g = Game.load((CompoundTag)tag, registries);
            data.games.put(g.id, g);
        }
        for (Tag tag : root.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) tag;
            if (!entry.hasUUID("uuid")) continue;
            data.players.computeIfAbsent(entry.getString("game"), ignored -> new HashMap<>())
                    .put(entry.getUUID("uuid"), Progress.load(entry.getCompound("progress")));
        }
        return data;
    }
}
