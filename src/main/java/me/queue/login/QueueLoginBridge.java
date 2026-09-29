package me.queue.login;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RegisterEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class QueueLoginBridge extends JavaPlugin implements Listener {
    private AuthMeApi authMe;
    private AuthStateReporter reporter;
    private long nextErrorLog;

    @Override
    public void onEnable() {
        authMe = AuthMeApi.getInstance();
        if (authMe == null) {
            getLogger().severe("AuthMe API is unavailable; disabling QueueLoginBridge.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getMessenger().registerOutgoingPluginChannel(this, AuthStateReporter.CHANNEL);
        reporter = new AuthStateReporter(this::isAuthenticated,
                (player, packet) -> player.sendPluginMessage(this, AuthStateReporter.CHANNEL, packet));
        getServer().getPluginManager().registerEvents(this, this);
        // Main-thread polling also covers missed events, session/premium auto-login,
        // administrator actions and players already online when this plugin enables.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                reporter.check(player);
            }
        }, 20L, 20L);
        getLogger().info("Checking AuthMe state every second and after login events. Install on the login backend only.");
    }

    private boolean isAuthenticated(Player player) {
        try {
            return authMe.getPlugin().isEnabled() && authMe.isAuthenticated(player);
        } catch (RuntimeException e) {
            // Failure must never authorize a transfer. Limit repetitive API error logs.
            long now = System.nanoTime();
            if (nextErrorLog == 0 || now - nextErrorLog >= 0) {
                getLogger().log(Level.WARNING, "Cannot read AuthMe state; reporting logged out", e);
                nextErrorLog = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            }
            return false;
        }
    }

    private void checkNextTick(Player player) {
        // Events can precede AuthMe's state update. Always read the API on the next tick,
        // and discard a callback if the same UUID has already reconnected.
        getServer().getScheduler().runTask(this, () -> {
            if (getServer().getPlayer(player.getUniqueId()) == player) reporter.check(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRestoreSession(RestoreSessionEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(RegisterEvent event) {
        // Registration by itself does not authorize login; read the actual API state.
        checkNextTick(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("queuelogin.admin")) return true;
        if (args.length != 1) return false;
        Player player = getServer().getPlayerExact(args[0]);
        if (player == null) {
            sender.sendMessage("Player is not online on this login server.");
        } else {
            sender.sendMessage(player.getName() + ": AuthMe authenticated=" + isAuthenticated(player));
        }
        return true;
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }
}
