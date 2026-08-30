# Minecraft Automation

让 Codex 或其他 MCP 客户端在获准的 Minecraft 服务器中辅助取料、搬运、补货、建造和界面操作。模组开发调试也是用途之一，当前重点是可靠地完成普通玩家任务。

基于 [Etoryx/mcpfabric](https://github.com/Etoryx/mcpfabric)，保留 MIT 许可证、原作者署名和 Git 历史。项目名为 Minecraft Automation；模组 ID、包名、配置名、产物标识及 MCP 连接名继续使用 `mcpfabric`。

## 当前状态

主要实测环境：Minecraft 26.2、Fabric Loader 0.19.3、Java 25，本地版本 `0.3.0-zedo.31+26.2`。保留上游其他版本配置，但当前 CI 只构建 26.2，不宣称其他版本已经兼容。

截至 2026-08-30，记录的实测包括：

- 精确放置、朝向、换料、蹲放、批量施工及状态复核。
- GUI 控件、容器槽位、搬运、条件等待和库存差异确认。
- 箱子、木桶、熔炉、铁砧重命名及部分工业机器流程。
- 内置导航的长距离、门和窄桥场景；Baritone 的真实地形、2/3 格跳跃和部分绕障场景。
- 安全隔离场内的生存铁轨复制机、TNT 复制机建造与运行。

这不是所有机器或模组的通用验收。尚待专门验证：村民交易、Baritone 窄桥移动实体推挤、最新退出收尾、任意自定义 GUI 及自主产线规划。

## 边界

- 先确认服规允许自动化。朋友服按普通玩家权限工作，不依赖发物品、传送或改规则。
- 管理员测试准备、创造编辑和生存操作分开描述；MCP 不会赋予远程服务器 OP 权限。
- 集成服务器权威回读与远程客户端缓存确认不同；不确定结果先核对，禁止盲目重发。
- 拆除、TNT、机器启停等需要明确范围；重要世界先备份。
- 内置导航与 Baritone 的安全行为不同，不承诺任意窄桥和动态实体环境绝对安全。
- 开发默认配置包含 `enableUnsafeJava=true` 等强能力。先读 [安全说明](../SECURITY.md)，按需关闭；Java scratch 拥有宿主进程权限，不是沙箱。

## 开发

从项目根目录执行：

```sh
./gradlew :26.2:build -x test
cd mcp-server
npm ci
npm run typecheck
npm run build
```

产物在 `versions/26.2/build/libs/`。不要同时安装上游和本项目 jar。[MCP 配置](../mcp-server/README.md)。

编译、自动测试和游戏实测必须分别报告；跳过测试的构建命令不代表测试通过。

## 整理约定

源码、文档和可复现构建配置进入 Git。存档、测试服、令牌、日志、第三方 jar、实验产物和恢复备份保存在忽略的 `local/` 或项目外。本机迁移历史和绝对路径不作为公开安装步骤。

[架构](ARCHITECTURE.md) · [GitHub 准备](GITHUB.md) · [发布](RELEASING.md) · [图标](ASSETS.md)
