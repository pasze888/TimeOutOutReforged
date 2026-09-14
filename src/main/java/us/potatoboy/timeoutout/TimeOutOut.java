package us.potatoboy.timeoutout;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(TimeOutOut.MODID)
public class TimeOutOut {
    public static final String MODID = "timeoutout";

    public TimeOutOut(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, TimeOutOutConfig.SPEC);
    }
}
