# 贡献指南

## 开始前

请先阅读 `README.md` 和 `docs/` 中的协议说明。公开仓库只接受可重建的
参考实现，不接受 AOSP Framework、厂商 HAL、签名材料、生产模型密钥或用户数据。

## 开发与验证

- 从仓库根目录执行 `bash tools/verify-local.sh`，在本机检查后端、契约和 Web。
- Android：在 `android-reference-app/` 执行 `./gradlew :app:testDebugUnitTest`；需要本机 Android SDK。
- PostgreSQL 集成测试使用显式提供的隔离测试库，连接配置见 `backend-reference/README.md`。
- 日常测试不启动 Docker；根目录 Dockerfile 和 `deploy/` Compose 仅用于后端交付部署。
- GitHub 公开发布前再运行公开边界扫描；内部 Gitee 开发提交不以该扫描代替功能测试。
- 修改协议时同步更新 Schema、Fixture 和说明文档。

接口类应说明职责、生命周期、线程约束、错误和扩展边界。关键流程使用分级
日志；不得记录令牌、密钥、原始音频或完整会议正文。

## 提交与合并请求

提交标题保留类型标记，描述使用中文，例如：
`Feature-[AIMeeting]: 增加参考存储端口`。每个提交保持小且可独立构建、测试和回滚。
合并请求需说明目的、影响目录、验证命令和未解决事项；界面变更附截图。
Gitee 为开发主仓，GitHub 仅由发布负责人审核后单向同步。
