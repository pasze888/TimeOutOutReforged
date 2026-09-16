# 文档落点规范：未完成项

依据工作区根 `AGENTS.md` §7（文档落点与协作规范）。2026-09-16 已完成本轮结构迁移
（`docs/KNOWLEDGE.md` → `docs/reference/mixin-injection-points.md` + `docs/troubleshooting.md`，
`docs/DESIGN.md` → `docs/design/multiversion-single-jar.md`，`docs/TESTING.md` → `docs/runbook/testing.md`），
以下是尚未处理的部分。

## 待办

- **补中文 README**：现只有英文 `README.md`，规范要求配 `README.zh-CN.md` 并保持两份同步，
  顶部语言切换写 `[English](README.md) | [简体中文](README.zh-CN.md)`。需要翻译，属于 README
  正文改动，须先向用户提出修改请求并确认。
- **`docs/scripts/login_timeout_probe.py` 的落点**：目前作为工具脚本留在 `docs/scripts/`，
  §7 落点表未覆盖脚本目录，暂按原样保留，待确认是否迁到 `scripts/`。

## 依据

- §7.3：`README.md` 为英文源，`README.zh-CN.md` 为中文同步——改一必同步另一。
