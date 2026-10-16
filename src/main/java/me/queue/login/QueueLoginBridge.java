package me.queue.login;

import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RegisterEvent;
import fr.xephi.authme.events.RestoreSessionEvent;
import fr.xephi.authme.events.UnregisterByAdminEvent;
import fr.xephi.authme.events.UnregisterByPlayerEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class QueueLoginBridge extends JavaPlugin implements Listener {
    private enum RegistrationState { UNKNOWN, REGISTERED, UNREGISTERED }

    private AuthMeApi authMe;
    private AuthStateReporter reporter;
    private final Map<UUID, RegistrationState> registrationStates = new HashMap<>();
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
        getServer().getMessenger().registerOutgoingPluginChannel(this, AuthStateReporter.ACCOUNT_CHANNEL);
        reporter = new AuthStateReporter(this::isAuthenticated,
                (player, packet) -> player.sendPluginMessage(this, AuthStateReporter.CHANNEL, packet));
        getServer().getPluginManager().registerEvents(this, this);
        // Main-thread polling also covers missed events, session/premium auto-login,
        // administrator actions and players already online when this plugin enables.
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                report(player);
            }
        }, 20L, 20L);
        getLogger().info("Checking AuthMe login and registration state. Install on the login backend only.");
    }

    private void report(Player player) {
        RegistrationState state = registrationStates.get(player.getUniqueId());
        if (state == null || state == RegistrationState.UNKNOWN) {
            state = readRegistrationState(player);
            registrationStates.put(player.getUniqueId(), state);
        }
        if (reporter.check(player) && state != RegistrationState.UNREGISTERED) {
            registrationStates.put(player.getUniqueId(), RegistrationState.REGISTERED);
        }
        state = registrationStates.get(player.getUniqueId());
        if (state != RegistrationState.UNKNOWN) {
            String action = state == RegistrationState.REGISTERED ? "REGISTERED" : "UNREGISTERED";
            player.sendPluginMessage(this, AuthStateReporter.ACCOUNT_CHANNEL,
                    AuthStateReporter.packet(player.getName(), action));
        }
    }

    private RegistrationState readRegistrationState(Player player) {
        try {
            if (!authMe.getPlugin().isEnabled()) return RegistrationState.UNKNOWN;
            return authMe.isRegistered(player.getName()) ? RegistrationState.REGISTERED : RegistrationState.UNREGISTERED;
        } catch (RuntimeException e) {
            logApiFailure("Cannot read AuthMe registration state", e);
            return RegistrationState.UNKNOWN;
        }
    }

    private void checkNextTick(Player player) {
        getServer().getScheduler().runTask(this, () -> {
            if (getServer().getPlayer(player.getUniqueId()) == player) report(player);
        });
    }

    private boolean isAuthenticated(Player player) {
        try {
            return authMe.getPlugin().isEnabled() && authMe.isAuthenticated(player);
        } catch (RuntimeException e) {
            // Failure must never authorize a transfer. Limit repetitive API error logs.
            logApiFailure("Cannot read AuthMe state; reporting logged out", e);
            return false;
        }
    }

    private void logApiFailure(String message, RuntimeException e) {
        long now = System.nanoTime();
        if (nextErrorLog == 0 || now - nextErrorLog >= 0) {
            getLogger().log(Level.WARNING, message, e);
            nextErrorLog = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        registrationStates.put(event.getPlayer().getUniqueId(), RegistrationState.REGISTERED);
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRestoreSession(RestoreSessionEvent event) {
        registrationStates.put(event.getPlayer().getUniqueId(), RegistrationState.REGISTERED);
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(RegisterEvent event) {
        registrationStates.put(event.getPlayer().getUniqueId(), RegistrationState.REGISTERED);
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregister(UnregisterByPlayerEvent event) {
        registrationStates.put(event.getPlayer().getUniqueId(), RegistrationState.UNREGISTERED);
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregister(UnregisterByAdminEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        registrationStates.put(player.getUniqueId(), RegistrationState.UNREGISTERED);
        checkNextTick(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        registrationStates.remove(event.getPlayer().getUniqueId());
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
        registrationStates.clear();
    }
}
