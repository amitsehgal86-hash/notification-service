package com.notification.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.notification.domain.DemoService;
import com.notification.infrastructure.repo.ConsumerPreferenceRepository;
import com.notification.infrastructure.repo.NotificationRepository;
import com.notification.infrastructure.repo.SeedRepository;
import com.notification.model.Notification;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full-screen terminal dashboard for the notification service, bundled in the same JAR.
 * Launch with:  java -jar notification-service.jar tui
 * Drives the domain services directly (same JVM), so no running web server is required.
 */
@Component
public class NotificationTui {

    private final DemoService demoService;
    private final SeedRepository seedRepository;
    private final NotificationRepository notificationRepository;
    private final ConsumerPreferenceRepository preferenceRepository;

    private final UUID tenant = DemoService.DEMO_TENANT;
    private Map<String, Object> lastLoad;

    public NotificationTui(DemoService demoService,
                           SeedRepository seedRepository,
                           NotificationRepository notificationRepository,
                           ConsumerPreferenceRepository preferenceRepository) {
        this.demoService = demoService;
        this.seedRepository = seedRepository;
        this.notificationRepository = notificationRepository;
        this.preferenceRepository = preferenceRepository;
    }

    public void run() throws IOException {
        Screen screen = new DefaultTerminalFactory()
                .setForceTextTerminal(true)
                .setTerminalEmulatorTitle("Notification Service")
                .createScreen();
        screen.startScreen();
        try {
            String status = "Ready. Tenant " + tenant;
            boolean running = true;
            while (running) {
                draw(screen, status);
                KeyStroke key = screen.readInput();
                if (key.getKeyType() == KeyType.EOF || key.getKeyType() == KeyType.Escape) {
                    break;
                }
                if (key.getKeyType() != KeyType.Character) {
                    continue;
                }
                switch (Character.toLowerCase(key.getCharacter())) {
                    case 'q' -> running = false;
                    case 's' -> status = doSeed(screen);
                    case 'l' -> status = doLoad(screen);
                    case 'b' -> { doBrowse(screen); status = "Ready."; }
                    case 'o' -> status = doOptOut(screen);
                    case 'r' -> status = "Refreshed.";
                    default -> { /* ignore */ }
                }
            }
        } finally {
            screen.stopScreen();
        }
    }

    // ---- actions -------------------------------------------------------------------------------

    private String doSeed(Screen screen) throws IOException {
        String in = prompt(screen, "Seed how many consumers? [1000000]: ");
        long count = parseLong(in, 1_000_000L);
        flash(screen, "Working: seeding " + fmt(count) + " consumers (+30% recent history)…");
        Map<String, Object> r = demoService.seed(tenant, count, 0.3);
        return "Seeded " + fmt(num(r, "consumersInserted")) + " consumers, "
                + fmt(num(r, "historyRowsInserted")) + " history rows in " + num(r, "elapsedMs") + " ms";
    }

    private String doLoad(Screen screen) throws IOException {
        String in = prompt(screen, "Send how many notifications? [50000]: ");
        int count = (int) parseLong(in, 50_000L);
        flash(screen, "Working: pushing " + fmt(count) + " notifications through the pipeline…");
        try {
            lastLoad = demoService.runLoad(tenant, count, 16);
        } catch (RuntimeException e) {
            return "Load failed: " + e.getMessage();
        }
        return "Load done: " + fmt(num(lastLoad, "processed")) + " processed in "
                + num(lastLoad, "elapsedMs") + " ms (" + fmt(num(lastLoad, "throughputPerSec")) + "/sec)";
    }

    private String doOptOut(Screen screen) throws IOException {
        String in = prompt(screen, "Opt-out consumer id (UUID): ");
        if (in == null || in.isBlank()) {
            return "Opt-out cancelled.";
        }
        try {
            boolean ok = preferenceRepository.optOut(tenant, UUID.fromString(in.trim()), "TUI", java.time.Instant.now());
            return ok ? "Consumer opted out." : "Consumer not found for this tenant.";
        } catch (IllegalArgumentException e) {
            return "Not a valid UUID.";
        }
    }

    private void doBrowse(Screen screen) throws IOException {
        List<Notification> rows = notificationRepository.list(tenant, null, null, 15);
        screen.clear();
        TextGraphics tg = screen.newTextGraphics();
        int w = screen.getTerminalSize().getColumns();
        drawBox(tg, 0, 0, w, 20, "Recent notifications (newest first)");
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.putString(2, 2, pad("STATUS", 12) + pad("CHANNEL", 9) + pad("CONSUMER", 38) + "SENT_AT");
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        int row = 3;
        for (Notification n : rows) {
            tg.setForegroundColor(colorForStatus(n.status().name()));
            String line = pad(n.status().name(), 12) + pad(n.channel().name(), 9)
                    + pad(n.consumerId().toString(), 38)
                    + (n.sentAt() == null ? "-" : n.sentAt().toString());
            tg.putString(2, row++, trunc(line, w - 4));
        }
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        tg.putString(2, 18, "Press any key to return…");
        screen.refresh();
        screen.readInput();
    }

    // ---- rendering -----------------------------------------------------------------------------

    private void draw(Screen screen, String status) throws IOException {
        screen.clear();
        TextGraphics tg = screen.newTextGraphics();
        TerminalSize size = screen.getTerminalSize();
        int w = Math.max(52, size.getColumns());

        long consumers = seedRepository.countConsumers(tenant);
        long notifs = notificationRepository.count(tenant);

        drawBox(tg, 0, 0, w, 22, "Notification Service — 3-day contact suppression demo");

        tg.setForegroundColor(TextColor.ANSI.CYAN_BRIGHT);
        tg.putString(2, 2, "Tenant: " + tenant);
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        tg.putString(2, 3, "Consumers: " + pad(fmt(consumers), 14) + "Notifications: " + fmt(notifs));

        tg.putString(2, 5, "Last load run:");
        if (lastLoad == null) {
            tg.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            tg.putString(4, 6, "(none yet — press [l] to run a load, [s] to seed first)");
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        } else {
            long processed = num(lastLoad, "processed");
            long max = Math.max(1, processed);
            drawBar(tg, 6, "SENT",           num(lastLoad, "sent"),                max, TextColor.ANSI.GREEN_BRIGHT);
            drawBar(tg, 7, "SUPPRESSED(3d)",  num(lastLoad, "suppressed_3day"),     max, TextColor.ANSI.YELLOW_BRIGHT);
            drawBar(tg, 8, "HELD(window)",    num(lastLoad, "held_outside_window"), max, TextColor.ANSI.BLUE_BRIGHT);
            drawBar(tg, 9, "OPTED_OUT",       num(lastLoad, "opted_out"),           max, TextColor.ANSI.RED_BRIGHT);
            drawBar(tg, 10, "FAILED",         num(lastLoad, "failed"),              max, TextColor.ANSI.RED);
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
            tg.putString(4, 12, "processed " + fmt(processed) + " in " + num(lastLoad, "elapsedMs")
                    + " ms  (" + fmt(num(lastLoad, "throughputPerSec")) + "/sec)");
        }

        // footer
        tg.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        tg.putString(2, 19, "[s]eed  [l]oad  [b]rowse  [o]pt-out  [r]efresh  [q]uit");
        tg.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        tg.putString(2, 20, trunc("status: " + status, w - 4));
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        screen.refresh();
    }

    private void flash(Screen screen, String msg) throws IOException {
        // Redraw with a "working" status so long operations show feedback before they block.
        draw(screen, msg);
    }

    private void drawBar(TextGraphics tg, int row, String label, long value, long max, TextColor color) {
        int barWidth = 24;
        int filled = (int) Math.round(barWidth * (value / (double) max));
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        tg.putString(4, row, pad(label, 16));
        tg.setForegroundColor(color);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        tg.putString(20, row, bar.toString());
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        tg.putString(46, row, fmt(value));
    }

    private void drawBox(TextGraphics tg, int x, int y, int w, int h, String title) {
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
        for (int i = x + 1; i < x + w - 1; i++) {
            tg.putString(i, y, "─");
            tg.putString(i, y + h - 1, "─");
        }
        for (int j = y + 1; j < y + h - 1; j++) {
            tg.putString(x, j, "│");
            tg.putString(x + w - 1, j, "│");
        }
        tg.putString(x, y, "┌");
        tg.putString(x + w - 1, y, "┐");
        tg.putString(x, y + h - 1, "└");
        tg.putString(x + w - 1, y + h - 1, "┘");
        tg.setForegroundColor(TextColor.ANSI.CYAN_BRIGHT);
        tg.putString(x + 2, y, " " + title + " ");
        tg.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private String prompt(Screen screen, String label) throws IOException {
        StringBuilder sb = new StringBuilder();
        int row = screen.getTerminalSize().getRows() - 1;
        while (true) {
            TextGraphics tg = screen.newTextGraphics();
            tg.putString(0, row, " ".repeat(Math.max(0, screen.getTerminalSize().getColumns())));
            tg.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
            tg.putString(0, row, label + sb + "▌");
            tg.setForegroundColor(TextColor.ANSI.DEFAULT);
            screen.refresh();
            KeyStroke k = screen.readInput();
            switch (k.getKeyType()) {
                case Enter -> { return sb.toString(); }
                case Escape -> { return null; }
                case Backspace -> { if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1); }
                case Character -> sb.append(k.getCharacter());
                default -> { /* ignore */ }
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Long.parseLong(s.trim().replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    private static String trunc(String s, int width) {
        return s.length() <= width ? s : s.substring(0, Math.max(0, width));
    }

    private static TextColor colorForStatus(String status) {
        return switch (status) {
            case "SENT", "DELIVERED" -> TextColor.ANSI.GREEN_BRIGHT;
            case "SUPPRESSED" -> TextColor.ANSI.YELLOW_BRIGHT;
            case "HELD" -> TextColor.ANSI.BLUE_BRIGHT;
            case "FAILED" -> TextColor.ANSI.RED_BRIGHT;
            default -> TextColor.ANSI.DEFAULT;
        };
    }
}
