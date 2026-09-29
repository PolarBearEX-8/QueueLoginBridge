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
    private final Predicate<Player> authenticated;
    private final BiConsumer<Player, byte[]> send;

    AuthStateReporter(Predicate<Player> authenticated, BiConsumer<Player, byte[]> send) {
        this.authenticated = authenticated;
        this.send = send;
    }

    void check(Player player) {
        if (!player.isOnline()) return;
        send.accept(player, packet(player.getName(), authenticated.test(player)));
    }

    static byte[] packet(String username, boolean loggedIn) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(loggedIn ? "LOGIN" : "LOGOUT");
                output.writeUTF(username);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
