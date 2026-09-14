# TimeOutOut 测试手册

> 分层测试策略：**L1 自动化门禁**（每次改动必跑；目前只剩 `build`，由 CI 执行）→ **L2 行为验证**（发布前手动跑）→ **L3 多版本**（单 jar 覆盖 1.21.1 ~ 1.21.8 的核对与实测）。
> 本模组逻辑极薄（只有三个 mixin 返回配置值），风险集中在"mixin 注入点是否匹配目标 MC 版本"和"三个超时是否真的生效"，测试围绕这两点设计。
>
> **本分支为单 jar 多版本**：编译基线 `NeoForge 21.1.244` + MC `1.21.1`，
> 声明范围 `minecraft_version_range=[1.21.1,1.21.9)` / neoforge `[21.1,)`。新增目标版本时按 L3 流程走。

## L1：自动化门禁

| 命令 | 覆盖 | 说明 |
|---|---|---|
| `./gradlew build` | 编译、打包、元数据展开 | 所有签名可编译；产物 `build/libs/timeoutout-neoforge-*.jar`。CI 在 tag 推送 / 手动触发时都会跑 |

> **注入点门禁已下线（1.21.8 起）**：MC 1.21.8 移除了注解版 GameTest（`@GameTest` / `@GameTestHolder` /
> `@PrefixGameTestTemplate` 全部不存在），改成数据驱动——测试函数进 `minecraft:test_function`、
> 用例进 `minecraft:test_instance`。NeoForge 的 `RegisterGameTestsEvent` 只能注册 environment 与 instance，
> 而 `minecraft:test_function` 是 **built-in 注册表**、在 mod 构造之前就已引导并冻结，mod 没有入口，
> `FunctionGameTestInstance` 这条路线走不通（实测报 `Trying to access missing test function`）。
>
> 因此本分支不再有自动化注入点门禁：`runGameTestServer` 只会跑 vanilla 自带的 `minecraft:always_pass`。
> 原先的 `TimeOutOutGameTests` 与它依赖的 `data/timeoutout/structure/empty.nbt` 已随之下线。
>
> CI（`.github/workflows/build.yml`）当前只跑 `build`：`runGameTestServer` 曾在 CI 中接入（`0b7a588`）后再回退（`8dd7cd8`）。
>
> **CI 发布行为**：推送 tag 时，工作流除构建外还会用 `softprops/action-gh-release` 把 `build/libs/*.jar` 发布到 GitHub Release；`workflow_dispatch` 手动触发只构建、不发布。

### 注入点怎么验（多版本通用）

mixin 在**目标类加载时**应用，`defaultRequire=1` 也只在那一刻才会抛错——所以"启动没崩"证明不了什么，
必须让相应目标类真的被加载。不同操作覆盖的注入点完全不同（下表与 MC 版本无关，1.21.1 ~ 1.21.8 一致）：

| 操作 | 会加载的目标类 | 被校验的注入点 |
|---|---|---|
| 只启动到主菜单 | — | 无（只有 metadata / FML 加载） |
| 单机开一个世界 | `ServerConnectionListener$2` → `MemoryServerHandshakePacketListenerImpl` → `ServerLoginPacketListenerImpl` | **登录超时** |
| 玩家进世界后 | `ServerGamePacketListenerImpl`（继承 `ServerCommonPacketListenerImpl`） | **KeepAlive** |
| 单机「对局域网开放」 | `ServerConnectionListener$1` | **服务端读超时** |
| 客户端连多人服 | `Connection$1` | **客户端读超时** |
| 起独立服务端并连入 | 上述全部 | 全覆盖 |

实战里最高效的是 **`runServer` + 探针**（见 L2 场景 4）：服务端起监听时就会加载 `ServerConnectionListener$1`，
探针连一次即可触发 `ServerLoginPacketListenerImpl`。日志判据（dev 环境 DEBUG 级别）：

```
Mixing ChannelInitializerMixin ... into net.minecraft.server.network.ServerConnectionListener$1
Mixing ChannelInitializerMixin ... into net.minecraft.network.Connection$1
Mixing ServerCommonPacketListenerImplMixin ... into net.minecraft.server.network.ServerCommonPacketListenerImpl
Mixing ServerLoginPacketListenerImplMixin ... into net.minecraft.server.network.ServerLoginPacketListenerImpl
```

## L2：行为验证（发布前，手动）

### 准备：把配置调小加速

编辑 `run/config/timeoutout-common.toml`（dev 服）或 `config/timeoutout-common.toml`（正式服）：

```toml
readTimeoutSeconds = 3        # 原 120
loginTimeoutTicks = 100       # 原 2400（5 秒）
keepAlivePacketIntervalSeconds = 2  # 原 15
```

或启动后在游戏内 Mods → Time Out Out → Config 修改。**测完记得改回默认值**。

> ⚠️ 组合约束：测**登录超时**时必须满足 `readTimeoutSeconds > loginTimeoutTicks / 20`，否则 netty 的读超时会先掐线，看不到 `slow_login`（上面示例的 3 秒读超时只适合场景 1）。

### 场景 1：服务端读超时（可脚本化）

1. 启动 `./gradlew runServer`（offline 模式即可）。
2. 用原始 TCP 客户端连接后**不发任何字节**：

   ```python
   import socket, time
   s = socket.create_connection(("127.0.0.1", 25565), timeout=10)
   time.sleep(5)  # 超过 readTimeoutSeconds=3 不发数据
   try:
       data = s.recv(1)
       print("对端已关闭" if data == b"" else "收到数据: " + repr(data))
   except Exception as e:
       print("异常:", e)
   ```

3. **预期**：约 3 秒后服务器关闭该连接，脚本打印"对端已关闭"，服务器日志出现连接关闭记录。

### 场景 2：客户端读超时（手动）

1. 起一个"只 accept 不回复"的原始 TCP 监听，例如 `python -m http.server` 不适用，用：

   ```python
   import socket, time
   s = socket.socket()
   s.bind(("0.0.0.0", 25566))
   s.listen(1)
   conn, _ = s.accept()
   time.sleep(30)  # 不发送任何数据
   ```

2. `./gradlew runClient`，多人游戏连接 `127.0.0.1:25566`。
3. **预期**：约 3 秒后客户端报连接超时/连接丢失。

> ⚠️ 注意：**单机游戏（integrated server）不走这个 mixin**（本地连接用的是 `Connection$3`，不构造 `ReadTimeoutHandler`）；必须连独立服务器（含 localhost 的 runServer）才会走 `Connection$1`。

### 场景 3：KeepAlive 间隔与踢人超时（手动）

1. `./gradlew runServer` + `./gradlew runClient`，客户端完成登录。
2. **让客户端停止响应**：暂停客户端进程（如 `pssuspend`）、断开网络，或防火墙阻断；不要关游戏窗口。
3. **预期**（`keepAlivePacketIntervalSeconds=2`）：
   - 约 2s：服务器发出 KeepAlive 包（可用 Wireshark 抓包确认间隔）；
   - 约 4s（= 2 × 间隔）：服务器以 `disconnect.timeout` 踢人，客户端回到标题界面，服务器日志出现超时断开记录。
4. 语义提醒：**总 KeepAlive 超时 = 2 × 间隔**（发送间隔 + 等回复窗口），这是原版机制，不是 bug。

### 场景 4：登录超时（快速探针，推荐）

**原理**：服务端在收到握手包（intent=LOGIN）时即创建 `ServerLoginPacketListenerImpl` 并开始 tick，
所以**无需完成登录、连 Login Start 都不用发**，只发一个握手包然后保持沉默即可触发超时。

1. 把 `loginTimeoutTicks` 调到 `60`（3 秒）；注意 `readTimeoutSeconds` 必须大于它（默认 120 即可），否则先触发读超时。
2. `./gradlew runServer`（offline），等日志出现 `Done (…)`。
3. 运行探针：

   ```bash
   # 协议号必须与"当前 dev 环境跑的 MC 版本"一致；不一致时握手会被直接拒掉，
   # 表现为"立刻断开"——是假阳性，不是超时生效。
   # 本分支编译基线是 1.21.1 → 767；若切到 1.21.8 跑则用 772。
   python docs/scripts/login_timeout_probe.py 127.0.0.1 25565 --protocol 767
   ```

4. **预期**：约 `loginTimeoutTicks / 20` 秒后服务端关闭连接，输出形如
   `kick reason after 3.05s: {"translate":"multiplayer.disconnect.slow_login"}`，随后 `server closed the connection`。

> **验证"原版 600 tick 已被抑制"**：把 `loginTimeoutTicks` 设成**大于 600** 的值（如 `700` = 35 秒，比 1200 省一半时间）。30 秒时连接应仍然存活、到配置值才被踢；
> 若 30 秒就被踢，说明 `@Redirect` 抑制 vanilla disconnect 失效。这是该 mixin 最关键的回归点。
>
> 探针的时序同理可验证其它两个超时：只连 TCP 不发任何字节 → 触发 `readTimeoutSeconds`。

#### 探针原理（为什么只发握手包就够）

MC 包 = `VarInt 长度前缀` + `VarInt 包ID` + 负载。1.21.1 握手包（包ID `0x00`）的负载：

| 字段 | 值 | 字节 |
|---|---|---|
| protocol version (VarInt) | 767 | `ff 05` |
| server address (String) | `localhost` | `09` + `6c 6f 63 61 6c 68 6f 73 74` |
| server port (unsigned short, 大端) | 25565 | `63 dd` |
| intent (VarInt) | 2 = LOGIN | `02` |

完整帧：`10 00 ff 05 09 6c6f63616c686f7374 63dd 02`（`10` = 负载长度 16 字节）。

> 帧结构本身与 MC 版本无关（1.21.1 ~ 1.21.8 都是这个布局），**唯一随版本变的是 `protocol version` 的值**：
> 1.21.1 = 767（`ff 05`）、1.21.8 = 772（`84 06`）。探针的 `--protocol` 就是填这个字段。

关键在于 `ServerHandshakePacketListenerImpl.handleIntention()` 收到 intent=LOGIN 时立即 `beginLogin()`，
其中直接 `new ServerLoginPacketListenerImpl(server, connection, transferred)`；该监听器实现
`TickablePacketListener`，`Connection.tick()` 每 tick 都会调它的 `tick()`。
**所以握手包一到登录计时就开始了——不需要 Login Start，也不需要身份验证。**

断开回包同样是三层：

```
33 | 00 | 31 | {"translate":"multiplayer.disconnect.slow_login"}
51   0x00  49   JSON 断线原因
帧长  包ID  字符串长
```

#### 判读

| 观察 | 结论 |
|---|---|
| 断开时间 ≈ `loginTimeoutTicks / 20` 秒 | `@Inject(TAIL)` 按配置值踢人 |
| 断开原因是 `multiplayer.disconnect.slow_login` | 走的是 `disconnect(Component)` 路径 |
| **跨过 30.0s 仍连接** | 原版 600 tick 已被 `@Redirect` 抑制 |
| 只连 TCP 不发字节时约 `readTimeoutSeconds` 秒断开 | `ChannelInitializerMixin` 生效 |

> ⚠️ `readTimeoutSeconds` 必须大于登录超时时长。`ReadTimeoutHandler` 位于 netty 管道底层，只要连接上
> 超过该时间没读到数据就掐线——设小了会在登录超时之前先把连接关掉，永远看不到 `slow_login`。
>
> 看 `run/logs/latest.log` 而不是重定向的 stdout：gradle 把子进程 stdout 重定向到文件时是块缓冲的，
> `Disconnecting ...: Took too long to log in` 那行可能还没 flush。

#### 实测记录（2026-09-14，本地 dev 服）

| 配置 | 探针 | 实测 |
|---|---|---|
| `readTimeoutSeconds=6`、`loginTimeoutTicks=60` | 只发握手包 | 3.03s 收到 `slow_login` 后连接关闭 |
| 同上 | 只连 TCP 不发字节 | 6.02s EOF（读超时） |
| `readTimeoutSeconds=120`、`loginTimeoutTicks=700` | 只发握手包 | 31.46s 仍连接（600 tick 未触发）、35.01s 收到 `slow_login` |

### 场景 4b：登录超时（同名双端，真客户端）

离线服登录太快，正常情况不会卡登录；用**同名双端**制造"等待原玩家断开"状态：

1. `./gradlew runServer`（offline），客户端 A 以名字 `Test` 进服并保持在线。
2. 客户端 B 以**同名** `Test` 连接。
3. **预期**：B 停在登录阶段（服务器等待 A 断开，进入 `WAITING_FOR_DUPE_DISCONNECT`），约 `loginTimeoutTicks=100`（5 秒）后 B 收到 `multiplayer.disconnect.slow_login` 被踢，日志出现 slow_login 断开。

### 观察手段

- 服务器日志时间戳（`run/logs/latest.log`）——对比各断开时间点；
- Wireshark 抓 localhost 流量——直接看 KeepAlive 包间隔；
- `netstat -ano` 看连接状态变化。

## L3：多版本（单 jar 1.21.1 ~ 1.21.8）

**本分支用单 jar 覆盖 MC `1.21.1` ~ `1.21.8`。** 依据是四个注入点在该区间内逐版本核对未变。
核对用两层、互相独立（方法、证据与盲区见工作区 `temp/scan-1.21.2-1.21.7/REPORT.md`）：

| 层 | 手段 | 结论 |
|---|---|---|
| NeoForge patches | 逐版本取 `neoforged/NeoForge` 的 `patches/`，比对四个目标文件的 patch 内容 | 四个文件里只有 3 个被 patch，`ServerLoginPacketListenerImpl` **从未被 patch**；三个 patch 的改动内容逐版本一致，只有 hunk 行号漂移 |
| vanilla 字节码 | 下载各版本官方 `client.jar` + `client_mappings`，用 `javap -p -c -constants` 核对 | 匿名类 `$1` 编号、`ReadTimeoutHandler(30)`、`keepConnectionAlive` 的唯一 `15000L`、`tick`/`600`/`disconnect(Component)` **全部未变** |

### 各版本实测状态（重要，别把静态核对当实测）

| MC 版本 | NeoForge 正式版 | 核对 | 运行时实测 |
|---|---|---|---|
| 1.21.1 | ✅ `21.1.244`（＝编译基线） | ✅ | ❌ 未跑（`build` 通过只证明可编译） |
| 1.21.2 | ❌ 只有 beta | ✅ | — |
| 1.21.3 | ✅ `21.3.97` | ✅ | ❌ 未跑 |
| 1.21.4 | ✅ `21.4.157` | ✅ | ❌ 未跑 |
| 1.21.5 | ✅ `21.5.98` | ✅ | ❌ 未跑 |
| 1.21.6 | ❌ 只有 beta | ✅ | — |
| 1.21.7 | ❌ 只有 beta | ✅ | — |
| 1.21.8 | ✅ `21.8.54` | ✅ | ✅ `runServer` + 探针（读超时 6.01s、登录超时 3.08s） |

> `1.21.2` / `1.21.6` / `1.21.7` **没有 NeoForge 正式版**（`21.2.x` 只有 2 个 beta，
> `21.6.x` / `21.7.x` 各二十多个 beta、无 stable）。元数据范围包含它们，但不会有用户跑正式版；
> 若要精确排除，FML 的范围语法做不到"排除区间内的某几个版本"。

### 新增/抬高目标版本的流程

1. 用该版本的反编译源码或 `javap` 核对四个注入点（类名、方法名、匿名类 `$1` 编号、常量数量与位置）——清单见 [KNOWLEDGE.md](KNOWLEDGE.md)；
2. 确认该版本**有 NeoForge 正式版**（`neoforged.forgecdn.net` 的 `maven-metadata.xml`）；
3. 若**降低**下界：把 `minecraft_version` / `parchment_*` 一并降到新下界并重新 `build`
   （编译基线必须等于范围下界，见 DESIGN.md §1）；
4. 若**抬高**上界：改 `minecraft_version_range`，**不需要动编译基线**（基线始终是下界）；
5. 在新版本上起 `runServer` + 探针（L2 场景 4，**记得改 `--protocol`**），
   KeepAlive 需真客户端进服；日志里确认四条 `Mixing ...` 都出现；
6. 全部通过后再动 metadata 范围。**不要只用 `build` 通过就宣称支持某版本。**

> 本分支**未**放宽 mixin `require`：`defaultRequire=1` 保持不变。
> 已验证的 5 个正式版都不需要条件 mixin / soft require，失配时让它立刻报错更安全。

## 常见问题

- **服务端启动即崩、报 mixin 注入失败**：说明注入点与当前 MC 版本不符，属于移植必须修的硬错误，不要用 `require=0` 掩盖。
- **改了配置不生效**：ModConfigSpec 支持运行期重载，但**已建立的连接保持旧值**直到重连；新连接立即生效。
