package de.coinshop;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moderator-Rang: Admins vergeben ihn mit /coinsadmin mod add [Spieler].
 * Moderatoren bekommen den Befehl /mod mit kleinen, sicheren Hilfen (Gamemode, Nacht überspringen,
 * Wetter, Teleport, Heilen, Stumm schalten, Kick). Coins, Shop und Admin-Befehle bleiben tabu.
 */
public final class ModeratorManager implements CommandExecutor, TabCompleter, Listener {

    private static final String META_PREFIX = "mod_";
    private static final List<String> SUBS = List.of("hilfe", "gm", "nacht", "tag", "wetter", "tp", "tphere",
            "heilen", "essen", "spawn", "mute", "unmute", "kick", "liste");

    private final CoinShopPlugin plugin;
    private final Map<UUID, Long> muted = new HashMap<>();   // Spieler -> Ende (ms)
    private final Map<UUID, Long> lastSkip = new HashMap<>(); // Moderator -> letzte Zeit/Wetter-Änderung

    public ModeratorManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Rang speichern
    // ------------------------------------------------------------------
    public boolean isMod(UUID uuid) {
        return plugin.database().getMetaLong(META_PREFIX + uuid, 0L) == 1L;
    }

    public void setMod(UUID uuid, boolean on) {
        plugin.database().setMetaLong(META_PREFIX + uuid, on ? 1L : 0L);
    }

    private boolean allowed(CommandSender s) {
        if (s.hasPermission("coinshop.admin")) {
            return true;
        }
        return s instanceof Player p && isMod(p.getUniqueId());
    }

    /** Admins und andere Moderatoren dürfen von Moderatoren nicht angefasst werden. */
    private boolean protectedTarget(CommandSender by, Player target) {
        if (by.hasPermission("coinshop.admin")) {
            return false;
        }
        return target.hasPermission("coinshop.admin") || isMod(target.getUniqueId());
    }

    // ------------------------------------------------------------------
    //  Chat-Sperre (mute)
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Long until = muted.get(event.getPlayer().getUniqueId());
        if (until == null) {
            return;
        }
        if (until < System.currentTimeMillis()) {
            muted.remove(event.getPlayer().getUniqueId());
            return;
        }
        event.setCancelled(true);
        long min = Math.max(1, (until - System.currentTimeMillis()) / 60_000L + 1);
        plugin.messages().send(event.getPlayer(), "mod-you-muted", "min", min);
    }

    // ------------------------------------------------------------------
    //  /mod ...
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages msg = plugin.messages();
        if (!allowed(sender)) {
            msg.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("hilfe")) {
            msg.send(sender, "mod-help");
            return true;
        }
        try {
            switch (args[0].toLowerCase()) {
                case "gm" -> gamemode(sender, args);
                case "nacht" -> time(sender, true);
                case "tag" -> time(sender, false);
                case "wetter" -> weather(sender);
                case "tp" -> teleport(sender, args, false);
                case "tphere" -> teleport(sender, args, true);
                case "heilen" -> heal(sender, args, true);
                case "essen" -> heal(sender, args, false);
                case "spawn" -> spawnPlayer(sender, args);
                case "mute" -> mute(sender, args);
                case "unmute" -> unmute(sender, args);
                case "kick" -> kick(sender, args);
                case "liste" -> list(sender);
                default -> msg.send(sender, "mod-help");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler bei /mod: " + e);
            msg.send(sender, "error");
        }
        return true;
    }

    private void log(CommandSender by, Player target, String what) {
        UUID id = by instanceof Player p ? p.getUniqueId() : new UUID(0L, 0L);
        plugin.database().ensureAutoCommit();
        plugin.database().logTx(id, by.getName(), "mod", 0L, what + (target == null ? "" : " -> " + target.getName()));
        plugin.getLogger().info("[Mod] " + by.getName() + ": " + what + (target == null ? "" : " -> " + target.getName()));
    }

    private Player findOnline(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            plugin.messages().send(sender, "mod-not-online", "player", name);
        }
        return p;
    }

    private boolean inExpedition(Player p) {
        return plugin.expeditions().isIn(p.getUniqueId());
    }

    // --- Gamemode ------------------------------------------------------
    private void gamemode(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "mod-gm-usage");
            return;
        }
        GameMode mode = switch (args[1].toLowerCase()) {
            case "survival", "s", "0", "überleben" -> GameMode.SURVIVAL;
            case "adventure", "a", "2", "abenteuer" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3", "zuschauer" -> GameMode.SPECTATOR;
            case "creative", "c", "1", "kreativ" -> GameMode.CREATIVE;
            default -> null;
        };
        if (mode == null) {
            msg.send(sender, "mod-gm-usage");
            return;
        }
        boolean admin = sender.hasPermission("coinshop.admin");
        if (mode == GameMode.CREATIVE && !admin && !plugin.getConfig().getBoolean("moderator.allow-creative", true)) {
            msg.send(sender, "mod-no-creative");
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = findOnline(sender, args[2]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg.send(sender, "only-players");
            return;
        }
        if (target != sender && protectedTarget(sender, target)) {
            msg.send(sender, "mod-protected");
            return;
        }
        if (!admin && inExpedition(target)) {
            msg.send(sender, "mod-in-expedition", "player", target.getName());
            return;
        }
        target.setGameMode(mode);
        msg.send(sender, "mod-gm-set", "player", target.getName(), "mode", mode.name());
        if (target != sender) {
            msg.send(target, "mod-gm-you", "mode", mode.name());
        }
        log(sender, target, "gamemode " + mode.name());
    }

    // --- Zeit / Wetter ---------------------------------------------------
    private boolean cooldownOk(CommandSender sender) {
        if (!(sender instanceof Player p) || sender.hasPermission("coinshop.admin")) {
            return true;
        }
        long wait = plugin.getConfig().getLong("moderator.skip-cooldown-seconds", 300L) * 1000L;
        long now = System.currentTimeMillis();
        long last = lastSkip.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < wait) {
            plugin.messages().send(sender, "mod-cooldown", "sec", (wait - (now - last)) / 1000L + 1);
            return false;
        }
        lastSkip.put(p.getUniqueId(), now);
        return true;
    }

    private World normalWorld(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            return Bukkit.getWorlds().get(0);
        }
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || inExpedition(p)) {
            plugin.messages().send(sender, "mod-wrong-world");
            return null;
        }
        return w;
    }

    private void time(CommandSender sender, boolean toDay) {
        setDaytime(sender, toDay ? 1000L : 13000L, toDay ? "mod-day" : "mod-night");
    }

    /** Setzt die Tageszeit (0-24000) und springt dabei nur vorwärts, damit der Tageszähler weiterläuft. */
    private void setDaytime(CommandSender sender, long target, String messageKey) {
        World w = normalWorld(sender);
        if (w == null || !cooldownOk(sender)) {
            return;
        }
        long full = w.getFullTime();
        long add = target - (full % 24000L);
        if (add < 0) {
            add += 24000L;
        }
        w.setFullTime(full + add);
        plugin.messages().send(sender, messageKey);
        log(sender, null, "Zeit auf " + target);
    }

    private void weather(CommandSender sender) {
        setWeather(sender, "clear");
    }

    private void setWeather(CommandSender sender, String kind) {
        World w = normalWorld(sender);
        if (w == null || !cooldownOk(sender)) {
            return;
        }
        switch (kind) {
            case "rain" -> {
                w.setStorm(true);
                w.setThundering(false);
            }
            case "thunder" -> {
                w.setStorm(true);
                w.setThundering(true);
            }
            default -> {
                w.setStorm(false);
                w.setThundering(false);
            }
        }
        plugin.messages().send(sender, "mod-weather");
        log(sender, null, "Wetter " + kind);
    }

    // --- Teleport ----------------------------------------------------------
    private void teleport(CommandSender sender, String[] args, boolean here) {
        Messages msg = plugin.messages();
        if (!(sender instanceof Player self)) {
            msg.send(sender, "only-players");
            return;
        }
        if (args.length < 2) {
            msg.send(sender, "mod-tp-usage");
            return;
        }
        Player target = findOnline(sender, args[1]);
        if (target == null) {
            return;
        }
        boolean admin = sender.hasPermission("coinshop.admin");
        if (!admin && (inExpedition(target) || inExpedition(self))) {
            msg.send(sender, "mod-in-expedition", "player", target.getName());
            return;
        }
        if (here) {
            if (protectedTarget(sender, target)) {
                msg.send(sender, "mod-protected");
                return;
            }
            target.teleport(self.getLocation());
            msg.send(target, "mod-tp-you", "player", self.getName());
        } else {
            self.teleport(target.getLocation());
        }
        msg.send(sender, "mod-tp-done", "player", target.getName());
        log(sender, target, here ? "tphere" : "tp");
    }

    private void spawnPlayer(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "mod-spawn-usage");
            return;
        }
        Player target = findOnline(sender, args[1]);
        if (target == null) {
            return;
        }
        if (protectedTarget(sender, target)) {
            msg.send(sender, "mod-protected");
            return;
        }
        if (!sender.hasPermission("coinshop.admin") && inExpedition(target)) {
            msg.send(sender, "mod-in-expedition", "player", target.getName());
            return;
        }
        target.teleport(plugin.teleports().spawnLocation());
        msg.send(sender, "mod-spawn-done", "player", target.getName());
        msg.send(target, "mod-spawn-you");
        log(sender, target, "zum Spawn");
    }

    // --- Heilen / Essen ------------------------------------------------------
    private void heal(CommandSender sender, String[] args, boolean health) {
        Messages msg = plugin.messages();
        Player target;
        if (args.length >= 2) {
            target = findOnline(sender, args[1]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            msg.send(sender, "only-players");
            return;
        }
        if (!sender.hasPermission("coinshop.admin") && inExpedition(target)) {
            msg.send(sender, "mod-in-expedition", "player", target.getName());
            return;
        }
        if (health) {
            AttributeInstance max = target.getAttribute(Attribute.MAX_HEALTH);
            target.setHealth(max == null ? 20.0 : max.getValue());
            target.setFireTicks(0);
        } else {
            target.setFoodLevel(20);
            target.setSaturation(20f);
        }
        msg.send(sender, health ? "mod-healed" : "mod-fed", "player", target.getName());
        log(sender, target, health ? "heilen" : "essen");
    }

    // --- Mute / Kick -----------------------------------------------------------
    private void mute(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "mod-mute-usage");
            return;
        }
        Player target = findOnline(sender, args[1]);
        if (target == null) {
            return;
        }
        if (protectedTarget(sender, target) || target == sender) {
            msg.send(sender, "mod-protected");
            return;
        }
        int max = plugin.getConfig().getInt("moderator.max-mute-minutes", 60);
        int minutes = 10;
        if (args.length >= 3) {
            try {
                minutes = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                msg.send(sender, "mod-mute-usage");
                return;
            }
        }
        minutes = Math.max(1, Math.min(max, minutes));
        muted.put(target.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L);
        msg.send(sender, "mod-muted", "player", target.getName(), "min", minutes);
        msg.send(target, "mod-you-muted", "min", minutes);
        log(sender, target, "mute " + minutes + " min");
    }

    private void unmute(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "mod-mute-usage");
            return;
        }
        Player target = findOnline(sender, args[1]);
        if (target == null) {
            return;
        }
        muted.remove(target.getUniqueId());
        msg.send(sender, "mod-unmuted", "player", target.getName());
        log(sender, target, "unmute");
    }

    private void kick(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2) {
            msg.send(sender, "mod-kick-usage");
            return;
        }
        Player target = findOnline(sender, args[1]);
        if (target == null) {
            return;
        }
        if (protectedTarget(sender, target) || target == sender) {
            msg.send(sender, "mod-protected");
            return;
        }
        String reason = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "Bitte melde dich bei einem Admin.";
        log(sender, target, "kick: " + reason);
        target.kick(net.kyori.adventure.text.Component.text(reason));
        msg.send(sender, "mod-kicked", "player", target.getName());
    }

    private void list(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isMod(p.getUniqueId())) {
                names.add(p.getName());
            }
        }
        plugin.messages().send(sender, "mod-list", "names", names.isEmpty() ? "-" : String.join(", ", names));
    }

    // ------------------------------------------------------------------
    //  Normale Minecraft-Befehle für Moderatoren (gleiche Schreibweise wie im Vanilla-Spiel)
    //  Admins und Operatoren sind nicht betroffen: für sie läuft der echte Minecraft-Befehl.
    // ------------------------------------------------------------------
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (p.isOp() || p.hasPermission("coinshop.admin") || !isMod(p.getUniqueId())) {
            return;
        }
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        String label = parts[0].toLowerCase();
        if (label.startsWith("minecraft:")) {
            label = label.substring(10);
        }
        String[] args = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        switch (label) {
            case "gamemode" -> {
                event.setCancelled(true);
                String[] a = new String[args.length + 1];
                a[0] = "gm";
                System.arraycopy(args, 0, a, 1, args.length);
                guard(p, () -> gamemode(p, a));
            }
            case "time" -> {
                event.setCancelled(true);
                guard(p, () -> vanillaTime(p, args));
            }
            case "weather" -> {
                event.setCancelled(true);
                guard(p, () -> {
                    if (args.length < 1 || !List.of("clear", "rain", "thunder").contains(args[0].toLowerCase())) {
                        plugin.messages().send(p, "mod-weather-usage");
                    } else {
                        setWeather(p, args[0].toLowerCase());
                    }
                });
            }
            case "tp", "teleport" -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("spawn")) {
                    return; // normales /tp spawn des Plugins
                }
                event.setCancelled(true);
                guard(p, () -> vanillaTp(p, args));
            }
            case "kick" -> {
                event.setCancelled(true);
                String[] a = new String[args.length + 1];
                a[0] = "kick";
                System.arraycopy(args, 0, a, 1, args.length);
                guard(p, () -> kick(p, a));
            }
            default -> { }
        }
    }

    private void guard(Player p, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Fehler bei Moderator-Befehl: " + e);
            plugin.messages().send(p, "error");
        }
    }

    /** /time set day|noon|night|midnight|Zahl, /time add Zahl, /time query daytime */
    private void vanillaTime(Player p, String[] args) {
        Messages msg = plugin.messages();
        if (args.length < 2 && !(args.length == 1 && args[0].equalsIgnoreCase("query"))) {
            msg.send(p, "mod-time-usage");
            return;
        }
        World w = normalWorld(p);
        if (w == null) {
            return;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("query")) {
            msg.send(p, "mod-time-query", "time", w.getTime());
            return;
        }
        long value;
        switch (args[1].toLowerCase()) {
            case "day" -> value = 1000L;
            case "noon" -> value = 6000L;
            case "night" -> value = 13000L;
            case "midnight" -> value = 18000L;
            default -> {
                try {
                    value = Long.parseLong(args[1]);
                } catch (NumberFormatException e) {
                    msg.send(p, "mod-time-usage");
                    return;
                }
            }
        }
        if (sub.equals("set")) {
            setDaytime(p, ((value % 24000L) + 24000L) % 24000L, "mod-time-set");
        } else if (sub.equals("add")) {
            setDaytime(p, (w.getTime() + ((value % 24000L) + 24000L)) % 24000L, "mod-time-set");
        } else {
            msg.send(p, "mod-time-usage");
        }
    }

    /** /tp Ziel, /tp Spieler Ziel, /tp x y z */
    private void vanillaTp(Player p, String[] args) {
        Messages msg = plugin.messages();
        if (args.length == 1) {
            teleport(p, new String[]{"tp", args[0]}, false);
        } else if (args.length == 2) {
            Player who = findOnline(p, args[0]);
            Player dest = who == null ? null : findOnline(p, args[1]);
            if (who == null || dest == null) {
                return;
            }
            if (protectedTarget(p, who) || inExpedition(who) || inExpedition(dest)) {
                msg.send(p, "mod-protected");
                return;
            }
            who.teleport(dest.getLocation());
            msg.send(p, "mod-tp-done", "player", who.getName());
            log(p, who, "tp zu " + dest.getName());
        } else if (args.length == 3) {
            if (inExpedition(p)) {
                msg.send(p, "mod-in-expedition", "player", p.getName());
                return;
            }
            try {
                org.bukkit.Location l = p.getLocation().clone();
                l.setX(Double.parseDouble(args[0]));
                l.setY(Double.parseDouble(args[1]));
                l.setZ(Double.parseDouble(args[2]));
                p.teleport(l);
                log(p, null, "tp " + args[0] + " " + args[1] + " " + args[2]);
            } catch (NumberFormatException e) {
                msg.send(p, "mod-tp-usage");
            }
        } else {
            msg.send(p, "mod-tp-usage");
        }
    }

    // ------------------------------------------------------------------
    //  Verwaltung durch Admins: /coinsadmin mod add|remove|list [Spieler]
    // ------------------------------------------------------------------
    public void adminCommand(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        Database db = plugin.database();
        if (args.length < 2) {
            msg.send(sender, "admin-mod-usage");
            return;
        }
        String action = args[1].toLowerCase();
        if (action.equals("list")) {
            List<String> names = new ArrayList<>();
            for (Database.Account a : db.allAccounts()) {
                if (isMod(a.uuid())) {
                    names.add(a.name());
                }
            }
            msg.send(sender, "admin-mod-list", "names", names.isEmpty() ? "-" : String.join(", ", names));
            return;
        }
        if (args.length < 3 || !(action.equals("add") || action.equals("remove"))) {
            msg.send(sender, "admin-mod-usage");
            return;
        }
        Optional<UUID> target = db.findUuidByName(args[2]);
        if (target.isEmpty()) {
            msg.send(sender, "player-unknown", "player", args[2]);
            return;
        }
        String name = db.getName(target.get());
        boolean add = action.equals("add");
        setMod(target.get(), add);
        db.logTx(target.get(), name, "admin-mod", 0L, (add ? "Moderator ernannt" : "Moderator entfernt") + " durch " + sender.getName());
        msg.send(sender, add ? "admin-mod-added" : "admin-mod-removed", "player", name);
        Player online = Bukkit.getPlayer(target.get());
        if (online != null) {
            msg.send(online, add ? "mod-you-added" : "mod-you-removed");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (!allowed(sender)) {
            return options;
        }
        if (args.length == 1) {
            options.addAll(SUBS);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("gm")) {
                options.addAll(List.of("survival", "adventure", "spectator", "creative"));
            } else if (List.of("tp", "tphere", "heilen", "essen", "spawn", "mute", "unmute", "kick").contains(args[0].toLowerCase())) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    options.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("gm")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                options.add(p.getName());
            }
        }
        String typed = args[args.length - 1].toLowerCase();
        options.removeIf(o -> !o.toLowerCase().startsWith(typed));
        return options;
    }
}
