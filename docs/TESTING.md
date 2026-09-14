# TimeOutOut 测试手册

> 分层测试策略：**L1 自动化门禁**（每次改动/CI 必跑）→ **L2 行为验证**（发布前手动跑一次）→ **L3 多版本**（未来放宽版本时重复 L1）。
> 本模组逻辑极薄（只有三个 mixin 返回配置值），风险集中在"mixin 注入点是否匹配当前 MC 版本"和"三个超时是否真的生效"，测试围绕这两点设计。

## L1：自动化门禁（CI 已配置）

| 命令 | 覆盖 | 说明 |
|---|---|---|
| `./gradlew build` | 编译、打包、元数据展开 | 所有签名可编译；产物 `build/libs/timeoutout-*.jar` |
| `./gradlew runGameTestServer` | **三个 mixin 的注入点** | 测试类 `TimeOutOutGameTests` 强制加载 4 个目标类，触发 mixin 应用；`defaultRequire=1` 下任何注入点缺失都会使测试失败（退出码非 0） |

GameTest 原理：NeoForge 的 mixin 在**目标类加载时**应用。KeepAlive 与登录超时的目标类平时要等客户端连接才加载，测试里用 `Class.forName` 在游戏测试服务器上提前加载，把"注入点是否正确"变成可在无人值守环境验证的门禁。

日志判据：`runGameTestServer` 输出应包含

```
Mixing ChannelInitializerMixin ... into net.minecraft.network.Connection$1
Mixing ChannelInitializerMixin ... into net.minecraft.server.network.ServerConnectionListener$1
Mixing ServerCommonPacketListenerImplMixin ... into net.minecraft.server.network.ServerCommonPacketListenerImpl
Mixing ServerLoginPacketListenerImplMixin ... into net.minecraft.server.network.ServerLoginPacketListenerImpl
All 1 required tests passed :)
```

CI（`.github/workflows/build.yml`）已包含 `runGameTestServer` 步骤，测试失败即 CI 红。

## L2：行为验证（发布前，手动）

### 准备：把配置调小加速

编辑 `run/config/timeoutout-common.toml`（dev 服）或 `config/timeoutout-common.toml`（正式服）：

```toml
readTimeoutSeconds = 3        # 原 120
loginTimeoutTicks = 100       # 原 2400（5 秒）
keepAlivePacketIntervalSeconds = 2  # 原 15
```

或启动后在游戏内 Mods → Time Out Out → Config 修改。**测完记得改回默认值**。

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

### 场景 4：登录超时（手动，离线服）

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
3. 行为只抽查一次（L2 场景 1 与 3 即可覆盖大部分风险）；
4. 全部通过后再放宽 metadata 与 mixin `require`。

## 常见问题

- **GameTest 报 "Missing template"**：`src/main/resources/data/timeoutout/structure/empty.nbt` 缺失或路径不对；该文件是提交在仓库里的 3×3×3 空气结构模板，用 `git checkout -- src/main/resources/data/timeoutout/structure/empty.nbt` 恢复。
- **服务端启动即崩、报 mixin 注入失败**：说明注入点与当前 MC 版本不符，属于移植必须修的硬错误，不要用 `require=0` 掩盖。
- **改了配置不生效**：ModConfigSpec 支持运行期重载，但**已建立的连接保持旧值**直到重连；新连接立即生效。
