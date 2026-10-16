package me.queue.login;

import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Stateless heartbeat: lost initial packets are repaired by the next check. */
final class AuthStateReporter {
    static final String CHANNEL = "authmevelocity:main";
    static final String ACCOUNT_CHANNEL = "queue:account";
    private final Predicate<Player> authenticated;
    private final BiConsumer<Player, byte[]> send;

    AuthStateReporter(Predicate<Player> authenticated, BiConsumer<Player, byte[]> send) {
        this.authenticated = authenticated;
        this.send = send;
    }

    boolean check(Player player) {
        if (!player.isOnline()) return false;
        boolean loggedIn = authenticated.test(player);
        send.accept(player, packet(player.getName(), loggedIn));
        return loggedIn;
    }

    static byte[] packet(String username, boolean loggedIn) {
        return packet(username, loggedIn ? "LOGIN" : "LOGOUT");
    }

    static byte[] packet(String username, String action) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(action);
                output.writeUTF(username);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
