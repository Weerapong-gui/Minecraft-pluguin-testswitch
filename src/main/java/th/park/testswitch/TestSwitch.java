package th.park.testswitch;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.PermissionFunction;
import com.velocitypowered.api.permission.PermissionProvider;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /test and /survival for approvers only. Starts the on-demand test server
 * (systemd user unit) when needed and stops it after it sits empty.
 * Also hides Velocity's own commands (e.g. /server) from everyone else.
 */
public final class TestSwitch {

    private static final String SURVIVAL = "survival";
    private static final String TEST = "test";
    private static final String TEST_UNIT = "mc-test.service";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration IDLE_STOP_AFTER = Duration.ofMinutes(10);

    private final ProxyServer proxy;
    private final Logger log;
    private final Path dataDir;
    private List<String> approvers = List.of();

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private volatile long testEmptySince = -1;

    @Inject
    public TestSwitch(ProxyServer proxy, Logger log, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.log = log;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("approvers.txt");
        if (Files.notExists(file)) {
            Files.writeString(file, "# One player name per line. Bedrock (Floodgate) names start with a dot.\n"
                    + "Verpong321\n.verpong321\n");
        }
        approvers = Files.readAllLines(file).stream().map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
        log.info("Approvers: {}", approvers);

        var cm = proxy.getCommandManager();
        cm.register(cm.metaBuilder("test").plugin(this).build(), new SwitchCommand(true));
        cm.register(cm.metaBuilder("survival").plugin(this).build(), new SwitchCommand(false));

        proxy.getScheduler().buildTask(this, this::idleCheck).repeat(1, TimeUnit.MINUTES).schedule();
    }

    private boolean isApprover(Player p) {
        return approvers.contains(p.getUsername().toLowerCase(Locale.ROOT));
    }

    // --- Permissions: non-approvers lose access to every velocity.command.* (e.g. /server) ---

    @Subscribe
    public void onPermissionsSetup(PermissionsSetupEvent e) {
        if (!(e.getSubject() instanceof Player player)) return;
        PermissionProvider original = e.getProvider();
        e.setProvider(subject -> {
            PermissionFunction base = original.createFunction(subject);
            return perm -> {
                if (!isApprover(player) && perm.startsWith("velocity.command.")) return Tristate.FALSE;
                return base.getPermissionValue(perm);
            };
        });
    }

    // --- Nobody but approvers may ever land on the test server ---

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent e) {
        Optional<RegisteredServer> target = e.getResult().getServer();
        if (target.isPresent() && target.get().getServerInfo().getName().equals(TEST) && !isApprover(e.getPlayer())) {
            e.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    // --- Commands ---

    private final class SwitchCommand implements SimpleCommand {
        private final boolean toTest;

        SwitchCommand(boolean toTest) { this.toTest = toTest; }

        @Override
        public boolean hasPermission(Invocation inv) {
            return inv.source() instanceof Player p && isApprover(p);
        }

        @Override
        public void execute(Invocation inv) {
            if (!(inv.source() instanceof Player p)) return;
            String targetName = toTest ? TEST : SURVIVAL;
            RegisteredServer target = proxy.getServer(targetName).orElse(null);
            if (target == null) {
                p.sendMessage(Component.text("ไม่พบเซิร์ฟ " + targetName, NamedTextColor.RED));
                return;
            }
            boolean alreadyThere = p.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().equals(targetName)).orElse(false);
            if (alreadyThere) {
                p.sendMessage(Component.text("อยู่ในเซิร์ฟ " + targetName + " อยู่แล้ว", NamedTextColor.YELLOW));
                return;
            }
            if (!toTest) {
                p.createConnectionRequest(target).fireAndForget();
                return;
            }
            isUp(target).thenAccept(up -> {
                if (up) {
                    p.createConnectionRequest(target).fireAndForget();
                } else {
                    p.sendMessage(Component.text("กำลังเปิดเซิร์ฟทดสอบ รอประมาณ 20-40 วินาที...", NamedTextColor.YELLOW));
                    startTestServer();
                    waitUntilUp(target, System.nanoTime() + START_TIMEOUT.toNanos(), p);
                }
            });
        }
    }

    private CompletableFuture<Boolean> isUp(RegisteredServer server) {
        return server.ping().thenApply(ping -> true).exceptionally(t -> false);
    }

    private void waitUntilUp(RegisteredServer target, long deadlineNanos, Player p) {
        proxy.getScheduler().buildTask(this, () -> isUp(target).thenAccept(up -> {
            if (up) {
                starting.set(false);
                testEmptySince = System.currentTimeMillis();
                if (p.isActive()) {
                    p.sendMessage(Component.text("เซิร์ฟทดสอบพร้อมแล้ว กำลังย้าย...", NamedTextColor.GREEN));
                    p.createConnectionRequest(target).fireAndForget();
                }
            } else if (System.nanoTime() > deadlineNanos) {
                starting.set(false);
                if (p.isActive()) p.sendMessage(Component.text("เปิดเซิร์ฟทดสอบไม่สำเร็จ ลองใหม่อีกครั้ง", NamedTextColor.RED));
                log.warn("Test server did not come up within {}", START_TIMEOUT);
            } else {
                waitUntilUp(target, deadlineNanos, p);
            }
        })).delay(2, TimeUnit.SECONDS).schedule();
    }

    private void startTestServer() {
        if (!starting.compareAndSet(false, true)) return; // a start is already in progress
        log.info("Starting {}", TEST_UNIT);
        systemctl("start");
    }

    private void systemctl(String action) {
        try {
            Process proc = new ProcessBuilder("systemctl", "--user", action, TEST_UNIT)
                    .redirectErrorStream(true).start();
            proc.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            proc.waitFor(30, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            log.error("systemctl --user {} {} failed", action, TEST_UNIT, ex);
        }
    }

    // --- Stop the test server after it has been empty for a while ---

    private void idleCheck() {
        if (starting.get()) return;
        RegisteredServer test = proxy.getServer(TEST).orElse(null);
        if (test == null) return;
        isUp(test).thenAccept(up -> {
            if (!up) { testEmptySince = -1; return; }
            if (!test.getPlayersConnected().isEmpty()) { testEmptySince = -1; return; }
            long now = System.currentTimeMillis();
            if (testEmptySince < 0) { testEmptySince = now; return; }
            if (now - testEmptySince >= IDLE_STOP_AFTER.toMillis()) {
                log.info("Test server empty for {} min, stopping", IDLE_STOP_AFTER.toMinutes());
                testEmptySince = -1;
                proxy.getScheduler().buildTask(this, () -> systemctl("stop")).schedule();
            }
        });
    }
}
