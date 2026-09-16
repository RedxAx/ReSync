package restudio.resync.upgrade.command;

import restudio.resync.upgrade.adapter.OfflineUpgradeAdapter;
import restudio.resync.migration.MigrationPaths;

import java.util.List;
import java.util.Objects;

public final class CommandBindingUpgradeAdapter implements OfflineUpgradeAdapter {
    private final String owner;
    private final String relativePath;
    private final List<CommandBinding> bindings;
    private final CommandBindingTransformer transformer;

    public CommandBindingUpgradeAdapter(String owner, String relativePath, List<CommandBinding> bindings) {
        this.owner = requireText(owner, "owner");
        this.relativePath = MigrationPaths.requireRelative(relativePath);
        this.bindings = List.copyOf(bindings == null ? List.of() : bindings);
        this.transformer = new CommandBindingTransformer();
    }

    @Override
    public AdapterKey key() {
        return new AdapterKey(CommandBindingTransformer.ADAPTER_ID, CommandBindingTransformer.ADAPTER_VERSION);
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public boolean matches(String path) {
        return relativePath.equals(path);
    }

    @Override
    public String targetPath(String path) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Command Binding Adapter Path Does Not Match");
        }
        return relativePath;
    }

    @Override
    public TransformResult transform(String path, byte[] sourceBytes) {
        if (!matches(path)) {
            throw new IllegalArgumentException("Command Binding Adapter Path Does Not Match");
        }
        CommandBindingOutput output = transformer.transform(new CommandBindingInput(RawGraphDocument.parse(
            Objects.requireNonNull(sourceBytes, "sourceBytes")), bindings));
        return output.changed() ? TransformResult.changed(output.canonicalBytes()) : TransformResult.unchanged(output.canonicalBytes());
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isBlank() || normalized.indexOf('\u0000') >= 0
            || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " Is Invalid");
        }
        return normalized;
    }
}
