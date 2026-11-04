# CNN 接口与结果保存

当前模型为 `cnn-v1`，来自正式运行 `cnn-20261102-212953` 的第 15 轮最佳模型。原始测试结果：24,219 个样本，accuracy 0.9684545191791568、macro-F1 0.8344997140616158、balanced accuracy 0.8482776737762688。完整逐类结果保存在运行目录的 `test-evaluation`，不能仅凭总体 accuracy 表示所有类别均有同等表现。

模型文件已复制到 `E:\WaferAI\artifacts\serving\cnn-v1`。模型 SHA-256：`8bf91dab52a648d1c1c66158d7d03835b312dd07bea918ace00f6f457f5f382d`。`model-info.json` 保存来源、独立测试结果及模型哈希；服务首次识别时核对文件并加载，原始训练目录保持不变。

## API

`POST /api/v1/wafers/{wafer_id}/predictions`：识别已导入的晶圆，无请求体。首次识别返回 201；同一晶圆、同一模型、同一原始矩阵再次请求返回 200 和 `already_predicted: true`。

响应包含 `prediction`：记录编号、晶圆编号、模型版本及哈希、预处理版本、英文和中文类别、最高模型得分、九类得分、计算耗时、设备及保存时间。`score_type` 固定为 `softmax_uncalibrated`。

`GET /api/v1/wafers/{wafer_id}/predictions/latest`：读取这张晶圆最后保存的识别结果。首次未识别时返回 404，App 保留手动识别按钮。

错误结构沿用 `{"error":{"code":"...","message":"..."}}`。晶圆不存在为 404、原始文件与记录不一致为 409、不能生成模型输入为 422、模型未准备好为 503。基础健康检查只表示 API 服务可用，模型状态由识别请求单独验证。

## 计算与显示

后端复用训练时的 64×64 最近邻、等比例补 0 预处理及三通道 one-hot 编码。服务使用 CPU、float32、2 个计算线程；独立测试记录的执行环境为 RTX 4060/CUDA 混合精度，保留该信息，不把两种数值环境描述为逐位一致。

App 显示“模型得分”，其值是这张晶圆的 softmax 输出，未经概率校准，不是模型的测试准确率。接近 1 的值显示为 `>99.99%`。`none` 显示“无特定缺陷模式”，不表示芯片良率为 100%。原始矩阵和良率在识别过程中不修改。

`inference_ms` 只统计预处理和模型计算，不包含首次模型加载、数据库访问及网络传输。读取缓存时返回的是原记录的计算耗时，不应作为本次网络请求耗时或吞吐量指标。

## 新增 SQLite 表

`predictions` 与 `wafers` 为一对多关系：一张晶圆可以保存不同模型版本的结果。

| 字段 | SQLAlchemy 类型 | 约束或用途 |
|---|---|---|
| id | INTEGER | 主键 |
| wafer_id | INTEGER | 外键 wafers.id，ON DELETE RESTRICT，单列索引 |
| model_version | VARCHAR(64) | 展示模型版本 |
| model_sha256 | VARCHAR(64) | 确定模型文件身份 |
| preprocessing_id | VARCHAR(100) | 预处理版本 |
| input_sha256 | VARCHAR(64) | 原始矩阵维度与 uint8 内容哈希 |
| label | VARCHAR(30) | 预测类别 |
| score | FLOAT | 最高模型得分，CHECK 0～1 |
| scores | JSON | 九类得分，SQLite 中由 SQLAlchemy 编解码 JSON |
| inference_ms | FLOAT | 计算耗时，CHECK >=0 |
| device | VARCHAR(20) | 当前为 cpu |
| created_at | VARCHAR(40) | UTC ISO 时间 |

以上字段均非空。`wafer_id + model_sha256 + input_sha256` 为联合唯一约束，避免重复保存；对应的唯一约束也提供查询索引。新表通过 `create_all` 创建，不删除或重建原来的三张表。接入前已使用 SQLite backup API 生成一致性备份：`storage\backups\before-cnn-20261102-215211.db`。

## 已完成的验证

项目 31 项自动检查通过，覆盖数据去重、批次隔离、指标计算、模型加载、识别缓存、并发去重、失败不写入、文件变更检测及良率保持不变。测试中的模拟预测器和随机权重只用于功能检查，不作为模型成绩。

真实 HTTP 验证使用 WM811K-lot2 / W20，首次识别返回 201，重复识别返回 200，预测 Center；模型得分约 0.99999058，原始良率仍为 2145/2460。Android 新版构建成功，已在 Pixel_2 模拟器中通过文件选择、结果恢复和按钮调用接口检查。该样例用于功能联调，模型总体成绩仍引用前述独立测试集。

## 手动演示

1. 电脑上后端未启动时，双击 `E:\WaferAI\backend\start-backend.bat`。如果健康检查已返回 ok，可直接继续。
2. Android Studio 打开 `E:\WaferAI\android`，选择 Pixel_2 并运行 app。
3. 导入模拟器下载目录里的 `wm811k-center-001.json`。
4. 点击“识别缺陷类型”。已识别过的晶圆会自动恢复保存的结果，点击按钮也可再次获取。
5. 在识别卡片查看类别、模型得分和模型版本，向下滚动查看原始晶圆图及良率。

当前 App 使用 Android 模拟器访问电脑的 `10.0.2.2:8000`；真机联网需要另行配置地址。识别发生在电脑后端，安装包内没有打包 PyTorch 或原始数据集。App 已支持原生 PDF 报告导出，详见 `PDF报告导出说明.md`。
