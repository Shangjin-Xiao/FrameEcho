---
name: quality-gate
description: 本仓库写代码/改代码的质量门禁——任何 AI 代理或人在声称"完成"之前必须走完的验证流程,以及本项目高频踩坑清单。改动 Kotlin/Gradle/workflow 文件前必读。
---

# FrameEcho 代码质量门禁

本技能存在的原因:曾有 AI 代理提交了一批"已完成"的改动,实际上**一次编译都没跑过**——删掉了仍在使用的 import、在 try 块内声明变量却在 catch 里引用、在非 suspend 函数里调用 `currentCoroutineContext()`,并把交接文档里未做的事情标成"已完成"。返工成本高于重写。以下规则不可跳过。

## 铁律:没跑过就不算完成

1. **每一批改动结束后,必须实际验证通过**。验证命令:
   ```bash
   ./gradlew compileDebugKotlin compileReleaseKotlin testDebugUnitTest
   ```
   注意:当前这台开发机性能很差,构建要 3–5 分钟且拖慢整机——**优先推送分支让 GitHub CI 验证**(CI 会跑 lint + test + assembleDebug),仅在改动小且急需本地反馈时才本机编译。无论哪种方式,"看起来对"都不等于验证通过。
2. **报告结果时如实陈述**:测试失败就贴失败输出;跳过了某项就写"跳过";绝不在文档或汇报里把未做/未验证的事情标记为"已完成"。宁可写"改了但没跑通",不可写假完成。
3. **改文档(如 docs/MVP_HANDOFF.md)的状态标记之前**,用 grep 确认代码里真的有对应改动。

## 本项目高频踩坑清单(全部真实发生过)

### Kotlin
- **删 import 前先 grep 文件内用法**。IDE 不在场,靠肉眼判断"这个 import 没用了"极易出错。`booleanPreferencesKey`、`edit`、`preferencesDataStore`、扩展函数(`map`/`first`)尤其容易误删——扩展函数不 import 会报"Unresolved reference"。
- **catch/finally 里要引用的变量必须声明在 try 之外**。模式:
  ```kotlin
  var outputUri: Uri? = null
  try { outputUri = save(...) } 
  catch (e: CancellationException) { outputUri?.let { cleanup(it) }; throw e }
  ```
- **`currentCoroutineContext()` / `ensureActive()` 只能在 suspend 函数里调用**。给深层的阻塞循环加取消检查点时,要把整条调用链上的私有函数改成 `suspend`。
- **捕获宽泛 `Exception` 的地方,先单独 catch 并 rethrow `CancellationException`**,否则协程取消会被吞掉。
- 枚举增删条目后,搜索所有 `when (…)` 穷举点和 `entries` 遍历点;删枚举值时同步处理 DataStore 里可能残留的旧值(用 `runCatching { valueOf(name) }.getOrNull()` 容错)。

### 资源与多语言
- 增删 `strings.xml` 条目必须同步三个语言:`values/`、`values-zh-rCN/`、`values-ja/`。
- 删字符串前 grep 所有 `R.string.xxx` 引用;删代码后 grep 字符串是否成了孤儿。

### Gradle / CI
- GitHub Action 的版本号不要凭感觉写(`upload-artifact` 最新是 v4,不存在 v7);不确定就查该 action 的仓库。
- workflow 中引用的 gradle property(如 `-PVERSION_CODE`)必须与 `build.gradle.kts` 中的读取代码同一次提交落地。

## 改动纪律

- **小步提交心态**:一次改一个主题,改完立即编译。十个文件的"大批完成"最后一起编译,定位错误的成本是十倍。
- **删除"死代码"前必须证明它死了**:grep 整个 `app/src` 和 `core/`(排除自身),确认无生产引用、无测试引用,再删。
- **不引入新依赖来解决小问题**;不顺手重构与任务无关的代码。
- 涉及导出/媒体路径(`FrameExporter`、`FrameExtractor`)的行为改动,在 PR/汇报里注明"仅单元测试验证,未真机验证",因为这些路径的行为高度依赖设备编解码器。
