# 如何为 Tesla Dashboard 贡献

[English](CONTRIBUTING.md)

感谢您对 Tesla Dashboard 的贡献兴趣！本文档为贡献者提供指南和信息。

## 行为准则

请在贡献前阅读我们的 [行为准则](CODE_OF_CONDUCT_zh.md)。

## 如何贡献

### 报告 Bug

1. 检查 [现有 issues](https://github.com/TimHuang336699/tesla-dashboard/issues) 以避免重复
2. 使用 **Bug Report** 模板创建新 issue
3. 尽可能包含详细信息

### 建议功能

1. 检查 [现有 issues](https://github.com/TimHuang336699/tesla-dashboard/issues) 中的类似建议
2. 使用 **Feature Request** 模板创建新 issue
3. 说明使用场景和收益

### Pull Requests

1. Fork 仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 进行修改
4. 运行测试 (`./gradlew test`)
5. 运行 lint (`./gradlew lint`)
6. 提交更改 (`git commit -m 'Add amazing feature'`)
7. 推送到分支 (`git push origin feature/amazing-feature`)
8. 创建 Pull Request

## 开发环境搭建

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17+
- Android SDK 34

### 构建

```bash
# 克隆仓库
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# 构建调试 APK
./gradlew assembleDebug

# 运行测试
./gradlew test

# 运行 lint
./gradlew lint
```

## 代码风格

- 遵循 [Kotlin 编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用有意义的变量和函数名
- 为公共 API 添加 KDoc 注释
- 保持函数专注且简洁

## 提交信息

- 使用现在时态（"Add feature" 而非 "Added feature"）
- 使用祈使语气（"Move cursor to..." 而非 "Moves cursor to..."）
- 第一行保持在 72 字符以内
- 引用 issues 和 pull requests

## 测试

- 为新业务逻辑编写单元测试
- 为新 UI 组件编写 UI 测试
- 确保所有测试通过后再提交 PR

## 许可

通过贡献，您同意您的贡献将在 [GPL-3.0 许可证](LICENSE) 下授权。

## 问题？

如有任何关于贡献的问题，请随时打开 issue 咨询。
