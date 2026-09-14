# TimeOutOut NeoForge 移植设计思路

> 本文记录 TimeOutOutReforged 从 Fabric 1.21.10 移植到 NeoForge 1.21.1 的**设计决策与理由**；
> 验证过的 API 签名、注入点细节见 [KNOWLEDGE.md](KNOWLEDGE.md)。

## 1. 目标与范围

- 把工具链从 **Fabric（fabric-loom + Yarn + fabric-loader）** 换成 **NeoForge（ModDevGradle + Mojang 映射）**。
- 行为与 Fabric 版**逐字段等价**：三个配置项、默认值、超时语义完全一致。
- **不新增功能**（例如独立的 KeepAlive 踢人超时配置项），**不做多版本承诺**（本次只支持 1.21.1）。
- 基线：NeoForge `21.1.244`（工作区标准）、ModDevGradle `2.0.146`、Gradle `9.2.1`、Java 21 toolchain、Parchment `2024.11.17`。

## 2. 决策记录

| 决策点 | 结论 | 理由 |
|---|---|---|
| 目标 MC / 加载器 | 1.21.1 NeoForge | 工作区生态以 NeoForge 1.21.1 为主，本地 MDK 即 1.21.1 |
| 多版本策略 | 先 1.21.1 严格范围，不预埋条件 mixin | 注入点跨版本稳定性必须逐版本实测，先单版本做对更可控 |
| NeoForge 版本 | 21.1.244 | 与工作区其他项目一致、坑已被踩过；21.1.250 只是 patch 差异 |
| 配置格式 | ModConfigSpec COMMON + TOML + 配置界面 | 走 NeoForge 惯例（Q3/Q7）；代价是 Fabric 老 JSON 配置不能直接复用 |
| mod 版本 | 1.1.1 | 与 Fabric 1.0.5 区分；1.1.0 后的补丁发布 |
| 分支 / CI | `neoforge` 分支；CI 在 tag 推送时构建并把 jar 发布到 GitHub Release | CI 本来就是 JDK21 + `gradlew build`，加载器无关；补上 Release 发布后无需手工上传产物 |
| 作者 | Potatoboy9999, Megastary, pasze888 | 保留原作者 + 注明移植者 |
| 验证标准 | `build` + `runGameTestServer` + 裸 TCP 探针 | 握手时即创建登录监听器，无需真客户端即可用裸 socket 复现读/登录超时；KeepAlive 仍需真客户端 |
| KeepAlive 行为 | 只配置发送间隔，关闭清理超时保持原版（Q12=a） | 与 Fabric 版等价，不加新功能 |

## 3. 工具链设计

- **ModDevGradle 而非 NeoGradle**：工作区标准，MDK 同款；dev 环境直接使用 Mojang 官方名，**dev 名 == 运行时名**，mixin 不需要 refmap，也不需要单独声明 mixin 依赖（sponge-mixin 由 NeoForge 传递提供）。
- **generateModMetadata + `filteringCharset = 'UTF-8'`**：模板里的 `${...}` 占位符统一展开；显式 UTF-8 防止 GBK 平台字符集把 `neoforge.mods.toml` 写坏（FML 会报 "is not a valid mod file"）。
- **runs 配置**：client / server（`--nogui`）/ data / gameTestServer 沿用 MDK 默认，为后续调试留好入口。
- **元数据**：
  - `minecraft_version_range=[1.21.1]`、neoforge 依赖 `[21.1.244,)` —— 严格范围，与"不多版本"决策一致；
  - `[[mixins]]` 声明 `timeoutout.mixins.json`，由 FML 在加载时接管 mixin 生命周期；
  - 包名/group 保持上游 `us.potatoboy`（fork 移植不换命名空间）。

## 4. 代码结构设计

- **主类只做配置注册**：`TimeOutOut`（`@Mod`）构造器里 `registerConfig(ModConfig.Type.COMMON, SPEC)`，不掺业务逻辑。
- **客户端类独立**：`TimeOutOutClient` 用 `@Mod(value=..., dist=Dist.CLIENT)` 声明，只在客户端注册 `IConfigScreenFactory` + `ConfigurationScreen`。原因：`net.neoforged.neoforge.client.gui.*` 是客户端类，放公共代码里会让专用服务器在类加载/校验上冒风险。
- **配置即静态值对象**：`TimeOutOutConfig` 持有 `ModConfigSpec.IntValue/LongValue`；mixin 每次调用 `get()` 取当前值，天然支持配置文件热重载（新连接生效），且不引入额外状态。

## 5. Mixin 设计（核心）

### 5.1 目标映射（Yarn → Mojang 1.21.1）

| 功能 | Fabric（Yarn） | NeoForge（Mojang 1.21.1） |
|---|---|---|
| 读超时（客户端/服务端） | `ClientConnection$1` / `ServerNetworkIo$1` | `Connection$1` / `ServerConnectionListener$1` |
| KeepAlive 发送间隔 | `ServerCommonNetworkHandler.baseTick` | `ServerCommonPacketListenerImpl.keepConnectionAlive()` |
| 登录超时 | `ServerLoginNetworkHandler`（`loginTicks` / `Text`） | `ServerLoginPacketListenerImpl`（`tick` / `Component`） |

每个目标都对照 api-sources 源码 + `javap` 字节码**逐字核实**后才落笔（见 KNOWLEDGE.md）。

### 5.2 注入点选择原则

- 优先选**语义稳定的调用点/常量**，而不是某个版本特有的结构；
- 匿名内部类（`Connection$1` 等）只存在 `.class`，编号由编译器生成顺序决定——**跨版本脆弱**，因此本次严格锁定 1.21.1，不把 `$1` 的稳定性当作承诺。

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

- `defaultRequire=1`：1.21.1 上任何注入点缺失都会**启动即报错**，而不是静默失效——单版本下严格优于容错。
- `compatibilityLevel=JAVA_21`：mixin 类由 Java 21 toolchain 编译（class version 65），声明 `JAVA_17` 会产生 class-version 警告（不影响应用但属噪音），已对齐实际类版本。

## 6. 验证设计

| 层级 | 手段 | 覆盖 |
|---|---|---|
| 编译 | `./gradlew build` | 所有类/签名可编译、jar 打包、元数据展开正确 |
| 注入点 | `./gradlew runGameTestServer` | 四个注入点存在且可应用（`defaultRequire=1`；本地手动跑） |
| 加载 | `runServer` 启动到 `Done` | mod 被 FML 发现、mixin 配置被接受 |
| 行为·读超时 | 裸 TCP 连上不发字节 | `ChannelInitializerMixin`；实测 6.02s @ `readTimeoutSeconds=6` |
| 行为·登录超时 | 裸 TCP 只发握手包 | `ServerLoginPacketListenerImplMixin` 双注入；实测 3.03s @ 60 ticks、35.01s @ 700 ticks（见 TESTING.md 场景 4） |
| 行为·KeepAlive | `runClient` 完成登录后装死 | `ServerCommonPacketListenerImplMixin`；需真客户端，未自动验证 |

## 7. 已知偏差与限制

- **配置格式变化**：JSON → TOML，老 Fabric 配置不能直接复用（文件与字段格式都变）。
- **MC 版本降级**：1.21.10 → 1.21.1，仅为对齐 NeoForge 生态；原 Fabric 版保留在 `master` 分支。
- **README 与实现的出入已修正**：新 README 只描述实际行为。
- **多版本未验证**：原 Fabric 版一个 jar 能跑 1.20.2~1.21.8，但那是 **Yarn/intermediary 命名**下的稳定性；NeoForge 用 **Mojang 名**，跨版本必须逐版本核对（类名、方法名、匿名类编号、常量位置），核对清单见 KNOWLEDGE.md。
- **GameTest 未接入 CI**：`runGameTestServer` 已从 CI 回退（`8dd7cd8`），注入点门禁靠本地执行；读/登录超时的行为验证用 `docs/scripts/login_timeout_probe.py`（见 TESTING.md 场景 4），KeepAlive 仍需真客户端实测。

## 8. 未来扩展方向

1. **多版本单 jar**：前提是注入点在目标版本的 Mojang 名下稳定；流程 = 逐版本核对 → 决定 mixin 容错策略（soft require / 多 mixin 配置 / 条件插件）→ 放宽 `minecraft_version_range` → 每版本实测。
2. **独立 KeepAlive 踢人超时配置项**：需额外注入 `checkIfClosed`（或改用 NeoForge 事件），属于功能开发，不在本次移植范围。
3. **配置热重载提示**：ModConfigSpec 已支持运行期重载，新连接即生效；已建立的连接保持旧值直到重连。
