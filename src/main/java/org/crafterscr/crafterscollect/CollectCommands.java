package org.crafterscr.crafterscollect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Comandos exclusivamente administrativos (permiso 2). Se pueden invocar con
 * consola, command blocks, NPC de otro mod, @p / @s / @a o nombres ONLINE.
 * Los selectores los interpreta Brigadier; este mod NO inventa @initiator.
 */
public final class CollectCommands {
    private static final String G = "game";
    private static final String P = "targets";
    private static final String MIN = "min";
    private static final String MAX = "max";

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> game() {
        return Commands.argument(G, StringArgumentType.word()).suggests(CollectCommands::suggestGames);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> targets() {
        return Commands.argument(P, EntityArgument.players());
    }
    private static CompletableFuture<Suggestions> suggestGames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(CollectData.get(ctx.getSource().getServer()).games.keySet(), b);
    }
    private static String name(CommandContext<CommandSourceStack> ctx) { return StringArgumentType.getString(ctx, G); }
    private static CollectData data(CommandContext<CommandSourceStack> ctx) { return CollectData.get(ctx.getSource().getServer()); }

    private static void msg(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendSuccess(() -> Component.literal(s), false);
    }
    private static void error(CommandContext<CommandSourceStack> c, String s) {
        c.getSource().sendFailure(Component.literal("§c" + s));
    }
    private static CollectData.Game existing(CommandContext<CommandSourceStack> c) {
        CollectData.Game g = data(c).games.get(name(c));
        if (g == null) error(c, "No existe el minijuego '" + name(c) + "'.");
        return g;
    }
    private static boolean editable(CommandContext<CommandSourceStack> c, CollectData.Game game) {
        if (CollectService.gameHasActive(data(c), game.id)) {
            error(c, "No edites este minijuego mientras alguien participa."); return false;
        }
        return true;
    }
    private static ServerPlayer pointAdmin(CommandContext<CommandSourceStack> c) {
        try { return c.getSource().getPlayerOrException(); }
        catch (Exception e) { error(c, "Este comando requiere un administrador dentro del mundo."); return null; }
    }

    /** Posición en el centro horizontal del bloque, igual que point add. */
    private static CollectData.Point atPlayer(int id, ServerPlayer player) {
        return new CollectData.Point(id, player.level().dimension().location().toString(),
                Math.floor(player.getX()) + 0.5, player.getY(),
                Math.floor(player.getZ()) + 0.5);
    }

    /** Solo toma puntos de la misma dimensión y dentro del radio especificado. */
    private static CollectData.Point nearest(CollectData.Game game, ServerPlayer player, double radius) {
        String dimension = player.level().dimension().location().toString();
        CollectData.Point nearest = null;
        double best = radius * radius;
        for (CollectData.Point point : game.points) {
            if (!point.dimension().equals(dimension)) continue;
            double dx = point.x() - player.getX();
            double dy = point.y() - player.getY();
            double dz = point.z() - player.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= best) { best = distance; nearest = point; }
        }
        return nearest;
    }

    private static boolean overlaps(CollectData.Game game, CollectData.Point target, int exceptId) {
        for (CollectData.Point point : game.points) {
            if (point.id() == exceptId || !point.dimension().equals(target.dimension())) continue;
            if (Math.abs(point.x() - target.x()) < 0.25
                    && Math.abs(point.y() - target.y()) < 0.5
                    && Math.abs(point.z() - target.z()) < 0.25) return true;
        }
        return false;
    }

    private static void changedPoints(CommandContext<CommandSourceStack> c, CollectData.Game game) {
        data(c).setDirty();
        CollectVisuals.refreshPreviews(c.getSource().getServer(), game.id);
    }

    private static ItemStack inHand(CommandContext<CommandSourceStack> c) {
        try { return c.getSource().getPlayerOrException().getMainHandItem().copy(); }
        catch (Exception ignored) { error(c, "Solo un jugador en el mundo puede usar este comando con objeto en mano."); return ItemStack.EMPTY; }
    }
    private static int forTargets(CommandContext<CommandSourceStack> c, BiConsumer<ServerPlayer, String> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var players = EntityArgument.getPlayers(c, P);
        String n = name(c);
        for (ServerPlayer player : players) {
            try {
                action.accept(player, n);
            } catch (Exception e) {
                error(c, "Error para " + player.getScoreboardName() + ": " + e.getMessage());
            }
        }
        return players.size();
    }
    private static int action(CommandContext<CommandSourceStack> c, BiConsumer<ServerPlayer, String> action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return forTargets(c, action);
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("crafterscollect").requires(s -> s.hasPermission(2))
                .then(Commands.literal("create").then(game().executes(c -> {
                    String id = name(c);
                    if (!id.matches("[a-z0-9_\\-]{1,40}")) { error(c, "Utiliza un ID de 1-40 caracteres: a-z, 0-9, guion o _."); return 0; }
                    CollectData d = data(c);
                    if (d.games.containsKey(id)) { error(c, "Ya existe ese minijuego."); return 0; }
                    d.games.put(id, new CollectData.Game(id)); d.setDirty();
                    msg(c, "§aMinijuego creado: §e" + id); return 1;
                })))
                .then(Commands.literal("list").executes(c -> {
                    msg(c, "§eMinijuegos: §f" + String.join(", ", data(c).games.keySet())); return 1;
                }))
                .then(Commands.literal("item")
                    .then(Commands.literal("add").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        ItemStack held = inHand(c); if (held.isEmpty()) { error(c, "Sostén una cabeza u objeto en la mano principal."); return 0; }
                        g.appearances.add(held.copyWithCount(1)); data(c).setDirty();
                        msg(c, "§aApariencia #" + g.appearances.size() + " guardada: §f" + held.getHoverName().getString()); return 1;
                    })))
                    .then(Commands.literal("list").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null) return 0;
                        for (int i = 0; i < g.appearances.size(); i++)
                            msg(c, "§e#" + (i + 1) + " §f" + g.appearances.get(i).getHoverName().getString());
                        msg(c, "§7Total de apariencias: " + g.appearances.size()); return 1;
                    })))
                    .then(Commands.literal("remove").then(game()
                        .then(Commands.argument("index", IntegerArgumentType.integer(1)).executes(c -> {
                            CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                            int i = IntegerArgumentType.getInteger(c, "index") - 1;
                            if (i >= g.appearances.size()) { error(c, "Índice inválido."); return 0; }
                            g.appearances.remove(i); data(c).setDirty(); msg(c, "§aApariencia eliminada."); return 1;
                        }))))
                    .then(Commands.literal("clear").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        g.appearances.clear(); data(c).setDirty(); msg(c, "§aApariencias eliminadas."); return 1;
                    }))))
                .then(Commands.literal("point")
                    .then(Commands.literal("add").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        ServerPlayer p = pointAdmin(c); if (p == null) return 0;
                        CollectData.Point location = atPlayer(g.nextPointId, p);
                        if (overlaps(g, location, -1)) {
                            error(c, "Ya existe un punto en esta ubicación."); return 0;
                        }
                        CollectData.Point point = location;
                        g.nextPointId++;
                        g.points.add(point); changedPoints(c, g);
                        msg(c, "§aPunto #" + point.id() + " guardado. Total: " + g.points.size()); return 1;
                    })))
                    .then(Commands.literal("list").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null) return 0;
                        for (CollectData.Point point : g.points) msg(c, "§e#" + point.id()
                                + " §7" + point.dimension() + " §f" + (int)point.x() + " " + String.format(Locale.ROOT, "%.1f", point.y()) + " " + (int)point.z());
                        msg(c, "§aTotal de puntos: " + g.points.size()); return 1;
                    })))
                    .then(Commands.literal("remove").then(game()
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                            int id = IntegerArgumentType.getInteger(c, "id");
                            if (!g.points.removeIf(p -> p.id() == id)) { error(c, "No existe ese punto."); return 0; }
                            changedPoints(c, g);
                            msg(c, "§aPunto #" + id + " eliminado."); return 1;
                        }))))
                    // Identifica el punto más cercano (máximo 8 bloques).
                    .then(Commands.literal("nearest").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null) return 0;
                        ServerPlayer p = pointAdmin(c); if (p == null) return 0;
                        CollectData.Point near = nearest(g, p, 8.0);
                        if (near == null) { error(c, "No hay puntos en esta dimensión a menos de 8 bloques."); return 0; }
                        double distance = Math.sqrt(p.distanceToSqr(near.x(), near.y(), near.z()));
                        msg(c, "§ePunto #" + near.id() + " §fa "
                                + String.format(Locale.ROOT, "%.1f", distance)
                                + " bloques §7| mover: /crafterscollect point move " + g.id + " " + near.id()
                                + " §7| borrar: /crafterscollect point remove " + g.id + " " + near.id());
                        return 1;
                    })))
                    // Recoloca un punto existente SIN cambiar el ID ni los rangos.
                    .then(Commands.literal("move").then(game()
                        .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                            CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                            ServerPlayer p = pointAdmin(c); if (p == null) return 0;
                            int id = IntegerArgumentType.getInteger(c, "id");
                            int index = -1;
                            for (int i = 0; i < g.points.size(); i++)
                                if (g.points.get(i).id() == id) { index = i; break; }
                            if (index < 0) { error(c, "No existe el punto #" + id); return 0; }
                            CollectData.Point moved = atPlayer(id, p);
                            if (overlaps(g, moved, id)) { error(c, "Ya existe otro punto en ese lugar."); return 0; }
                            g.points.set(index, moved);
                            changedPoints(c, g);
                            msg(c, "§aPunto #" + id + " movido a §f"
                                    + (int)moved.x() + " " + String.format(Locale.ROOT, "%.1f", moved.y())
                                    + " " + (int)moved.z() + " §7(" + moved.dimension() + ").");
                            return 1;
                        }))))
                    // Borra el objeto más cercano, con un radio conservador de 3 bloques.
                    .then(Commands.literal("remove-nearest").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        ServerPlayer p = pointAdmin(c); if (p == null) return 0;
                        CollectData.Point near = nearest(g, p, 3.0);
                        if (near == null) { error(c, "No hay puntos a menos de 3 bloques."); return 0; }
                        g.points.remove(near);
                        changedPoints(c, g);
                        msg(c, "§aPunto #" + near.id() + " eliminado. §7Total: " + g.points.size());
                        return 1;
                    })))
                    .then(Commands.literal("preview").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null) return 0;
                        ServerPlayer p; try { p = c.getSource().getPlayerOrException(); }
                        catch (Exception e) { error(c, "La preview requiere un jugador."); return 0; }
                        if (CollectService.activeFor(data(c), p.getUUID()) != null) { error(c, "No puedes usar preview durante una partida."); return 0; }
                        if (CollectVisuals.hasPreview(p.getUUID())) CollectVisuals.clear(p);
                        else CollectVisuals.preview(p, g);
                        msg(c, "§aVista previa alternada. Verás cada objeto con su etiqueta #ID (sólo tú)."); return 1;
                    }))))
                .then(Commands.literal("set")
                    .then(Commands.literal("duration").then(game().then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400)).executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        g.durationSeconds = IntegerArgumentType.getInteger(c, "seconds");
                        data(c).setDirty(); msg(c, "§aDuración: " + g.durationSeconds + " s."); return 1;
                    }))))
                    .then(Commands.literal("attempts").then(game().then(Commands.argument("number", IntegerArgumentType.integer(1, 1000)).executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        g.attempts = IntegerArgumentType.getInteger(c, "number");
                        data(c).setDirty(); msg(c, "§aIntentos base: " + g.attempts); return 1;
                    }))))
                    .then(Commands.literal("radius").then(game().then(Commands.argument("blocks", DoubleArgumentType.doubleArg(.25, 4.0)).executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        g.radius = DoubleArgumentType.getDouble(c, "blocks");
                        data(c).setDirty(); msg(c, "§aRadio de recolección: " + g.radius); return 1;
                    })))))
                .then(Commands.literal("reward")
                    .then(Commands.literal("item").then(game().then(rangeMin().then(rangeMax().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        ItemStack held = inHand(c); if (held.isEmpty()) { error(c, "Sostén el premio en la mano, con la cantidad deseada."); return 0; }
                        CollectData.RewardTier tier = define(c, g); if (tier == null) return 0;
                        tier.items.add(held.copy()); data(c).setDirty();
                        msg(c, "§aÍtem agregado al rango " + tier.min + "-" + tier.max + ": §f" + held.getCount() + " x " + held.getHoverName().getString()); return 1;
                    })))))
                    .then(Commands.literal("command").then(game().then(rangeMin().then(rangeMax()
                        .then(Commands.argument("command", StringArgumentType.greedyString()).executes(c -> {
                            CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                            String cmd = StringArgumentType.getString(c, "command").trim();
                            if (cmd.isBlank() || cmd.length() > 1024) { error(c, "Comando vacío o demasiado largo."); return 0; }
                            CollectData.RewardTier tier = define(c, g); if (tier == null) return 0;
                            tier.commands.add(cmd); data(c).setDirty();
                            msg(c, "§aComando agregado al rango " + tier.min + "-" + tier.max); return 1;
                        }))))))
                    .then(Commands.literal("list").then(game().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null) return 0;
                        for (CollectData.RewardTier t : g.rewards)
                            msg(c, "§e" + t.min + "-" + t.max + " §f→ " + t.items.size()
                                    + " ítems, " + t.commands.size() + " comandos");
                        msg(c, "§7Rangos: " + g.rewards.size()); return 1;
                    })))
                    .then(Commands.literal("remove").then(game().then(rangeMin().then(rangeMax().executes(c -> {
                        CollectData.Game g = existing(c); if (g == null || !editable(c, g)) return 0;
                        int min = IntegerArgumentType.getInteger(c, MIN), max = IntegerArgumentType.getInteger(c, MAX);
                        if (!g.rewards.removeIf(r -> r.min == min && r.max == max)) { error(c, "Rango inexistente."); return 0; }
                        data(c).setDirty(); msg(c, "§aRango eliminado."); return 1;
                    }))))))
                .then(Commands.literal("play").then(game().then(targets().executes(c -> action(c,
                    (player, gameName) -> report(c, player, CollectService.start(c.getSource().getServer(), player, gameName)))))))
                .then(Commands.literal("claim").then(game().then(targets().executes(c -> action(c,
                    (player, gameName) -> report(c, player, CollectService.claim(c.getSource().getServer(), player, gameName)))))))
                .then(Commands.literal("pending")
                    .then(Commands.literal("info").then(game().then(targets().executes(c -> action(c,
                        (player, gameName) -> report(c, player, CollectService.pendingInfo(
                                c.getSource().getServer(), player, gameName)))))))
                    .then(game().then(targets().executes(c -> action(c,
                        (player, gameName) -> report(c, player, CollectService.deliverPending(
                                c.getSource().getServer(), player, gameName)))))))
                .then(Commands.literal("results").then(game().then(targets().executes(c -> action(c,
                    (player, gameName) -> info(c, player, gameName))))))
                .then(Commands.literal("attempts")
                    .then(Commands.literal("info").then(game().then(targets().executes(c -> action(c,
                        (player, gameName) -> info(c, player, gameName))))))
                    .then(Commands.literal("add").then(game().then(targets()
                        .then(Commands.argument("number", IntegerArgumentType.integer(1, 1000)).executes(c -> action(c,
                            (player, gameName) -> {
                                CollectData.Game g = data(c).games.get(gameName);
                                if (g == null) { error(c, "No existe el minijuego."); return; }
                                CollectData.Progress p = data(c).progress(gameName, player.getUUID());
                                p.bonus += IntegerArgumentType.getInteger(c, "number"); data(c).setDirty();
                                msg(c, "§a" + player.getScoreboardName() + ": +" + IntegerArgumentType.getInteger(c, "number") + " intentos.");
                            }))))))
                    .then(Commands.literal("reset").then(game().then(targets().executes(c -> action(c,
                        (player, gameName) -> {
                            CollectData.Game g = data(c).games.get(gameName);
                            if (g == null) { error(c, "No existe el minijuego."); return; }
                            CollectData.Progress p = data(c).progress(gameName, player.getUUID());
                            if (p.last != null && (p.last.state == CollectData.State.ACTIVE
                                    || p.last.state == CollectData.State.FINISHED || p.last.state == CollectData.State.REVIEW)) {
                                error(c, "Primero debe finalizar/reclamar/revisar el último intento de " + player.getScoreboardName()); return;
                            }
                            p.used = 0; p.bonus = 0; data(c).setDirty();
                            msg(c, "§aIntentos reiniciados para " + player.getScoreboardName() + ". Historial de premios preservado.");
                        }))))))
                    .then(Commands.literal("review-close").then(game().then(targets().executes(c -> action(c,
                        (player, gameName) -> {
                            CollectData.Progress p = data(c).existing(gameName, player.getUUID());
                            if (p == null || p.last == null || p.last.state != CollectData.State.REVIEW) {
                                error(c, "No hay ninguna entrega en revisión."); return;
                            }
                            // NO entrega nada: el administrador verificó/resolvió el incidente externamente.
                            p.last.state = CollectData.State.CLAIMED; data(c).setDirty();
                            msg(c, "§eRevisión cerrada para " + player.getScoreboardName() + ". No se ejecutó ningún premio.");
                        })))))
        );
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> rangeMin() {
        return Commands.argument(MIN, IntegerArgumentType.integer(0));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> rangeMax() {
        return Commands.argument(MAX, IntegerArgumentType.integer(0));
    }
    private static CollectData.RewardTier define(CommandContext<CommandSourceStack> c, CollectData.Game g) {
        int min = IntegerArgumentType.getInteger(c, MIN), max = IntegerArgumentType.getInteger(c, MAX);
        if (min > max || max > g.points.size()) { error(c, "Rango inválido. Debe estar entre 0 y " + g.points.size()); return null; }
        CollectData.RewardTier tier = g.defineTier(min, max);
        if (tier == null) error(c, "Los rangos no pueden solaparse (salvo repetir los mismos límites para añadir premios).");
        return tier;
    }
    private static void report(CommandContext<CommandSourceStack> c, ServerPlayer player, String result) {
        msg(c, "§7[" + player.getScoreboardName() + "] §f" + result);
        // La respuesta también llega al jugador, excepto si ya fue enviada por la fuente.
        if (c.getSource().getEntity() != player) CollectService.notifyPlayer(player, result);
    }
    private static void info(CommandContext<CommandSourceStack> c, ServerPlayer player, String gameName) {
        CollectData.Game game = data(c).games.get(gameName);
        if (game == null) { error(c, "No existe ese minijuego."); return; }
        CollectData.Progress p = data(c).existing(gameName, player.getUUID());
        if (p == null) { msg(c, "§e" + player.getScoreboardName() + " §7no ha participado. Quedan " + game.attempts + " intentos."); return; }
        msg(c, "§e" + player.getScoreboardName() + " §fIntentos usados: " + p.used + "/" + (game.attempts + p.bonus)
            + ", premios reclamados: " + p.claimedCount
            + ", lotes pendientes: " + p.pendingItems.size()
            + ", último: " + (p.last == null ? "ninguno" : p.last.state + " (" + p.last.score + " puntos, #" + p.last.serial + ")"));
        int size = p.history.size();
        for (int i = Math.max(0, size - 5); i < size; i++) {
            CollectData.Attempt a = p.history.get(i);
            msg(c, "§7Historial: intento #" + a.serial + ", " + a.score + " puntos, " + a.state);
        }
    }
}
