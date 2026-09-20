package restudio.resync.commands;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import restudio.resync.ReSync;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncCommandAuthorityPermissionTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void operatorWithoutTheDedicatedPermissionCannotExportAuthority() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOp(true);
        player.addAttachment(plugin, "resync.authority.export", false);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"authority", "export"});

        Component message = player.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("No permission"));
    }

    @Test
    void dedicatedPermissionAllowsTheAuthorityCommandPastThePermissionGate() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.addAttachment(plugin, "resync.authority.export", true);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"authority", "export"});

        Component message = player.nextComponentMessage();
        assertNotNull(message);
        assertTrue(message.toString().contains("Server Not Initialized"));
    }

    @Test
    void consoleWithoutTheDedicatedPermissionCannotExportAuthority() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        ConsoleCommandSenderMock console = MockBukkit.getMock().getConsoleSender();
        console.addAttachment(plugin, "resync.authority.export", false);

        new ReSyncCommand(plugin).onCommand(console, command(), "resync",
            new String[]{"authority", "export"});

        Component message = console.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("No permission"));
    }

    @Test
    void consoleWithTheDedicatedPermissionCanReachAuthorityExport() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        ConsoleCommandSenderMock console = MockBukkit.getMock().getConsoleSender();
        console.addAttachment(plugin, "resync.authority.export", true);

        new ReSyncCommand(plugin).onCommand(console, command(), "resync",
            new String[]{"authority", "export"});

        Component message = console.nextComponentMessage();
        assertNotNull(message);
        assertTrue(message.toString().contains("Server Not Initialized"));
    }

    @Test
    void operatorWithoutTheDedicatedGrantPermissionCannotIssueAuthorityGrant() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOp(true);
        player.addAttachment(plugin, "resync.command", true);
        player.addAttachment(plugin, "resync.authority.grant", false);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"authority", "grant", "migration-a", "source", "d".repeat(64), "f".repeat(64), "grant-a"});

        Component message = player.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("No permission"));
    }

    @Test
    void operatorWithoutTheDedicatedAnchorPermissionCannotExportAnchor() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.setOp(true);
        player.addAttachment(plugin, "resync.command", true);
        player.addAttachment(plugin, "resync.authority.anchor", false);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"authority", "export-anchor"});

        Component message = player.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("No permission"));
    }

    @Test
    void generalCommandPermissionCannotExportMetadata() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.addAttachment(plugin, "resync.command", true);
        player.addAttachment(plugin, "resync.metadata.export", false);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"metadata", "export-schema", "26.3", "2026-09-20T00:00:00Z"});

        Component message = player.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("No permission"));
    }

    @Test
    void dedicatedMetadataPermissionReachesTheExporter() {
        TestReSync plugin = MockBukkit.loadSimple(TestReSync.class);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        player.addAttachment(plugin, "resync.metadata.export", true);

        new ReSyncCommand(plugin).onCommand(player, command(), "resync",
            new String[]{"metadata", "export-schema", "26.3", "2026-09-20T00:00:00Z"});

        Component message = player.nextComponentMessage();
        assertTrue(message != null && message.toString().contains("Server Not Initialized"));
    }

    private static Command command() {
        return new Command("resync") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }

    public static class TestReSync extends ReSync {
        @Override
        public void onEnable() {
        }

        @Override
        public void onDisable() {
        }
    }
}
