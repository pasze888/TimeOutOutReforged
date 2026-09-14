package us.potatoboy.timeoutout.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import us.potatoboy.timeoutout.TimeOutOutConfig;

@Mixin(targets = {
        "net/minecraft/network/Connection$1",
        "net/minecraft/server/network/ServerConnectionListener$1"
})
public abstract class ChannelInitializerMixin {
    @ModifyArg(method = "initChannel(Lio/netty/channel/Channel;)V", at = @At(
            value = "INVOKE",
            target = "io/netty/handler/timeout/ReadTimeoutHandler.<init> (I)V"
    ))
    private int timeoutout$getReadTimeout(int timeout) {
        return TimeOutOutConfig.READ_TIMEOUT_SECONDS.get();
    }
}
