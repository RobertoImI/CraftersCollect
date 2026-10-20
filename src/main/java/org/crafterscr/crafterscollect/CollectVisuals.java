package org.crafterscr.crafterscollect;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/**
 * Renderizado PRIVADO con paquetes vanilla. Los ItemEntity nunca son añadidos al
 * ServerLevel: existen solo en el cliente destinatario, así que no pueden ser
 * robados, manipulados ni recogidos por el servidor.
 *
 * El cliente vanilla anima el giro y el bobbing natural de los objetos tirados.
 * Los visuales se envían en un radio fijo y se destruyen al salir del área.
 */
public final class CollectVisuals {
    private static final double SHOW_SQ = 48 * 48;
    private static final double HIDE_SQ = 56 * 56;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final String game;
        final int serial; // -1 para preview
        final Map<Integer, Integer> entityByPoint = new HashMap<>();
        final Map<Integer, Long> appearedAt = new HashMap<>();
        Session(String game, int serial) { this.game = game; this.serial = serial; }
    }

    public static void clear(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null || session.entityByPoint.isEmpty()) return;
        int[] ids = session.entityByPoint.values().stream().mapToInt(Integer::intValue).toArray();
        player.connection.send(new ClientboundRemoveEntitiesPacket(ids));
    }

    public static void forget(UUID uuid) { SESSIONS.remove(uuid); }
    public static void resetAll() { SESSIONS.clear(); }

    public static void refreshPreview(ServerPlayer player, CollectData data) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.serial == -1) {
            CollectData.Game game = data.games.get(session.game);
            if (game == null) clear(player);
            else refresh(player, game, null);
        }
    }

    public static boolean hasPreview(UUID uuid) {
        Session s = SESSIONS.get(uuid);
        return s != null && s.serial == -1;
    }

    public static void preview(ServerPlayer player, CollectData.Game game) {
        clear(player);
        refresh(player, game, null);
    }

    /** Actualiza selectivamente el cliente; se invoca cada cinco ticks. */
    public static void refresh(ServerPlayer player, CollectData.Game game, CollectData.Attempt attempt) {
        int serial = attempt == null ? -1 : attempt.serial;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.game.equals(game.id) || session.serial != serial) {
            clear(player);
            session = new Session(game.id, serial);
            SESSIONS.put(player.getUUID(), session);
        }
        String dimension = player.level().dimension().location().toString();
        Vec3 position = player.position();
        for (CollectData.Point point : game.points) {
            double dist = position.distanceToSqr(point.x(), point.y(), point.z());
            boolean eligible = dimension.equals(point.dimension()) &&
                    (attempt == null || !attempt.collected.contains(point.id()));
            Integer entityId = session.entityByPoint.get(point.id());
            // Items del cliente envejecen; renovamos antes de 5 minutos para
            // soportar juegos con duraciones más largas.
            boolean old = entityId != null && player.level().getGameTime()
                    - session.appearedAt.getOrDefault(point.id(), 0L) > 4600;
            if (entityId != null && (!eligible || dist > HIDE_SQ || old)) {
                player.connection.send(new ClientboundRemoveEntitiesPacket(entityId));
                session.entityByPoint.remove(point.id());
                session.appearedAt.remove(point.id());
                entityId = null;
            }
            if (entityId == null && eligible && dist < SHOW_SQ && !game.appearances.isEmpty()) {
                // Apariencia determinista durante cada intento; nunca cambia al alejarse.
                long seed = (attempt == null ? 0 : attempt.seed);
                int selected = Math.floorMod(mix(seed ^ ((long) point.id() * 0x9E3779B97F4A7C15L)),
                        game.appearances.size());
                ItemStack stack = game.appearances.get(selected).copyWithCount(1);
                // ItemEntity flotante y animado exclusivamente en el cliente.
                ItemEntity fake = new ItemEntity(player.serverLevel(),
                        point.x(), point.y() + 0.35, point.z(), stack);
                fake.setNoGravity(true);
                fake.setDeltaMovement(Vec3.ZERO);
                player.connection.send(new ClientboundAddEntityPacket(fake, 0,
                        BlockPos.containing(fake.position())));
                var values = fake.getEntityData().getNonDefaultValues();
                if (values != null && !values.isEmpty())
                    player.connection.send(new ClientboundSetEntityDataPacket(fake.getId(), values));
                session.entityByPoint.put(point.id(), fake.getId());
                session.appearedAt.put(point.id(), player.level().getGameTime());
            }
        }
    }

    private static long mix(long value) {
        value ^= value >>> 30; value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27; value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
