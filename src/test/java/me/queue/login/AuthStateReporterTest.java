package me.queue.login;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthStateReporterTest {
    @Test
    void heartbeatRecoversMissedLoginAndReportsLogoutUsingActualState() throws Exception {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("TestPlayer");
        AtomicBoolean authenticated = new AtomicBoolean(false);
        List<byte[]> sent = new ArrayList<>();
        AuthStateReporter reporter = new AuthStateReporter(p -> authenticated.get(),
                (carrier, packet) -> { assertSame(player, carrier); sent.add(packet); });

        reporter.check(player);
        authenticated.set(true); // Session restore without any event reaching this listener.
        reporter.check(player);
        reporter.check(player); // Retransmit so a lost first packet is harmless.
        authenticated.set(false);
        reporter.check(player);
        assertEquals(4, sent.size());
        List<String> expected = List.of("LOGOUT", "LOGIN", "LOGIN", "LOGOUT");
        for (int i = 0; i < sent.size(); i++) {
            try (var input = new DataInputStream(new ByteArrayInputStream(sent.get(i)))) {
                assertEquals(expected.get(i), input.readUTF());
                assertEquals("TestPlayer", input.readUTF());
                assertEquals(0, input.available());
            }
        }
    }

    @Test
    void disconnectedPlayerCannotSendStaleLogin() {
        Player player = mock(Player.class);
        AuthStateReporter reporter = new AuthStateReporter(p -> fail("Must not check offline player"),
                (p, packet) -> fail("Must not send stale state"));
        reporter.check(player);
    }

    @Test
    void onlinePresenceAloneNeverAuthorizesLogin() throws Exception {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("PremiumPlayer");
        List<byte[]> sent = new ArrayList<>();
        new AuthStateReporter(p -> false, (p, packet) -> sent.add(packet)).check(player);
        try (var input = new DataInputStream(new ByteArrayInputStream(sent.getFirst()))) {
            assertEquals("LOGOUT", input.readUTF());
        }
    }
}
