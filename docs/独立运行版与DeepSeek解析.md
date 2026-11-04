# WaferAI 2.0：手机独立运行与 DeepSeek 解析

此版本的导入、晶圆图、芯片良率、CNN 识别、历史记录、批次统计和 PDF 报告全部由 Android 本地完成。只有 DeepSeek 解析需要手机联网和用户自己的 API 密钥，不需要电脑中转。

## 安装和首次体验

1. 将 `WaferAI-standalone.apk` 传到安卓手机，点击安装。安装包适用于 64 位 Android 8.0 / API 26 及以上设备，包含 arm64-v8a 和 x86_64。它仍为毕业设计 debug 演示包。
2. 打开 WaferAI，点击“载入演示晶圆”。内置的是手工构造的 9×9 示例，不是额外收集的生产数据。
3. 点击“识别缺陷类型”。模型在手机 CPU 上运行，不需要电脑、USB、Wi-Fi 或 API 密钥。
4. 查看晶圆图和良率；示例合格 42、不合格 7、有效芯片 49，良率 85.71%。模型得分是未经校准的分类倾向，不是识别正确率。
5. “历史记录”和“批次汇总”读取手机自己的 SQLite 数据，重启 App 后仍保留。“导出 PDF 报告”使用系统保存窗口选择保存位置。

如果之后用 USB 连接当前开发电脑，也可双击项目根目录的“安装独立版到手机.bat”。它只安装和打开本 App，不启动 FastAPI，不设置端口转发；安装完成即可拔线。不要使用旧版“连接手机.bat”作为 2.0 运行前提。

实际晶圆文件通过“选择 JSON 文件”导入。格式如下，0 为非芯片区域，1 为合格芯片，2 为不合格芯片：

```json
{"lot_code":"LOT-001","wafer_code":"W01","map":[[0,1,1],[1,2,0]]}
```

仅支持 UTF-8 JSON（可带 BOM），最大 2 MB，矩阵每边最多 512；不接受小数、布尔值、参差行或全 0 矩阵。重复编号与相同矩阵会打开已有记录；同一编号使用不同矩阵会提示冲突。良率从原始矩阵计算，非芯片区域不参与分母。

电脑端旧数据库不会自动出现在手机上。旧记录仍保留在电脑；可把对应 JSON 文件传到手机后重新导入。本版不删除电脑数据。手机卸载 App 或清除应用数据会移除本地记录，重要报告请导出到系统文档目录。

## 启用 DeepSeek 解析

1. 打开 App 首页右上角“AI 设置”。
2. 在 DeepSeek 平台申请自己的 API 密钥；可点击 App 内“打开 DeepSeek 密钥管理”。密钥在手机 App 内填写，不要发到聊天或写进源代码。
3. 选择 Flash 或 Pro，点击“保存 AI 设置”。当前接入的模型标识为 `deepseek-flash` 和 `deepseek-v4-pro`，依据 [DeepSeek 官方接口文档](https://api-docs.deepseek.com/) 配置。
4. 返回晶圆页面，先进行本地缺陷识别，再点击“DeepSeek 解析（联网）”。每次解析由用户主动发起，可能产生 DeepSeek API 费用；保存设置和打开历史记录不会自动调用在线模型。
5. 解析完成后，文字、模型名和保存时间会保存在手机。之后可离线打开；已有解析也可随 PDF 导出。点击“重新解析（联网）”会发起新的请求，不会在后台自动重试付费请求。

接口固定为 `https://api.deepseek.com/chat/completions`，使用 HTTPS Bearer 认证，关闭自动跳转，不绕过证书验证。非流式请求包含禁用思考模式的设置和 1536 token 输出上限，整体请求超时为 90 秒，页面可取消请求。接口定义参考 [官方 Chat Completions 文档](https://api-docs.deepseek.com/api/create-chat-completion/)。

密钥通过 Android Keystore 的 AES-GCM 加密后存入应用私有配置；密文配置从云备份及设备迁移备份中排除。安装包、PDF 和代码均不包含用户密钥；设置页不会回显已保存密钥，可单独清除。替换密钥框留空时保留原密钥。

## 解析内容与边界

发送的数据包括：有效芯片数、合格及不合格数量、良率、矩阵大小、CNN 类别与九类得分、归一化中心/中部/边缘统计以及 3×3 区域统计。不发送原始矩阵、文件名、批次号、晶圆编号或整套数据集。

区域由矩阵坐标归一化得到，不表示真实物理距离。提示词要求分别说明观测事实、模式含义、待验证原因和排查顺序；不把 none 类解释为全片合格，不把模型得分解释为正确率，不编造设备故障或确定工艺根因。在线模型的解释仍需结合电测、设备日志和真实工艺记录核实。

API 的密钥错误、余额不足、限流、服务异常、网络中断、输出不完整均会显示对应提示，不保存假结果。已保存的解析在重新解析失败后仍可保留查看。当前没有提供“规则模板冒充大模型解析”的离线替代内容。

## 本地实现

Kotlin / Jetpack Compose → ViewModel + StateFlow → ImportRepository → SQLite / ONNX Runtime；只有在线解析分支访问 DeepSeek。

- `data/local/WaferJson.kt`：JSON 校验、原始统计和包含矩阵尺寸的输入哈希。
- `data/local/LocalWaferStore.kt`：手机 SQLite，事务导入、去重、历史、汇总、预测及解析保存。
- `data/local/MobilePredictor.kt`：64×64 等比例最近邻缩放、居中补 0、三通道 one-hot 和本地 CNN 推理。
- `data/ai/`：密钥加密、只含汇总的解析证据、DeepSeek 请求与取消。
- `ui/settings/AiSettings*`：AI 设置；首页不再要求检查电脑服务。
- `data/report/WaferPdfReport.kt`：Android 原生 PDF，附带已有 AI 解析，长文字自动分页。

手机数据库名为 `waferai-local.db`，位于应用私有数据库目录。四张表的主要结构如下：

| 表 | 字段和约束 |
| --- | --- |
| lots | id INTEGER 主键；lot_code TEXT 非空唯一 |
| wafers | id INTEGER 主键；lot_id INTEGER 外键到 lots；wafer_code / filename / map_json / input_hash / created_at 为非空 TEXT；rows / cols / good_count / bad_count 为非空 INTEGER；唯一键 (lot_id, wafer_code) |
| predictions | id INTEGER 主键；wafer_id INTEGER 外键到 wafers；model_hash / result_json 为非空 TEXT；唯一键 (wafer_id, model_hash) |
| analyses | id INTEGER 主键；wafer_id / prediction_id INTEGER 分别外键到 wafers / predictions；model / content / created_at 为非空 TEXT |

另有 wafers(lot_id,id)、analyses(wafer_id,id) 索引。批次良率为总合格芯片数除以总有效芯片数，不平均单张百分比，不连接预测历史导致重复计数。SQLite 汇总使用 64 位整数，Android 芯片汇总字段为 Long。解析按晶圆及预测记录关联保存，避免出现在其他晶圆下。

## 模型转换和验证记录

沿用已冻结的 cnn-v1，不重新训练，也不依据测试集调参。PyTorch 模型转换为固定输入 `[1,3,64,64]`、opset 17 的 ONNX，模型文件 626,662 字节；ONNX Runtime Android 固定为 1.23.2。原始 checkpoint 的 SHA-256 为 `8bf91dab52a648d1c1c66158d7d03835b312dd07bea918ace00f6f457f5f382d`。移动模型另有独立文件哈希，运行时校验。

转换脚本为 `backend/scripts/export_mobile_model.py`，依赖见 `requirements-mobile-export.txt`。11 组固定输入（含真实样例、细长矩阵、不同宽高及取整情况）在 Windows 上比较原始 PyTorch 与 ONNX，最大概率绝对误差约 4.69×10^-7。Android 端进一步逐项核对预处理字节哈希、九类概率及类别，允许数值误差 10^-4，全部通过。这是转换一致性检查，不是新的手机端全测试集性能评估。原始独立测试报告保持不变。

本轮 16 项 JVM 单元测试通过（包含旧版地址测试及新增本地解析/预处理测试），7 项 Android 端侧测试通过，包含：本地导入/去重/冲突/加权汇总/重启保存、11 组模型一致性、密钥加密与清除、DeepSeek 请求结构及模拟响应、401/402/429/500/截断/取消处理，以及长解析 PDF 分页。

在电脑 FastAPI 后台关闭的情况下，模拟器实测内置示例和真实 WM811K-lot2 / W20 导入、Center 本地识别、重启恢复、批次汇总和 PDF 导出。W20 原始合格 2145、不合格 315、有效芯片 2460，良率 87.20%。已核对真实单页报告和 80 段、4 页的测试报告版面；测试报告的模拟解析未写入实际业务记录。

真实手机测试按用户选择留待之后完成。尚未填写用户真实 API 密钥，未发起 DeepSeek 真实付费解析，当前接口验证使用明确标记的模拟响应，不能当成真实服务效果。DeepSeek 的可用性、账号权限及实际解析内容需在手机填写密钥后进一步验证。

ONNX Runtime 及第三方许可证随 APK 打包在 assets/licenses，推理接入参考 [官方 Java 文档](https://onnxruntime.ai/docs/get-started/with-java.html)。WM-811K 原始许可、标签、类别不均衡及按批次划分注意事项仍见 `WM811K训练数据准备.md`，不因转换而改变。
