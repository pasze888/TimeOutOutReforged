package us.potatoboy.timeoutout;

import net.neoforged.neoforge.common.ModConfigSpec;

public class TimeOutOutConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue READ_TIMEOUT_SECONDS = BUILDER
            .comment("Connection read timeout in seconds (client and server side).")
            .defineInRange("readTimeoutSeconds", 120, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.LongValue LOGIN_TIMEOUT_TICKS = BUILDER
            .comment("How long the server waits for a player to log in, in ticks.")
            .defineInRange("loginTimeoutTicks", 2400L, 1L, Long.MAX_VALUE);

    public static final ModConfigSpec.LongValue KEEP_ALIVE_PACKET_INTERVAL_SECONDS = BUILDER
            .comment("Interval at which KeepAlive packets are sent to clients, in seconds.")
            .defineInRange("keepAlivePacketIntervalSeconds", 15L, 1L, Long.MAX_VALUE);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
