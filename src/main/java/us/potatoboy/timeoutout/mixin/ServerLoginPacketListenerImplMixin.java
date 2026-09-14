package us.potatoboy.timeoutout.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import us.potatoboy.timeoutout.TimeOutOutConfig;

@Mixin(ServerLoginPacketListenerImpl.class)
public final class ServerLoginPacketListenerImplMixin {
    @Shadow
    private int tick;

    @Inject(method = "tick", at = @At("TAIL"))
    private void timeoutout$tick(CallbackInfo info) {
        if (tick >= TimeOutOutConfig.LOGIN_TIMEOUT_TICKS.get()) {
            ((ServerLoginPacketListenerImpl) (Object) this).disconnect(
                    Component.translatable("multiplayer.disconnect.slow_login")
            );
        }
    }

    @Redirect(method = "tick", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;disconnect" +
                    "(Lnet/minecraft/network/chat/Component;)V"
    ))
    private void timeoutout$disconnect(ServerLoginPacketListenerImpl handler, Component reason) {
        // No-op: suppress the vanilla 600-tick disconnect, replaced by the TAIL inject.
    }
}
