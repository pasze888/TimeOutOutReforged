# TimeOutOut 测试手册

> 分层测试策略：**L1 自动化门禁**（每次改动必跑；其中 `build` 由 CI 执行）→ **L2 行为验证**（发布前手动跑一次）→ **L3 多版本**（未来放宽版本时重复 L1）。
> 本模组逻辑极薄（只有三个 mixin 返回配置值），风险集中在"mixin 注入点是否匹配当前 MC 版本"和"三个超时是否真的生效"，测试围绕这两点设计。

## L1：自动化门禁

| 命令 | 覆盖 | 说明 |
|---|---|---|
| `./gradlew build` | 编译、打包、元数据展开 | 所有签名可编译；产物 `build/libs/timeoutout-*.jar`。CI 在 tag 推送 / 手动触发时都会跑 |
| `./gradlew runGameTestServer` | **三个 mixin 的注入点** | 测试类 `TimeOutOutGameTests` 强制加载 4 个目标类，触发 mixin 应用；`defaultRequire=1` 下任何注入点缺失都会使测试失败（退出码非 0）。**本地手动跑**，CI 中的该步骤已回退 |

GameTest 原理：NeoForge 的 mixin 在**目标类加载时**应用。KeepAlive 与登录超时的目标类平时要等客户端连接才加载，测试里用 `Class.forName` 在游戏测试服务器上提前加载，把"注入点是否正确"变成可在无人值守环境验证的门禁。

日志判据：`runGameTestServer` 输出应包含

```
Mixing ChannelInitializerMixin ... into net.minecraft.network.Connection$1
Mixing ChannelInitializerMixin ... into net.minecraft.server.network.ServerConnectionListener$1
Mixing ServerCommonPacketListenerImplMixin ... into net.minecraft.server.network.ServerCommonPacketListenerImpl
Mixing ServerLoginPacketListenerImplMixin ... into net.minecraft.server.network.ServerLoginPacketListenerImpl
All 1 required tests passed :)
```

> CI（`.github/workflows/build.yml`）当前只跑 `build`：`runGameTestServer` 曾在 CI 中接入（`0b7a588`）后又回退（`8dd7cd8`），注入点门禁目前需本地手动执行。
>
> **CI 发布行为**：推送 tag 时，工作流除构建外还会用 `softprops/action-gh-release` 把 `build/libs/*.jar` 发布到 GitHub Release；`workflow_dispatch` 手动触发只构建、不发布。

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
   python docs/scripts/login_timeout_probe.py 127.0.0.1 25565
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

## L3：多版本（未来放宽 `minecraft_version_range` 时）

每新增一个目标 MC 版本：

1. 用该版本的 api-sources/反编译源码核对四个注入点（类名、方法名、匿名类 `$1` 编号、常量数量与位置）——核对清单见 [KNOWLEDGE.md](KNOWLEDGE.md)；
2. 在该版本跑一遍 L1（`build` + `runGameTestServer`）；
3. 行为只抽查一次（L2 场景 1 与 4 即可覆盖大部分风险）；
4. 全部通过后再放宽 metadata 与 mixin `require`。

## 常见问题

- **GameTest 报 "Missing template"**：`src/main/resources/data/timeoutout/structure/empty.nbt` 缺失或路径不对；该文件是提交在仓库里的 3×3×3 空气结构模板，用 `git checkout -- src/main/resources/data/timeoutout/structure/empty.nbt` 恢复。
- **服务端启动即崩、报 mixin 注入失败**：说明注入点与当前 MC 版本不符，属于移植必须修的硬错误，不要用 `require=0` 掩盖。
- **改了配置不生效**：ModConfigSpec 支持运行期重载，但**已建立的连接保持旧值**直到重连；新连接立即生效。
