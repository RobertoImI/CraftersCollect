package org.crafterscr.crafterscollect;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Reglas autoritativas: partidas, reloj, recolección y premios. */
public final class CollectService {
    private static long ticks;

    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % 5 != 0) return;
        MinecraftServer server = event.getServer();
        CollectData data = CollectData.get(server);
        long now = System.currentTimeMillis();

        // Expirar TODOS los intentos, incluso los de personas desconectadas.
        for (var group : data.players.entrySet()) {
            CollectData.Game game = data.games.get(group.getKey());
            if (game == null) continue;
            for (var entry : group.getValue().entrySet()) {
                CollectData.Attempt attempt = entry.getValue().last;
                if (attempt == null || attempt.state != CollectData.State.ACTIVE) continue;
                if (attempt.secondsLeft(now) == 0) {
                    attempt.state = CollectData.State.FINISHED;
                    data.setDirty();
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        CollectVisuals.clear(player);
                        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
                        notifyPlayer(player, "§aTiempo terminado. §eRecogiste " + attempt.score + "/" + game.points.size()
                                + ". §fRegresa al NPC para reclamar tu premio.");
                    }
                }
            }
        }

        // Cada usuario posee a lo sumo una partida activa (sin superponer HUD).
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Active active = activeFor(data, player.getUUID());
            if (active == null) {
                // Preview opcional sólo para administradores, sin partida activa.
                CollectVisuals.refreshPreview(player, data);
                continue;
            }
            CollectData.Game game = active.game;
            CollectData.Attempt attempt = active.attempt;
            long seconds = attempt.secondsLeft(now);
            if (seconds == 0) continue;

            CollectVisuals.refresh(player, game, attempt);
            String dimension = player.level().dimension().location().toString();
            Vec3 pos = player.position();
            double r2 = game.radius * game.radius;
            boolean picked = false;
            // La colisión es calculada solo en el servidor; no se aceptan paquetes de cliente.
            for (CollectData.Point point : game.points) {
                if (!point.dimension().equals(dimension) || attempt.collected.contains(point.id())) continue;
                double dx = pos.x - point.x(), dz = pos.z - point.z();
                double dy = Math.abs(pos.y - point.y());
                if (dx * dx + dz * dz <= r2 && dy <= 1.8) {
                    attempt.collected.add(point.id()); attempt.score++;
                    data.setDirty(); picked = true;
                    // En la siguiente actualización se destruirá su entidad visual.
                    CollectVisuals.refresh(player, game, attempt);
                    player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                            SoundSource.PLAYERS, 0.65f, 1.15f);
                    ((ServerLevel) player.level()).sendParticles(player, ParticleTypes.HAPPY_VILLAGER,
                            true, point.x(), point.y() + 0.5, point.z(), 8,
                            0.25, 0.25, 0.25, 0.04);
                }
            }
            String time = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
            player.displayClientMessage(Component.literal((picked ? "§a✦ +1  " : "§6✦  ")
                    + "§e" + attempt.score + "§7/§e" + game.points.size()
                    + "   §8│   §b⏱ " + time), true);
        }
    }

    public record Active(CollectData.Game game, CollectData.Attempt attempt) {}

    public static Active activeFor(CollectData data, UUID uuid) {
        for (var entry : data.games.entrySet()) {
            CollectData.Progress progress = data.existing(entry.getKey(), uuid);
            if (progress != null && progress.last != null && progress.last.state == CollectData.State.ACTIVE)
                return new Active(entry.getValue(), progress.last);
        }
        return null;
    }

    public static String start(MinecraftServer server, ServerPlayer player, String gameName) {
        CollectData data = CollectData.get(server);
        CollectData.Game game = data.games.get(gameName);
        if (game == null) return "§cEse minijuego no existe.";
        if (game.points.isEmpty() || game.appearances.isEmpty())
            return "§cEl minijuego necesita puntos y al menos una apariencia.";
        if (activeFor(data, player.getUUID()) != null) return "§cYa tienes una partida en marcha.";
        CollectData.Progress progress = data.progress(gameName, player.getUUID());
        if (progress.last != null && progress.last.state == CollectData.State.FINISHED)
            return "§cPrimero reclama el premio pendiente de tu último intento.";
        if (progress.last != null && progress.last.state == CollectData.State.REVIEW)
            return "§cTu última entrega necesita revisión del administrador.";
        if (progress.used >= game.attempts + progress.bonus)
            return "§cNo te quedan intentos disponibles.";
        CollectData.Attempt attempt = new CollectData.Attempt();
        attempt.serial = progress.nextSerial++;
        attempt.startedAtMillis = System.currentTimeMillis();
        attempt.durationSeconds = game.durationSeconds;
        attempt.seed = new java.security.SecureRandom().nextLong();
        // Guarda en historial la partida ya cobrada antes de reemplazar el actual.
        if (progress.last != null && progress.last.state == CollectData.State.CLAIMED)
            progress.history.add(progress.last);
        progress.last = attempt;
        progress.used++;
        data.setDirty();
        CollectVisuals.clear(player);
        CollectVisuals.refresh(player, game, attempt);
        player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 1, 1.4f);
        return "§a¡Comenzó " + gameName + "! §fTienes " + game.durationSeconds + " segundos."
                + " §7(Intento " + progress.used + "/" + (game.attempts + progress.bonus) + ")";
    }

    /**
     * Entrega como máximo UNA vez por intento. Se marca REVIEW y se intenta
     * guardar ANTES de entregar nada. Sólo pasa a CLAIMED si acaba la rutina.
     * Un fallo deja el intento en REVIEW para intervención manual; no es
     * una transacción atómica, especialmente con comandos de otros mods.
     */
    public static String claim(MinecraftServer server, ServerPlayer player, String gameName) {
        CollectData data = CollectData.get(server);
        CollectData.Game game = data.games.get(gameName);
        if (game == null) return "§cEse minijuego no existe.";
        CollectData.Progress progress = data.existing(gameName, player.getUUID());
        if (progress == null || progress.last == null) return "§cTodavía no has jugado.";
        CollectData.Attempt attempt = progress.last;
        if (attempt.state == CollectData.State.ACTIVE) return "§cTu tiempo no ha terminado; no puedes reclamar aún.";
        if (attempt.state == CollectData.State.CLAIMED) return "§cYa reclamaste el premio de este intento.";
        if (attempt.state == CollectData.State.REVIEW) return "§cEntrega bloqueada: solicita revisión a un administrador.";
        CollectData.RewardTier tier = game.tier(attempt.score);
        if (tier == null || tier.items.isEmpty() && tier.commands.isEmpty())
            return "§cNo existe una recompensa configurada para " + attempt.score + " objetos.";

        attempt.state = CollectData.State.REVIEW; // Bloqueo previo a cualquier efecto.
        data.setDirty();
        try {
            // Mitigación ante apagones: solicitar guardado del bloqueo antes de dar ítems.
            // El guardado depende de la implementación de Minecraft y NO ofrece
            // atomicidad entre inventarios, drops y comandos de terceros.
            server.overworld().getDataStorage().save();
            for (ItemStack configured : tier.items) {
                if (configured.isEmpty()) continue;
                ItemStack remaining = configured.copy();
                player.getInventory().add(remaining);
                if (!remaining.isEmpty()) {
                    ItemEntity drop = new ItemEntity(player.serverLevel(), player.getX(),
                            player.getY() + 0.2, player.getZ(), remaining.copy());
                    drop.setTarget(player.getUUID()); // NeoForge: sólo este jugador puede recogerlo.
                    drop.setDefaultPickUpDelay();
                    if (!player.serverLevel().addFreshEntity(drop))
                        throw new IllegalStateException("No se pudo crear el drop de recompensa");
                }
            }
            player.containerMenu.broadcastChanges();
            String safeName = player.getGameProfile().getName();
            for (String configured : tier.commands) {
                String command = configured.replace("{player}", safeName);
                if (command.startsWith("/")) command = command.substring(1);
                // Brigadier en 1.21.1 no devuelve resultado sincrónico aquí.
                // Una excepción marca REVIEW; para comprobar éxito real de
                // comandos externos se requiere integración con su API.
                server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
            }
            attempt.state = CollectData.State.CLAIMED;
            progress.claimedCount++;
            data.setDirty();
            server.overworld().getDataStorage().save();
            player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            return "§a¡Premio entregado! §fResultado: §e" + attempt.score + "/" + game.points.size()
                    + " §7(Intento #" + attempt.serial + ")";
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(CollectService.class).error(
                    "REVISAR premio {} / jugador {} / intento {}. NO REINTENTAR AUTOMATICAMENTE",
                    gameName, player.getUUID(), attempt.serial, error);
            // REVIEW impide el reintento automático; la entrega pudo ser parcial.
            return "§cHubo un fallo entregando el premio. Un administrador debe revisar el registro.";
        }
    }

    public static boolean gameHasActive(CollectData data, String id) {
        for (CollectData.Progress progress : data.players.getOrDefault(id, Map.of()).values())
            if (progress.last != null && progress.last.state == CollectData.State.ACTIVE) return true;
        return false;
    }

    public static void notifyPlayer(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CollectVisuals.forget(event.getEntity().getUUID());
    }
    public static void onServerStopped(ServerStoppedEvent event) {
        CollectVisuals.resetAll(); ticks = 0;
    }
}
