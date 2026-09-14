package us.potatoboy.timeoutout.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import us.potatoboy.timeoutout.TimeOutOut;

/**
 * 注入点验证 GameTest：强制加载三个 mixin 的全部目标类。
 *
 * <p>NeoForge 的 mixin 在目标类加载时应用（dev 名 == 运行时名），
 * 且 mixins.json 声明了 {@code defaultRequire=1}——任何注入点缺失都会在类加载阶段抛错。
 * 平时这些类要等客户端连接才加载，这里用 {@link Class#forName} 在测试环境里提前触发，
 * 把"三个 mixin 的注入点是否正确"变成可自动化的门禁。
 */
@GameTestHolder(TimeOutOut.MODID)
@PrefixGameTestTemplate(false)
public class TimeOutOutGameTests {
    private static final String[] MIXIN_TARGETS = {
            // ChannelInitializerMixin（客户端读超时）
            "net.minecraft.network.Connection$1",
            // ChannelInitializerMixin（服务端读超时）
            "net.minecraft.server.network.ServerConnectionListener$1",
            // ServerCommonPacketListenerImplMixin（KeepAlive 发送间隔）
            "net.minecraft.server.network.ServerCommonPacketListenerImpl",
            // ServerLoginPacketListenerImplMixin（登录超时）
            "net.minecraft.server.network.ServerLoginPacketListenerImpl",
    };

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void mixinTargetsLoad(GameTestHelper helper) {
        try {
            for (String target : MIXIN_TARGETS) {
                Class.forName(target, true, TimeOutOutGameTests.class.getClassLoader());
            }
            helper.succeed();
        } catch (Throwable t) {
            helper.fail("Mixin 目标类加载失败: " + t);
        }
    }
}
