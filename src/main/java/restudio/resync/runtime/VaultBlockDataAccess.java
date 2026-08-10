package restudio.resync.runtime;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Vault;

final class VaultBlockDataAccess {
    private VaultBlockDataAccess() {
    }

    static boolean isOminous(BlockData blockData) {
        return blockData instanceof Vault vault && vault.isOminous();
    }

    static boolean isActive(BlockData blockData) {
        return blockData instanceof Vault vault && vault.getVaultState() == Vault.State.ACTIVE;
    }
}
