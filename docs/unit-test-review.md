# 如何阅读单元测试结果

本版示范：`ApiExceptionHandlerTest`。只修改测试展示与断言说明，不修改业务行为。

`scripts/test-review.ps1` 使用 UTF-8 **带 BOM** 编码，以兼容 Windows PowerShell 5.1 的中文解析；编辑时请保留 BOM。

## 在 IDEA 中运行

打开测试类，点击类或方法旁的绿色三角，选择 Run。运行窗口左侧的测试树显示中文场景及预期。
绿色表示该场景的全部断言通过；红色表示断言失败或执行异常；跳过不能视为通过。
控制台的 Spring 初始化日志不是测试结果。

例如“GET 访问 POST 接口：返回 405，Allow 为 POST”通过，表示响应状态与 Allow 头都已实际验证。
`detail` 当前只验证字符串类型，没有承诺固定框架文案。

想查看具体 HTTP 内容时，可临时在请求后加 `.andDo(print())`；不用给所有测试添加打印。
IDEA 直接运行不会生成 Maven Surefire 报告，下方脚本会重新运行选中的测试。

## 生成本次可读报告

在项目根目录的 PowerShell 执行：

```powershell
./scripts/test-review.ps1
```

默认使用 PATH 中的 Maven，执行异常映射的五个示范场景。指定 Maven 或测试：

```powershell
./scripts/test-review.ps1 -Maven ./mvnw.cmd
./scripts/test-review.ps1 -Module robot-scheduler-core -Test TaskExecutionEngineTest
```

控制台显示每项结果与中文预期；打开输出路径的 `index.html` 可以查看测试类、方法、耗时和失败原因。
`cases.csv` 可用 Excel 打开。没有中文名称的旧测试仍显示英文方法名，后续按模块逐步补齐。
原始输出保存在同一目录的 `maven.log`。

每次输出位于独立的 `target/test-review/<本次编号>`，只收集本轮更新的 Surefire XML，不混入历史结果。
Maven 构建失败也会报告非零退出码；没有结果时不会宣称测试通过。

模块统计复用团队的 `summarize-junit-reports.py`，需要 Python launcher `py`。
默认工具路径为 `D:\workspace\qc-robot\team-ai-rules\skills\testing\scripts\summarize-junit-reports.py`，
其他环境可用 `-SummaryScript` 指定；工具缺失时仍保留 HTML/CSV 明细并提示。

## 如何判断测试有价值

1. 先读场景和预期，再看断言是否验证了需求中的关键结果与副作用。
2. 不只判断“不报错”或“非空”，也检查状态码、点位、占用是否保留等明确规则。
3. 需要验证断言是否有效时，临时将 405 预期改成 200，运行后应失败，随后恢复。
   不提交故意失败的测试。本版交付验证也执行这一步。
4. 单测通过只证明已覆盖的规则；真实机器人、并发压力和业务全流程仍需相应集成验证。
