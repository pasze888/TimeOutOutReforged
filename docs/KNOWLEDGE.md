# TimeOutOut (NeoForge) KNOWLEDGE

本文件记录 TimeOutOut NeoForge 移植中**验证过**的 API 与踩坑（基线 1.21.1，已在 1.21.8 逐条复核，并已对 1.21.2 ~ 1.21.7 做静态扫描），供后续升级/多版本迭代复用。

## 构建工具链

- **本分支为单 jar 多版本**：编译基线 `ModDevGradle 2.0.147` + NeoForge `21.1.244` + Minecraft `1.21.1`
  （Mojang 官方映射）+ Parchment `2024.11.17` + Gradle `9.2.1` wrapper + Java 21 toolchain。
  元数据声明范围：MC `[1.21.1,1.21.9)`、neoforge `[21.1,)`。
  > **编译基线必须等于支持范围的下界**，不能取上界：对着最低版编译，才不会误用高版本才有的 API
  > 而让低版本在运行期炸 `NoSuchMethodError`。抬高上界不需要动基线。
  >
  > 历史参考：1.21.8 单版本时期的基数为 NeoForge `21.8.54` + Parchment `2025.09.14`。
- `generateModMetadata` 必须显式 `filteringCharset = 'UTF-8'`（模板含非 ASCII 内容时防 GBK 平台字符集写坏 `neoforge.mods.toml`，否则 FML 报 "is not a valid mod file"）。
- dev 运行时直接使用 Mojang 官方名（dev 名 == 运行时名），mixin **无需 refmap**，也无需单独声明 mixin 依赖（`net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7` 由 NeoForge 21.1.x 传递提供，`org.spongepowered.asm.mixin.*` 全套注解在编译 classpath 上）。

## Mixin 注入点

> 源码行号引用基于 **1.21.1** 的 `api-sources`（逐字核对）；`1.21.8` 的复核行号单列在每节末尾。
> **行号本身跨版本会漂移，不要把它当判据**——判据是类名/方法名/描述符/常量这些语义锚点。
> 1.21.2 ~ 1.21.7 已用官方 `client.jar` 的 `javap` 逐版本核对（见"多版本观察"）。

### 读超时（客户端 + 服务端）

- 目标类：`net/minecraft/network/Connection$1` 与 `net/minecraft/server/network/ServerConnectionListener$1`。
  匿名内部类没有独立 .java 源文件（仅 `Connection$1.class` / `ServerConnectionListener$1.class`），源码内嵌在 `Connection.java` / `ServerConnectionListener.java`。
- 方法：`initChannel(Lio/netty/channel/Channel;)V`。
- 注入：`@ModifyArg`，目标 `io/netty/handler/timeout/ReadTimeoutHandler.<init> (I)V`。
- 客户端参数是字面量 `30`；服务端参数来自 NeoForge 补丁字段 `ServerConnectionListener.READ_TIMEOUT`（读系统属性 `neoforge.readTimeout`，默认 30）——ModifyArg 对两者都生效（替换构造器实参）。
- 1.21.8 复核：`Connection.java:516` 仍是 `new ReadTimeoutHandler(30)`；`ServerConnectionListener.java:107` 仍是 `new ReadTimeoutHandler(READ_TIMEOUT)`，匿名类编号未变；运行时实测 `Mixing ChannelInitializerMixin ... into ServerConnectionListener$1`。
- 实测：裸 TCP 连上后不发任何字节，`readTimeoutSeconds=6` 时 1.21.1 为 6.02s、1.21.8 为 6.01s 被关闭。注意 `ReadTimeoutHandler` 在 netty 管道底层，若该值小于其它阶段超时，会先于登录超时触发。

### KeepAlive 发送间隔

- 目标类：`net.minecraft.server.network.ServerCommonPacketListenerImpl`。
- 方法：`keepConnectionAlive()`（Yarn 名 `baseTick`），唯一的 `15000L` 在 `i - keepAliveTime >= 15000L`。
- 注入：`@ModifyConstant(longValue = 15000L)`，替换为 `配置秒数 * 1000L`。
- ⚠️ 注意：`checkIfClosed(long)`（独立方法）里还有**另一个** `15000L`（连接已 close 后的清理超时）。`@ModifyConstant(method = "keepConnectionAlive")` 不会命中它；如果要改清理超时，必须再写一个针对 `checkIfClosed` 的注入。
- 语义：改这一个常量会同时改变"发送间隔"和"等回复 pending 窗口"，总 KeepAlive 超时 = 2 × 该值（默认 15s → 30s = vanilla）。原模组 README 声称"默认 120s"与实际代码默认值（15s）不符，README 描述是照搬 RandomPatches 的。
- 1.21.8 复核（`ServerCommonPacketListenerImpl.java`）：`:152` 为 `if (!this.isSingleplayerOwner() && i - this.keepAliveTime >= 15000L)`，与 1.21.1 的 `:137` **字面相同**；`keepConnectionAlive` 方法体内**仍只有这一个 `15000L`**，`checkIfClosed` 的第二个在 `:168`。同文件另有 `LATENCY_CHECK_INTERVAL`(:34)、`CLOSED_LISTENER_TIMEOUT`(:35) 两个 15000 常量（1.21.1 同样存在，位于 :31/:32），类型是 `int`，`@Constant(longValue = 15000L)` 不会命中。

### 登录超时

- 目标类：`net.minecraft.server.network.ServerLoginPacketListenerImpl`。
- 字段：`private int tick`（Mojang 名；Yarn 叫 `loginTicks`）。
- 方法：`tick()`；vanilla 逻辑为 `if (this.tick++ == 600) disconnect(Component.translatable("multiplayer.disconnect.slow_login"))`（600 ticks = 30s）。
- 注入组合：
  - `@Inject(method = "tick", at = @At("TAIL"))`：`tick >= 配置值` 时调 `disconnect(Component.translatable("multiplayer.disconnect.slow_login"))`；
  - `@Redirect(method = "tick", ...)`：把 tick() 内的 vanilla `disconnect` 调用变成 no-op，抑制 30s 默认踢人。
- `disconnect` 描述符：`(Lnet/minecraft/network/chat/Component;)V`；Yarn `Text` → Mojang `Component`。
- 实测要点：`handleIntention` 收到 intent=LOGIN 时立即 `beginLogin()` 并 `new ServerLoginPacketListenerImpl(...)`，该监听器是 `TickablePacketListener`，`Connection.tick()` 每 tick 调它；**所以只发握手包、不完成 Login Start/验证，登录计时照样跑**——裸 socket 即可复现超时，无需真客户端。
- `tick` 在 vanilla `tick++` **之后**判断，故 `loginTimeoutTicks=N` 对应第 N 次 tick ≈ `N/20` 秒（实测 60→3.03s、700→35.01s）。
- 跨过 600 tick（30s）是判断 `@Redirect` 是否抑制成功的唯一行为判据（实测 700 ticks 时 31.46s 仍连接、35.01s 才断）。
- 1.21.8 复核（`ServerLoginPacketListenerImpl.java`）：`private int tick`(:58)、`tick()`(:74)、`if (this.tick++ == 600)`(:84)、`disconnect(Component)`(:94) 全部未变；运行时探针（`--protocol 772`）在 3.08s 收到 `slow_login`。

## 配置（ModConfigSpec）

- `ModConfig.Type.COMMON`，生成文件 `config/timeoutout-common.toml`。
- `ModConfigSpec.Builder.defineInRange(String, int, int, int)` → `IntValue`；`defineInRange(String, long, long, long)` → `LongValue`（签名已在 api-sources `ModConfigSpec.java` 第 770/787 行核对）。
- 主类构造器 `modContainer.registerConfig(ModConfig.Type.COMMON, TimeOutOutConfig.SPEC)`。
- 配置界面：客户端类 `@Mod(value = MODID, dist = Dist.CLIENT)` 的构造器里
  `container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new)`
  （`net.neoforged.neoforge.client.gui.ConfigurationScreen` / `IConfigScreenFactory`，接口方法 `createScreen(ModContainer, Screen)`）。
- 界面翻译键（`en_us.json` + `zh_cn.json`）：`<modid>.configuration.title`、`<modid>.configuration.section.<modid>.common.toml`、
  `<modid>.configuration.section.<modid>.common.toml.title`、`<modid>.configuration.<字段名>`。

## 多版本观察

原模组（Fabric）一个 jar 可运行 1.20.2~1.21.8，但那是 **Yarn/intermediary 命名**下的稳定性；
NeoForge 运行时是 **Mojang 官方名**，跨版本稳定性必须逐版本核对（类名、方法名、匿名类 `$1` 编号、常量位置都可能变）。

**1.21.1 → 1.21.8 实测结论：四个注入点全部未变，mixin 一行没改。**
据此本分支由"每版本一条分支、严格锁定单一版本"改为**单 jar 多版本**：
`minecraft_version_range=[1.21.1,1.21.9)`、neoforge `[21.1,)`、编译基线回到 1.21.1、`defaultRequire=1` 保持不变。

**1.21.2 ~ 1.21.7 静态扫描结论（两层，互相独立）：**

| 层 | 手段 | 结论 |
|---|---|---|
| NeoForge patches | 逐版本取 `neoforged/NeoForge` 各 tag（`21.2`~`21.7`）的 `patches/`，比对四个目标文件 | 四个文件里**只有 3 个被 patch**，`ServerLoginPacketListenerImpl` 六个版本都**没被 patch**（纯 vanilla）；三个 patch 的改动内容逐版本一致，只有 hunk 行号随 vanilla 漂移 |
| vanilla 字节码 | 各版本官方 `client.jar` + `client_mappings`，`javap -p -c -constants` | 匿名类 `$1` 编号、`ReadTimeoutHandler(30)`、`keepConnectionAlive` 的唯一 `15000L`、`tick`/`600`/`disconnect(Component)` **全部未变** |

> 完整方法、逐项证据与 6 条盲区见工作区 `temp/scan-1.21.2-1.21.7/REPORT.md`。

**扫描时踩到的两个坑（复现方法时必读）：**

1. **Mojang 官方 `client.jar` 本身是混淆的。** 要核对注入点必须用 `client_mappings` 把
   `javap` 输出反向映射回 Mojang 名（NeoForge 运行时用的名字）；直接看字节码会全是 `wp`/`ath`/`a`/`e`。
2. **映射文件里混淆短名被大量重载**，**裸名不能当键**。例如
   `ServerCommonPacketListenerImpl` 里 `a` 同时是 `onDisconnect` / `onPacketError` / `handleKeepAlive` /
   `checkIfClosed` / `send` / `disconnect` / `createCookie` 等十几个方法，必须用**描述符**消歧，
   否则会把 `checkIfClosed` 解成 `onDisconnect`。另外映射描述符里的类型名是**混淆短名**（`Lxv;`），
   与 Mojang 名的拼写（点号 vs 斜杠）也要归一，否则匹配会**静默失败**。

**各版本 NeoForge 正式版有无（决定哪些版本值得作为目标）：**

| MC | NeoForge 线 | 正式版 |
|---|---|---|
| 1.21.1 | `21.1.x` | ✅ `21.1.244`（本分支编译基线） |
| 1.21.2 | `21.2.x` | ❌ 只有 2 个 beta |
| 1.21.3 | `21.3.x` | ✅ `21.3.97` |
| 1.21.4 | `21.4.x` | ✅ `21.4.157` |
| 1.21.5 | `21.5.x` | ✅ `21.5.98` |
| 1.21.6 | `21.6.x` | ❌ 只有 beta |
| 1.21.7 | `21.7.x` | ❌ 只有 beta |
| 1.21.8 | `21.8.x` | ✅ `21.8.54` |

> `1.21.2` / `1.21.6` / `1.21.7` 无正式版，FML 的范围语法又无法"排除区间内某几个版本"，
> 故元数据范围包含它们但不构成实际问题。

> 版本配套/协议号：1.21.1 → Parchment `2024.11.17`、协议号 **767**；1.21.8 → Parchment `2025.09.14`、协议号 **772**。
> MDG `2.0.146` / `2.0.147` 在两者间无实质差异，本分支用 `2.0.147`。

## GameTest（1.21.8 变更，本模组未使用）

1.21.8 移除了注解版 GameTest（`@GameTest` / `@GameTestHolder` / `@PrefixGameTestTemplate`），改为数据驱动：
测试函数进 `minecraft:test_function`、用例进 `minecraft:test_instance`。NeoForge 提供的入口是
`net.neoforged.neoforge.event.RegisterGameTestsEvent`（mod 总线），但它只暴露 `TEST_ENVIRONMENT` 与 `TEST_INSTANCE`。
`minecraft:test_function` 是 **built-in 注册表**（`BuiltInRegistries.TEST_FUNCTION`），由 `TestFunctionLoader` 在
`BuiltInRegistries` 引导时一次性填充并冻结——`TestFunctionLoader.registerLoader()` 在 mod 构造期调用已经太晚，
所以 `FunctionGameTestInstance` 路线不可用（实测运行期报 `Trying to access missing test function`）。
