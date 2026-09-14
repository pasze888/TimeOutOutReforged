# Time Out Out (NeoForge)

NeoForge 1.21.1 移植版，源自 [TimeOutOut (Fabric)](https://github.com/PotatoPresident/TimeOutOut) ——
从 [Random Patches](https://github.com/TheRandomLabs/RandomPatches) 独立出来的连接超时配置模组。

完整的配置说明可参考 [Random Patches README](https://github.com/TheRandomLabs/RandomPatches)。

## Features

Features without a specified side are server-sided.

### Connection timeouts

In vanilla Minecraft, the connection timeouts are hardcoded, and often not long enough for
slower computers or heavier modded instances. To counter this, TimeOutOut allows several
connection timeouts to be configured:

* The connection read timeout
    * Both client and server-sided
    * Raised to 120 seconds from the vanilla value of 30 seconds by default
* The login timeout
    * How long the server waits for a player to log in
    * Raised to 2400 ticks (120 seconds) from the vanilla value of 600 ticks (30 seconds) by default
* The KeepAlive packet interval
    * How often KeepAlive packets are sent to clients
    * Defaults to the vanilla value of 15 seconds

## Configuration

配置文件位于 `config/timeoutout-common.toml`（NeoForge ModConfigSpec，COMMON 类型）。
可以在游戏内 Mods 界面 → Time Out Out → Config 中修改，也可以直接编辑文件。

* `readTimeoutSeconds`（默认 `120`）：连接读超时（秒），客户端与服务端都生效。
* `loginTimeoutTicks`（默认 `2400`）：服务器等待玩家完成登录的时长（tick，20 tick = 1 秒）。
* `keepAlivePacketIntervalSeconds`（默认 `15`）：服务器向客户端发送 KeepAlive 包的间隔（秒）。

## Download

* GitHub Releases：https://github.com/pasze888/TimeOutOutReforged/releases
