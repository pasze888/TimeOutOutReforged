package us.potatoboy.timeoutout.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import us.potatoboy.timeoutout.TimeOutOutConfig;

@Mixin(ServerCommonPacketListenerImpl.class)
public final class ServerCommonPacketListenerImplMixin {
    @ModifyConstant(method = "keepConnectionAlive", constant = @Constant(longValue = 15000L))
    private long timeoutout$getKeepAlivePacketInterval(long interval) {
        return TimeOutOutConfig.KEEP_ALIVE_PACKET_INTERVAL_SECONDS.get() * 1000L;
    }
}
