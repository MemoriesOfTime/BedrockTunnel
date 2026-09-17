package org.allaymc.bedrocktunnel;

import org.allaymc.bedrocktunnel.codec.CodecRegistry;
import org.allaymc.bedrocktunnel.codec.SupportedCodec;
import org.allaymc.bedrocktunnel.rules.PacketControlMode;
import org.allaymc.bedrocktunnel.rules.PacketRule;
import org.allaymc.bedrocktunnel.tunnel.TunnelTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

public final class UserSettingsStore {
    private static final Path DEFAULT_PATH = BedrockTunnelPaths.settingsFile();

    private final Path path;

    public UserSettingsStore() {
        this(DEFAULT_PATH);
    }

    public UserSettingsStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public Settings load(List<SupportedCodec> codecs) {
        Settings defaults = Settings.defaults(codecs);
        if (!Files.isRegularFile(path)) {
            return defaults;
        }

        try {
            Settings loaded = BedrockTunnelJson.MAPPER.readValue(path.toFile(), Settings.class);
            return sanitize(loaded, defaults);
        } catch (IOException exception) {
            return defaults;
        }
    }

    public void save(Settings settings) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    path,
                    BedrockTunnelJson.MAPPER.writeValueAsString(settings),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save user settings", exception);
        }
    }

    private static Settings sanitize(Settings loaded, Settings defaults) {
        if (loaded == null) {
            return defaults;
        }

        CodecSelection selectedCodec = loaded.selectedCodec();
        CodecSelection codec = selectedCodec == null ? defaults.selectedCodec() : resolveSelection(selectedCodec, defaults.selectedCodec());

        return new Settings(
                blankToDefault(loaded.listenHost(), defaults.listenHost()),
                validPortOrDefault(loaded.listenPort(), defaults.listenPort()),
                loaded.listenTransport() == null ? defaults.listenTransport() : loaded.listenTransport(),
                blankToDefault(loaded.targetHost(), defaults.targetHost()),
                validPortOrDefault(loaded.targetPort(), defaults.targetPort()),
                loaded.targetTransport() == null ? defaults.targetTransport() : loaded.targetTransport(),
                codec,
                loaded.controlMode() == null ? defaults.controlMode() : loaded.controlMode(),
                loaded.blockRules(),
                loaded.breakpointRules(),
                loaded.hideRules()
        );
    }

    private static CodecSelection resolveSelection(CodecSelection selection, CodecSelection fallback) {
        SupportedCodec resolved = CodecRegistry.resolve(selection.protocolVersion(), selection.minecraftVersion(), selection.netEase());
        if (resolved == null) {
            return fallback;
        }
        return new CodecSelection(resolved.protocolVersion(), resolved.minecraftVersion(), resolved.netEase());
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int validPortOrDefault(int value, int defaultValue) {
        return value >= 1 && value <= 65535 ? value : defaultValue;
    }

    public record Settings(
            String listenHost,
            int listenPort,
            TunnelTransport listenTransport,
            String targetHost,
            int targetPort,
            TunnelTransport targetTransport,
            CodecSelection selectedCodec,
            PacketControlMode controlMode,
            List<PacketRule> blockRules,
            List<PacketRule> breakpointRules,
            List<PacketRule> hideRules
    ) {
        public Settings {
            listenHost = listenHost == null || listenHost.isBlank() ? "0.0.0.0" : listenHost;
            listenPort = listenPort <= 0 ? 19134 : listenPort;
            listenTransport = listenTransport == null ? TunnelTransport.RAKNET : listenTransport;
            targetHost = targetHost == null || targetHost.isBlank() ? "127.0.0.1" : targetHost;
            targetPort = targetPort <= 0 ? 19132 : targetPort;
            targetTransport = targetTransport == null ? TunnelTransport.RAKNET : targetTransport;
            controlMode = controlMode == null ? PacketControlMode.BLACKLIST : controlMode;
            blockRules = blockRules == null ? List.of() : List.copyOf(blockRules);
            breakpointRules = breakpointRules == null ? List.of() : List.copyOf(breakpointRules);
            hideRules = hideRules == null ? List.of() : List.copyOf(hideRules);
        }

        public static Settings defaults(List<SupportedCodec> codecs) {
            SupportedCodec codec = codecs.getFirst();
            return new Settings(
                    "0.0.0.0",
                    19134,
                    TunnelTransport.RAKNET,
                    "127.0.0.1",
                    19132,
                    TunnelTransport.RAKNET,
                    new CodecSelection(codec.protocolVersion(), codec.minecraftVersion(), codec.netEase()),
                    PacketControlMode.BLACKLIST,
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    public record CodecSelection(int protocolVersion, String minecraftVersion, boolean netEase) {
    }
}
