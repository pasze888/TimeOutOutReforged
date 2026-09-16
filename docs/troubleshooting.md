# 环境 / 构建 / 工具链坑

> 按工作区 `AGENTS.md` §7 落点表：环境、构建、运行、工具链坑归本文。
> 已验证的 API 与注入点事实见 [docs/reference/mixin-injection-points.md](reference/mixin-injection-points.md)，
> 设计取舍见 [docs/design/multiversion-single-jar.md](design/multiversion-single-jar.md)，
> 测试门禁与多版本验证流程见 [docs/runbook/testing.md](runbook/testing.md)。

## 编译与 dev 环境前提

- **本分支为单 jar 多版本**：编译基线 `ModDevGradle 2.0.147` + NeoForge `21.1.244` + Minecraft `1.21.1`
  （Mojang 官方映射）+ Parchment `2024.11.17` + Gradle `9.2.1` wrapper + Java 21 toolchain。
  元数据声明范围：MC `[1.21.1,1.21.9)`、neoforge `[21.1,)`。
  > **编译基线必须等于支持范围的下界**，不能取上界：对着最低版编译，才不会误用高版本才有的 API
  > 而让低版本在运行期炸 `NoSuchMethodError`。抬高上界不需要动基线。
  >
  > 历史参考：1.21.8 单版本时期的基数为 NeoForge `21.8.54` + Parchment `2025.09.14`。
- `generateModMetadata` 必须显式 `filteringCharset = 'UTF-8'`（模板含非 ASCII 内容时防 GBK 平台字符集写坏 `neoforge.mods.toml`，否则 FML 报 "is not a valid mod file"）。
- dev 运行时直接使用 Mojang 官方名（dev 名 == 运行时名），mixin **无需 refmap**，也无需单独声明 mixin 依赖（`net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7` 由 NeoForge 21.1.x 传递提供，`org.spongepowered.asm.mixin.*` 全套注解在编译 classpath 上）。

## 单 jar 多版本的跨版本观察

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

## GameTest（1.21.8 变更，本模组未使用）

1.21.8 移除了注解版 GameTest（`@GameTest` / `@GameTestHolder` / `@PrefixGameTestTemplate`），改为数据驱动：
测试函数进 `minecraft:test_function`、用例进 `minecraft:test_instance`。NeoForge 提供的入口是
`net.neoforged.neoforge.event.RegisterGameTestsEvent`（mod 总线），但它只暴露 `TEST_ENVIRONMENT` 与 `TEST_INSTANCE`。
`minecraft:test_function` 是 **built-in 注册表**（`BuiltInRegistries.TEST_FUNCTION`），由 `TestFunctionLoader` 在
`BuiltInRegistries` 引导时一次性填充并冻结——`TestFunctionLoader.registerLoader()` 在 mod 构造期调用已经太晚，
所以 `FunctionGameTestInstance` 路线不可用（实测运行期报 `Trying to access missing test function`）。
