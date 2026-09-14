# TimeOutOut (NeoForge) KNOWLEDGE

本文件记录 TimeOutOut NeoForge 1.21.1 移植中**验证过**的 API 与踩坑，供后续升级/多版本迭代复用。

## 构建工具链

- ModDevGradle `2.0.146` + NeoForge `21.1.244` + Minecraft `1.21.1`（Mojang 官方映射）+ Parchment `2024.11.17` + Gradle `9.2.1` wrapper + Java 21 toolchain。
- `generateModMetadata` 必须显式 `filteringCharset = 'UTF-8'`（模板含非 ASCII 内容时防 GBK 平台字符集写坏 `neoforge.mods.toml`，否则 FML 报 "is not a valid mod file"）。
- dev 运行时直接使用 Mojang 官方名（dev 名 == 运行时名），mixin **无需 refmap**，也无需单独声明 mixin 依赖（`net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7` 由 NeoForge 21.1.x 传递提供，`org.spongepowered.asm.mixin.*` 全套注解在编译 classpath 上）。

## Mixin 注入点（1.21.1，已在 api-sources 逐字核对）

### 读超时（客户端 + 服务端）

- 目标类：`net/minecraft/network/Connection$1` 与 `net/minecraft/server/network/ServerConnectionListener$1`。
  匿名内部类没有独立 .java 源文件（仅 `Connection$1.class` / `ServerConnectionListener$1.class`），源码内嵌在 `Connection.java` / `ServerConnectionListener.java`。
- 方法：`initChannel(Lio/netty/channel/Channel;)V`。
- 注入：`@ModifyArg`，目标 `io/netty/handler/timeout/ReadTimeoutHandler.<init> (I)V`。
- 客户端参数是字面量 `30`；服务端参数来自 NeoForge 补丁字段 `ServerConnectionListener.READ_TIMEOUT`（读系统属性 `neoforge.readTimeout`，默认 30）——ModifyArg 对两者都生效（替换构造器实参）。

### KeepAlive 发送间隔

- 目标类：`net.minecraft.server.network.ServerCommonPacketListenerImpl`。
- 方法：`keepConnectionAlive()`（Yarn 名 `baseTick`），唯一的 `15000L` 在 `i - keepAliveTime >= 15000L`。
- 注入：`@ModifyConstant(longValue = 15000L)`，替换为 `配置秒数 * 1000L`。
- ⚠️ 注意：`checkIfClosed(long)`（独立方法）里还有**另一个** `15000L`（连接已 close 后的清理超时）。`@ModifyConstant(method = "keepConnectionAlive")` 不会命中它；如果要改清理超时，必须再写一个针对 `checkIfClosed` 的注入。
- 语义：改这一个常量会同时改变"发送间隔"和"等回复 pending 窗口"，总 KeepAlive 超时 = 2 × 该值（默认 15s → 30s = vanilla）。原模组 README 声称"默认 120s"与实际代码默认值（15s）不符，README 描述是照搬 RandomPatches 的。

### 登录超时

- 目标类：`net.minecraft.server.network.ServerLoginPacketListenerImpl`。
- 字段：`private int tick`（Mojang 名；Yarn 叫 `loginTicks`）。
- 方法：`tick()`；vanilla 逻辑为 `if (this.tick++ == 600) disconnect(Component.translatable("multiplayer.disconnect.slow_login"))`（600 ticks = 30s）。
- 注入组合：
  - `@Inject(method = "tick", at = @At("TAIL"))`：`tick >= 配置值` 时调 `disconnect(Component.translatable("multiplayer.disconnect.slow_login"))`；
  - `@Redirect(method = "tick", ...)`：把 tick() 内的 vanilla `disconnect` 调用变成 no-op，抑制 30s 默认踢人。
- `disconnect` 描述符：`(Lnet/minecraft/network/chat/Component;)V`；Yarn `Text` → Mojang `Component`。

## 配置（ModConfigSpec）

- `ModConfig.Type.COMMON`，生成文件 `config/timeoutout-common.toml`。
- `ModConfigSpec.Builder.defineInRange(String, int, int, int)` → `IntValue`；`defineInRange(String, long, long, long)` → `LongValue`（签名已在 api-sources `ModConfigSpec.java` 第 770/787 行核对）。
- 主类构造器 `modContainer.registerConfig(ModConfig.Type.COMMON, TimeOutOutConfig.SPEC)`。
- 配置界面：客户端类 `@Mod(value = MODID, dist = Dist.CLIENT)` 的构造器里
  `container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new)`
  （`net.neoforged.neoforge.client.gui.ConfigurationScreen` / `IConfigScreenFactory`，接口方法 `createScreen(ModContainer, Screen)`）。
- 界面翻译键（en_us.json）：`<modid>.configuration.title`、`<modid>.configuration.section.<modid>.common.toml`、
  `<modid>.configuration.section.<modid>.common.toml.title`、`<modid>.configuration.<字段名>`。

## 多版本观察（未验证，待后续）

原模组（Fabric）一个 jar 可运行 1.20.2~1.21.8，但那是 **Yarn/intermediary 命名**下的稳定性；
NeoForge 运行时是 **Mojang 官方名**，跨版本稳定性必须逐版本用该版本的 api-sources/反编译源码核对
（类名、方法名、匿名类 `$1` 编号、常量位置都可能变）后才能放宽 `minecraft_version_range` 与 mixin `require`。
当前保持 `minecraft_version_range=[1.21.1]` + `defaultRequire=1`。
