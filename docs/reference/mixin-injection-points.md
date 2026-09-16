# Mixin 注入点与已验证 API 事实

> 本文原为 `docs/KNOWLEDGE.md`，按 §7 落点表迁入 `docs/reference/`。
> 记录 TimeOutOut NeoForge 移植中**验证过**的 API 与注入点事实（基线 1.21.1，已在 1.21.8 逐条复核，并已对 1.21.2 ~ 1.21.7 做静态扫描），供后续升级/多版本迭代复用。
> 设计决策与取舍见 [docs/design/multiversion-single-jar.md](../design/multiversion-single-jar.md)，测试门禁流程见 [docs/runbook/testing.md](../runbook/testing.md)，构建与 dev 环境前提、跨版本扫描方法见 [docs/troubleshooting.md](../troubleshooting.md)。

## 注入点

> 源码行号引用基于 **1.21.1** 的 `api-sources`（逐字核对）；`1.21.8` 的复核行号单列在每节末尾。
> **行号本身跨版本会漂移，不要把它当判据**——判据是类名/方法名/描述符/常量这些语义锚点。
> 1.21.2 ~ 1.21.7 已用官方 `client.jar` 的 `javap` 逐版本核对（见 [docs/troubleshooting.md](../troubleshooting.md) 的"单 jar 多版本的跨版本观察"）。

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

## 配置 API（ModConfigSpec）

- `ModConfig.Type.COMMON`，生成文件 `config/timeoutout-common.toml`。
- `ModConfigSpec.Builder.defineInRange(String, int, int, int)` → `IntValue`；`defineInRange(String, long, long, long)` → `LongValue`（签名已在 api-sources `ModConfigSpec.java` 第 770/787 行核对）。
- 主类构造器 `modContainer.registerConfig(ModConfig.Type.COMMON, TimeOutOutConfig.SPEC)`。
- 配置界面：客户端类 `@Mod(value = MODID, dist = Dist.CLIENT)` 的构造器里
  `container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new)`
  （`net.neoforged.neoforge.client.gui.ConfigurationScreen` / `IConfigScreenFactory`，接口方法 `createScreen(ModContainer, Screen)`）。
- 界面翻译键（`en_us.json` + `zh_cn.json`）：`<modid>.configuration.title`、`<modid>.configuration.section.<modid>.common.toml`、
  `<modid>.configuration.section.<modid>.common.toml.title`、`<modid>.configuration.<字段名>`。

## 版本配套速查

> 版本配套/协议号：1.21.1 → Parchment `2024.11.17`、协议号 **767**；1.21.8 → Parchment `2025.09.14`、协议号 **772**。
> MDG `2.0.146` / `2.0.147` 在两者间无实质差异，本分支用 `2.0.147`。
