# TimeOutOut NeoForge 移植设计思路

> 本文记录 TimeOutOutReforged 从 Fabric 移植到 NeoForge 的**设计决策与理由**。
> 最初基线 1.21.1；经 1.21.8 复核与中间版本静态扫描后，本分支改为**单 jar 覆盖 MC 1.21.1 ~ 1.21.8**，
> 编译基线回到 **1.21.1**。**四个注入点在 1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.8 全部未变，mixin 一行没改**；
> 验证过的 API 签名、注入点细节见 [KNOWLEDGE.md](KNOWLEDGE.md)。

## 1. 目标与范围

- 把工具链从 **Fabric（fabric-loom + Yarn + fabric-loader）** 换成 **NeoForge（ModDevGradle + Mojang 映射）**。
- 行为与 Fabric 版**逐字段等价**：三个配置项、默认值、超时语义完全一致。
- **不新增功能**（例如独立的 KeepAlive 踢人超时配置项）。
- **单 jar 多版本**：一个 jar 覆盖 MC `1.21.1` ~ `1.21.8`（含中间版本），不再每版本一条分支。
- 编译基线（本分支）：**NeoForge `21.1.244`**（范围下界）、ModDevGradle `2.0.147`、Gradle `9.2.1`、
  Java 21 toolchain、Parchment `2024.11.17`（对应 MC `1.21.1`）。
  > 基线取**下界**而非上界：对着最低版编译，就不会误用高版本才有的 API 而在低版本运行期炸 `NoSuchMethodError`。
- 元数据声明范围：MC `[1.21.1,1.21.9)`，NeoForge `[21.1,)`。

## 2. 决策记录

| 决策点 | 结论 | 理由 |
|---|---|---|
| 目标 MC / 加载器 | NeoForge，MC `1.21.1` ~ `1.21.8` 单 jar | 工作区生态以 NeoForge 1.21.x 为主；注入点在该区间内逐版本核对未变 |
| 多版本策略 | **单 jar 多版本**，元数据范围 `[1.21.1,1.21.9)`，不预埋条件 mixin | 四个注入点跨版本稳定性已用两层静态扫描 + 运行时实测验证（见 TESTING.md L3）；`require=1` 下注入点失配会立刻暴露，无需条件 mixin |
| 编译基线 | NeoForge `21.1.244`（= 范围下界）+ Parchment `1.21.1/2024.11.17` | 对着最低版编译可避免误用高版本 API；比"对着上界编译"更安全 |
| 配置格式 | ModConfigSpec COMMON + TOML + 配置界面 | 走 NeoForge 惯例（Q3/Q7）；代价是 Fabric 老 JSON 配置不能直接复用 |
| mod 版本 | 1.2.0 | 与 Fabric 1.0.5 区分；支持范围由单版本扩为 1.21.1 ~ 1.21.8，属能力变化，走 minor |
| 分支 / CI | `neoforge-1.21.1-1.21.8`（单分支覆盖整个范围）；CI 在 tag 推送时构建并把 jar 发布到 GitHub Release | 分支数由"每版本一条"收敛为一条；CI 就是 JDK21 + `gradlew build`，加载器无关 |
| 作者 | Potatoboy9999, Megastary, pasze888 | 保留原作者 + 注明移植者 |
| 验证标准 | `build` + `runServer` + 裸 TCP 探针 | 握手时即创建登录监听器，无需真客户端即可用裸 socket 复现读/登录超时；KeepAlive 仍需真客户端（1.21.8 起 GameTest 门禁下线，见 §6） |
| KeepAlive 行为 | 只配置发送间隔，关闭清理超时保持原版（Q12=a） | 与 Fabric 版等价，不加新功能 |

## 3. 工具链设计

- **ModDevGradle 而非 NeoGradle**：工作区标准，MDK 同款；dev 环境直接使用 Mojang 官方名，**dev 名 == 运行时名**，mixin 不需要 refmap，也不需要单独声明 mixin 依赖（sponge-mixin 由 NeoForge 传递提供）。
- **generateModMetadata + `filteringCharset = 'UTF-8'`**：模板里的 `${...}` 占位符统一展开；显式 UTF-8 防止 GBK 平台字符集把 `neoforge.mods.toml` 写坏（FML 会报 "is not a valid mod file"）。
- **runs 配置**：client / server（`--nogui`）/ data / gameTestServer 沿用 MDK 默认，为后续调试留好入口。
- **元数据**：
  - `minecraft_version_range=[1.21.1,1.21.9)`、neoforge 依赖 `[21.1,)` —— 覆盖整个支持区间；
    上界取 `1.21.9`（不含），因为 1.21.8 是该区间内最后一个版本；
  - NeoForge 下界写 `21.1` 而非 `21.1.244`：同一条 NeoForge 线内的修订版都应被接受，
    编译基线的精确版本只影响构建，不应成为运行期门槛；
  - `[[mixins]]` 声明 `timeoutout.mixins.json`，由 FML 在加载时接管 mixin 生命周期；
  - 包名/group 保持上游 `us.potatoboy`（fork 移植不换命名空间）。

## 4. 代码结构设计

- **主类只做配置注册**：`TimeOutOut`（`@Mod`）构造器里 `registerConfig(ModConfig.Type.COMMON, SPEC)`，不掺业务逻辑。
- **客户端类独立**：`TimeOutOutClient` 用 `@Mod(value=..., dist=Dist.CLIENT)` 声明，只在客户端注册 `IConfigScreenFactory` + `ConfigurationScreen`。原因：`net.neoforged.neoforge.client.gui.*` 是客户端类，放公共代码里会让专用服务器在类加载/校验上冒风险。
- **配置即静态值对象**：`TimeOutOutConfig` 持有 `ModConfigSpec.IntValue/LongValue`；mixin 每次调用 `get()` 取当前值，天然支持配置文件热重载（新连接生效），且不引入额外状态。

## 5. Mixin 设计（核心）

### 5.1 目标映射（Yarn → Mojang 1.21.1）

| 功能 | Fabric（Yarn） | NeoForge（Mojang，1.21.8 复核） |
|---|---|---|
| 读超时（客户端/服务端） | `ClientConnection$1` / `ServerNetworkIo$1` | `Connection$1` / `ServerConnectionListener$1` |
| KeepAlive 发送间隔 | `ServerCommonNetworkHandler.baseTick` | `ServerCommonPacketListenerImpl.keepConnectionAlive()` |
| 登录超时 | `ServerLoginNetworkHandler`（`loginTicks` / `Text`） | `ServerLoginPacketListenerImpl`（`tick` / `Component`） |

每个目标都对照 api-sources 源码 + `javap` 字节码**逐字核实**后才落笔（见 KNOWLEDGE.md）。
1.21.8 复核时四个注入点逐条复查，**结论是全部未变**，mixin 未作任何修改。

### 5.2 注入点选择原则

- 优先选**语义稳定的调用点/常量**，而不是某个版本特有的结构；
- 匿名内部类（`Connection$1` 等）只存在 `.class`，编号由编译器生成顺序决定——**理论上是跨版本最脆弱的一环**。
  本分支曾据此把 `minecraft_version_range` 严格锁在单一版本。放开为 `[1.21.1,1.21.9)` 的依据是：
  已用 `javap` 对 6 个中间版本的官方 `client.jar` 逐版本核对了 `$1` 编号与注入锚点，**全部未变**
  （方法与证据见 [KNOWLEDGE.md](KNOWLEDGE.md) 与 `temp/scan-1.21.2-1.21.7/REPORT.md`）。
  > 这不等于 `$1` 从此稳定：**每次抬高上界都必须重新核对**，`require=1` 会让失配在目标类加载时立刻报错。

### 5.3 KeepAlive 语义澄清（重要）

`keepConnectionAlive()` 里**唯一**的 `15000L` 同时控制两件事：

1. **发送间隔**：距上次发包 ≥ 15000ms 时发新 KeepAlive 包并标记 pending；
2. **等回复窗口**：若已 pending 又过了一个 15000ms，说明没收到回复，直接踢人。

因此改这一个常量 = 间隔和等待窗口一起变，**总 KeepAlive 超时 = 2 × 配置值**（默认 15s → 30s = vanilla）。

另外 `checkIfClosed()`（独立方法）里有第二个 `15000L`，是"连接已关闭后的清理超时"，与 KeepAlive 回复超时无关；Fabric 版从未配置它，移植保持不动（`@ModifyConstant` 只作用在 `keepConnectionAlive` 方法体内，天然不会误伤）。

> ⚠️ 原 README 声称 "KeepAlive timeout 默认 120s" 与代码默认 `keepAlivePacketIntervalSeconds=15` 不符（README 是照搬 RandomPatches 的介绍）；新 README 已改为如实描述"KeepAlive packet interval"。

### 5.4 登录超时的双注入组合

vanilla 在 `tick()` 内 `if (this.tick++ == 600) disconnect(...)`（600 ticks = 30s 硬编码）。移植保留原模组的设计：

- `@Redirect` 把 tick() 内 vanilla 的 `disconnect(Component)` 调用变成 **no-op**，抑制 30s 默认踢人；
- `@Inject(method="tick", at=@At("TAIL"))` 在 `tick >= 配置值`（默认 2400）时主动 `disconnect`，实现可配置的登录超时。

`@Redirect` 只作用于原方法指令，不会拦截注入器新增的 disconnect 调用（与原 Fabric 版已验证行为一致）。

### 5.5 严格性设计

- `defaultRequire=1`：任何注入点缺失都会在**目标类加载时报错**，而不是静默失效。
  单版本时代这条是"严格优于容错"；**多版本下单 jar 更需要它**——否则会退化成
  "某些 MC 版本上某个超时静默不生效"这类极难排查的问题。已验证的 5 个版本都不需要条件 mixin 或 soft require。
- `compatibilityLevel=JAVA_21`：mixin 类由 Java 21 toolchain 编译（class version 65），声明 `JAVA_17` 会产生 class-version 警告（不影响应用但属噪音），已对齐实际类版本。本区间内所有版本都是 Java 21，故单一值即可覆盖。

## 6. 验证设计

| 层级 | 手段 | 覆盖 |
|---|---|---|
| 编译 | `./gradlew build` | 所有类/签名可编译、jar 打包、元数据展开正确。**基线是 1.21.1**，所以这一关同时验证了"没有误用高版本 API" |
| 加载 | `runServer` 启动到 `Done` | mod 被 FML 发现、mixin 配置被接受；服务端起监听即加载 `ServerConnectionListener$1`，**服务端读超时注入点顺带被校验** |
| 行为·读超时 | 裸 TCP 连上不发字节 | `ChannelInitializerMixin`；1.21.8 实测 6.01s @ `readTimeoutSeconds=6` |
| 行为·登录超时 | 裸 TCP 只发握手包（`--protocol`） | `ServerLoginPacketListenerImplMixin` 双注入；1.21.8 实测 3.08s @ 60 ticks |
| 注入点·客户端读超时 / KeepAlive | 源码静态核对 + 真客户端进服 | `Connection$1` 的 `ReadTimeoutHandler(30)`、`keepConnectionAlive` 的 `15000L` 已在 1.21.8 源码确认；运行时需 `runClient` 才会加载目标类 |
| 多版本 | `javap` 扫官方 `client.jar`（1.21.1 ~ 1.21.8） | 四个注入点逐版本核对：匿名类编号、`ReadTimeoutHandler(30)`、`keepConnectionAlive` 的唯一 `15000L`、`tick`/`600`/`disconnect(Component)` **全部未变** |

> 1.21.8 起 MC 移除了注解版 GameTest 并改为数据驱动注册表（函数在 `minecraft:test_function`、
> 用例在 `minecraft:test_instance`）。该注册表是 built-in、在 mod 构造前就引导并冻结，mod 没有写入入口，
> 因此原先"用 `Class.forName` 强制加载四个目标类"的自动化门禁无法移植，已随本分支下线。

## 7. 已知偏差与限制

- **配置格式变化**：JSON → TOML，老 Fabric 配置不能直接复用（文件与字段格式都变）。
- **MC 版本降级**：1.21.10 → 1.21.1，仅为对齐 NeoForge 生态；原 Fabric 版保留在 `master` 分支。
- **README 与实现的出入已修正**：新 README 只描述实际行为。
- **多版本已验证到静态层**：原先"每版本一条分支"的理由是 NeoForge 用 **Mojang 名**、跨版本必须逐版本核对。
  该核对已完成（1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.8 四个注入点逐版本一致），故改为单 jar。
  > ⚠️ 仍有两点未覆盖：① `1.21.2` / `1.21.6` / `1.21.7` **没有 NeoForge 正式版**（只有 beta），
  > 元数据范围虽然包含它们，但实际不会有用户跑 NeoForge 正式版；
  > ② 除 1.21.1（编译基线）与 1.21.8（已跑过 `runServer` + 探针）外，
  > **中间版本只做了静态核对，没有运行时实测**。详见 TESTING.md L3。
- **GameTest 门禁已下线（1.21.8）**：MC 改为数据驱动测试注册表，`minecraft:test_function` 是 built-in、mod 无入口（实测 `Trying to access missing test function`），`TimeOutOutGameTests` 与 `empty.nbt` 已删除；注入点验证改为 `runServer` + 探针 + 真客户端（见 TESTING.md 的"注入点怎么验"）。

## 8. 未来扩展方向

1. ~~**多版本单 jar**~~ —— **本分支已实现**（`minecraft_version_range=[1.21.1,1.21.9)`）。
   后续若要抬高上界（例如纳入 1.21.9+），流程仍是：逐版本核对注入点 → 确认无 NeoForge 正式版缺口 →
   改 `minecraft_version_range` → 在新增版本上跑 `runServer` + 探针实测 → 再更新上界。
2. **独立 KeepAlive 踢人超时配置项**：需额外注入 `checkIfClosed`（或改用 NeoForge 事件），属于功能开发，不在本次移植范围。
3. **配置热重载提示**：ModConfigSpec 已支持运行期重载，新连接即生效；已建立的连接保持旧值直到重连。
4. **补中间版本的运行时实测**：目前 1.21.3 / 1.21.4 / 1.21.5 只有静态核对结论，
   若要正式宣称支持，应在这些版本上各跑一次 `runServer` + 探针。
