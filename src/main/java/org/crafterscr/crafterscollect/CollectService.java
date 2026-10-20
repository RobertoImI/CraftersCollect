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
                // Finaliza por tiempo o si, por cualquier razón, ya recogió todos.
                // También funciona con jugadores desconectados y partidas previas al parche.
                if (attempt.secondsLeft(now) == 0 || (!game.points.isEmpty()
                        && attempt.collected.size() >= game.points.size())) {
                    finishAttempt(data, game, attempt,
                            server.getPlayerList().getPlayer(entry.getKey()),
                            attempt.collected.size() >= game.points.size());
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
            if (!game.points.isEmpty() && attempt.collected.size() >= game.points.size()) {
                finishAttempt(data, game, attempt, player, true);
                continue;
            }

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
            // El último objeto cierra inmediatamente el intento; ya puede reclamar
            // su premio, sin esperar a que el reloj llegue a cero.
            if (!game.points.isEmpty() && attempt.collected.size() >= game.points.size()) {
                finishAttempt(data, game, attempt, player, true);
                continue;
            }
            String time = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
            player.displayClientMessage(Component.literal((picked ? "§a✦ +1  " : "§6✦  ")
                    + "§e" + attempt.score + "§7/§e" + game.points.size()
                    + "   §8│   §b⏱ " + time), true);
        }
    }

    /**
     * Una sola ruta de finalización: reloj agotado o colección completada.
     * Siempre limpia los objetos privados y persiste el estado antes del aviso.
     */
    private static void finishAttempt(CollectData data, CollectData.Game game,
                                      CollectData.Attempt attempt, ServerPlayer player,
                                      boolean completedAll) {
        if (attempt.state != CollectData.State.ACTIVE) return;
        attempt.state = CollectData.State.FINISHED;
        data.setDirty();
        if (player == null) return;
        CollectVisuals.clear(player);
        player.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.25f);
        String result = attempt.score + "/" + game.points.size();
        player.displayClientMessage(Component.literal("§a✔ §e" + result
                + (completedAll ? " §a¡COMPLETADO!" : " §c00:00")), true);
        notifyPlayer(player, (completedAll
                ? "§a¡Felicidades! Recogiste todos los objetos. La partida terminó."
                : "§eTiempo terminado. §fRecogiste " + result + ".")
                + " §fRegresa al NPC para reclamar tu premio.");
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
            int dropsOnGround = 0;
            int savedForLater = 0;
            for (ItemStack configured : tier.items) {
                if (configured.isEmpty()) continue;
                // No usar Inventory.add(ItemStack): en creativo puede consumir el
                // sobrante cuando el inventario está lleno sin crear ningún drop.
                ItemStack remaining = insertRewardInInventory(player, configured);
                if (!remaining.isEmpty()) {
                    // Entidad REAL a los pies, con dueño y mayor tiempo de vida.
                    ItemEntity drop = new ItemEntity(player.serverLevel(), player.getX(),
                            player.getY() + 0.65, player.getZ(), remaining.copy());
                    drop.setTarget(player.getUUID());
                    drop.setDefaultPickUpDelay();
                    drop.setExtendedLifetime();
                    drop.setDeltaMovement(0, 0.16, 0);
                    if (player.serverLevel().addFreshEntity(drop)) {
                        dropsOnGround++;
                    } else {
                        // Otro mod puede cancelar la creación del drop. Guardamos
                        // el resto persistentemente en vez de perderlo o bloquear
                        // el siguiente intento en REVIEW por falta de espacio.
                        progress.pendingItems.add(remaining.copy());
                        data.setDirty();
                        savedForLater++;
                        org.slf4j.LoggerFactory.getLogger(CollectService.class).warn(
                                "Drop bloqueado, premio pendiente {} / jugador {} / intento {}",
                                gameName, player.getUUID(), attempt.serial);
                    }
                }
            }
            player.getInventory().setChanged();
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
            return "§a¡Premio procesado! §fResultado: §e" + attempt.score + "/" + game.points.size()
                    + " §7(Intento #" + attempt.serial + ")"
                    + (dropsOnGround > 0 ? " §e" + dropsOnGround + " lote(s) a tus pies." : "")
                    + (savedForLater > 0 ? " §6" + savedForLater
                    + " lote(s) guardados. Libera espacio y usa /crafterscollect pending "
                    + gameName + " <jugador> para recuperarlos." : "");
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(CollectService.class).error(
                    "REVISAR premio {} / jugador {} / intento {}. NO REINTENTAR AUTOMATICAMENTE",
                    gameName, player.getUUID(), attempt.serial, error);
            // REVIEW impide el reintento automático; la entrega pudo ser parcial.
            return "§cHubo un fallo entregando el premio. Un administrador debe revisar el registro.";
        }
    }

    /**
     * Inserción sin pérdidas, también en creativo. Solo se usan los 36 espacios
     * normales (hotbar + inventario); nunca slots de armadura u offhand.
     * El ItemStack devuelto contiene EXACTAMENTE la cantidad que no cupo.
     */
    private static ItemStack insertRewardInInventory(ServerPlayer player, ItemStack configured) {
        ItemStack remaining = configured.copy();
        var inventory = player.getInventory();
        // Prioridad a completar stacks iguales, respetando sus componentes/NBT.
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
                && !remaining.isEmpty(); slot++) {
            ItemStack existing = inventory.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remaining))
                continue;
            int space = Math.min(existing.getMaxStackSize(), inventory.getMaxStackSize())
                    - existing.getCount();
            if (space <= 0) continue;
            int inserted = Math.min(space, remaining.getCount());
            existing.grow(inserted);
            remaining.shrink(inserted);
        }
        // Luego usar los espacios vacíos. No hacemos la inserción especial de
        // creativo que descartaba el remanente en Inventory.add().
        for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE
                && !remaining.isEmpty(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            int inserted = Math.min(remaining.getCount(),
                    Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize()));
            if (inserted <= 0) break;
            inventory.setItem(slot, remaining.copyWithCount(inserted));
            remaining.shrink(inserted);
        }
        inventory.setChanged();
        return remaining;
    }

    /**
     * Rescata premios que no pudieron materializarse porque otro mod bloqueó
     * el spawn. Nunca duplica una entrega ya reclamada: solo usa la cola guardada.
     */
    /**
     * Consulta segura: sólo muestra respaldos que realmente continúan en
     * pendingItems. Un drop YA creado en el suelo no pertenece a esta cola.
     */
    public static String pendingInfo(MinecraftServer server, ServerPlayer player, String gameName) {
        CollectData data = CollectData.get(server);
        if (!data.games.containsKey(gameName)) return "§cEse minijuego no existe.";
        CollectData.Progress progress = data.existing(gameName, player.getUUID());
        if (progress == null || progress.pendingItems.isEmpty())
            return "§aNo tienes premios pendientes de recuperar en " + gameName + ".";
        StringBuilder message = new StringBuilder("§ePendientes en " + gameName + ": §f");
        int units = 0;
        int totalStacks = 0;
        for (ItemStack stack : progress.pendingItems) {
            if (stack.isEmpty()) continue;
            units += stack.getCount();
            if (totalStacks > 0 && totalStacks < 5) message.append("§7, §f");
            if (totalStacks < 5) message.append(stack.getCount()).append("x ")
                    .append(stack.getHoverName().getString());
            totalStacks++;
        }
        if (totalStacks > 5) message.append(" §7y ").append(totalStacks - 5).append(" lote(s) más");
        return message.append(" §7(").append(units).append(" objetos en ")
                .append(totalStacks).append(" lotes). §fLibera espacio y usa ")
                .append("/crafterscollect pending ").append(gameName)
                .append(" ").append(player.getScoreboardName()).toString();
    }

    public static String deliverPending(MinecraftServer server, ServerPlayer player, String gameName) {
        CollectData data = CollectData.get(server);
        if (!data.games.containsKey(gameName)) return "§cEse minijuego no existe.";
        CollectData.Progress progress = data.existing(gameName, player.getUUID());
        if (progress == null || progress.pendingItems.isEmpty())
            return "§eNo tienes premios pendientes de recuperar.";
        int deliveredCount = 0;
        List<ItemStack> remainingPending = new ArrayList<>();
        for (ItemStack pending : progress.pendingItems) {
            if (pending.isEmpty()) continue;
            // Esta recuperación va al inventario exclusivamente. Si aún no
            // cabe, queda en la cola hasta el próximo intento de recuperar.
            ItemStack leftover = insertRewardInInventory(player, pending);
            deliveredCount += pending.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) remainingPending.add(leftover);
        }
        progress.pendingItems.clear();
        progress.pendingItems.addAll(remainingPending);
        data.setDirty();
        player.containerMenu.broadcastChanges();
        server.overworld().getDataStorage().save();
        return progress.pendingItems.isEmpty()
                ? "§a¡Recuperaste " + deliveredCount + " objeto(s)! No quedan premios pendientes."
                : "§eRecuperaste " + deliveredCount + " objeto(s). §6Quedan "
                + remainingPending.stream().mapToInt(ItemStack::getCount).sum()
                + " objeto(s) pendientes en " + remainingPending.size()
                + " lote(s). Libera espacio y repite el comando.";
    }

    public static boolean gameHasActive(CollectData data, String id) {
        for (CollectData.Progress progress : data.players.getOrDefault(id, Map.of()).values())
            if (progress.last != null && progress.last.state == CollectData.State.ACTIVE) return true;
        return false;
    }

    public static void notifyPlayer(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }
    /**
     * Recordatorio al reconectar: NO entregamos automáticamente objetos,
     * porque el jugador podría tener el inventario lleno u otro mod activo.
     * La cola guardada sigue intacta hasta que un NPC/admin ejecute pending.
     */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        CollectData data = CollectData.get(server);
        for (var entry : data.games.entrySet()) {
            CollectData.Progress progress = data.existing(entry.getKey(), player.getUUID());
            if (progress == null || progress.pendingItems.isEmpty()) continue;
            int units = progress.pendingItems.stream().mapToInt(ItemStack::getCount).sum();
            notifyPlayer(player, "§6CraftersCollect: Tienes §e" + units
                    + " §6objeto(s) pendientes de §e" + entry.getKey()
                    + "§6. Pide al NPC o admin recuperar con /crafterscollect pending "
                    + entry.getKey() + " " + player.getScoreboardName());
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CollectVisuals.forget(event.getEntity().getUUID());
    }
    public static void onServerStopped(ServerStoppedEvent event) {
        CollectVisuals.resetAll(); ticks = 0;
    }
}
