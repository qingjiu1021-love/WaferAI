# CNN 首版训练

训练数据准备完成后，先保持 Android Studio 和模拟器关闭，在 `E:\WaferAI\backend` 中完整试跑 1 轮：

```bat
.venv\Scripts\python.exe scripts\train_cnn.py --epochs 1
```

该命令真实遍历全部训练数据和验证数据，检查显卡训练、验证与模型保存；1 轮结果只代表初步训练状态。命令会显示 `Train 100/...` 等批次进度，完成后显示 `TRAINING DONE (validation only)`。首次使用 CUDA 卷积时初始化可能较慢。

默认输出：`E:\WaferAI\artifacts\cnn-runs\trial-日期-时间`。如运行报错，保留完整报错和对应目录，先处理原因。已有运行目录不会覆盖。

确认试跑正常后，后续可另行开始最多 15 轮的正式训练：

```bat
.venv\Scripts\python.exe scripts\train_cnn.py --epochs 15
```

每次命令都会从头训练并写入新的目录，不从 1 轮试跑的模型自动续训。验证集 macro-F1 连续 4 轮未改善时提前停止，连续 2 轮未改善时降低学习率。固定划分保持不变；测试集只在模型与参数确定后另行评估，当前脚本不对它进行预测或计算测试指标。

## 模型和资源设置

模型以 3 通道 one-hot 编码区分非芯片、合格、不合格区域。3 个卷积块的通道数为 16、32、64，包含 BatchNorm、ReLU 和池化，再保留 4×4 空间布局，经 128 单元全连接层和 Dropout 输出 9 类 logits。首版不使用数据增强，后续如需增强只能对训练集应用。

优化器采用 AdamW，初始学习率 0.001，权重衰减 0.0001。为应对类别不均衡，交叉熵使用训练集计数倒数平方根得到的类别权重，并归一化为均值 1；不使用验证或测试集计数设置权重。验证损失使用不加权的交叉熵，同时记录 macro-F1、balanced accuracy、总体 accuracy 和各类别 precision/recall/F1/support。

默认 batch size 为 128，CUDA 开启混合精度和梯度缩放。使用磁盘映射读取 uint8 矩阵，DataLoader 的 `num_workers=0`，避免 Windows 多进程复制数据挤占 16 GB 内存。随机种子固定为 42，并设置 cuDNN 确定性选项；不同硬件、驱动和库版本仍可能有数值差异，不保证跨环境逐位复现。

启动时核对输入数组、标签和固定划分的 SHA-256，训练日志保存数据准备报告和划分的哈希。App 的芯片良率仍由原始矩阵计算，不使用模型预测值或 64×64 矩阵替代。

## 输出与结果解释

- `config.json`：硬件、软件版本、训练参数、类别权重及数据哈希。
- `history.csv`：每轮训练/验证指标及真实耗时。
- `best.pt`：按验证集 macro-F1 保存的最佳模型参数与类别、预处理元数据。
- `last.pt`：最后完整一轮的模型参数。
- `best-validation.json`：最佳验证轮的总体及各类指标。
- `best-validation-confusion.csv`：对应验证轮的混淆矩阵，行为真实类别、列为预测类别。
- `training-summary.json`：完成轮数、最佳轮次、实际耗时以及 `test_evaluated: false`。

模型保存为 state_dict 和简单元数据，后续使用 `torch.load(..., weights_only=True)` 与相同架构加载。训练命令不会自动把模型接入 App，也不会把验证集成绩当作最终测试成绩。

当前真实划分中 `Near-full` 的验证样本为 20 张、测试样本为 19 张，少数类指标对个别样本十分敏感。论文应展示各类样本数、混淆矩阵和 macro-F1，不预设或编造准确率目标。清洗后子集和新的批次划分也不能直接与其他划分的论文成绩比较。

实现参考：PyTorch 2.10 的 [混合精度说明](https://docs.pytorch.org/docs/2.10/amp.html) 与 [DataLoader 说明](https://docs.pytorch.org/docs/2.10/data.html)。

## 当前训练记录与独立测试

2026-11-02 的正式训练运行保存于 `E:\WaferAI\artifacts\cnn-runs\cnn-20261102-212953`，完成 15 轮；最佳模型为第 15 轮，验证集 macro-F1 为 0.8462722415291832，验证集 accuracy 为 0.97053969301865。这些数字来自本地训练记录，仅属于验证集。

固定使用该目录的 `best.pt` 后，在 `E:\WaferAI\backend` 执行：

```bat
.venv\Scripts\python.exe scripts\evaluate_cnn.py --checkpoint "E:\WaferAI\artifacts\cnn-runs\cnn-20261102-212953\best.pt"
```

评估脚本核对模型、数据准备报告和固定划分的哈希，只将测试索引交给模型，使用与 CUDA 验证相同的混合精度设置。它不会训练模型或调整超参数。结果保存于模型目录内的 `test-evaluation`：

- `selection.json`：执行测试前记录选定模型、轮次、哈希和选择依据。
- `test-metrics.json`：独立测试总体指标、逐类指标、混淆矩阵、实际设备及版本。
- `test-confusion.csv`：混淆矩阵，行为真实类别，列为预测类别。
- `test-per-class.csv`：各类 precision、recall、F1、support。
- `test-report.md`：可阅读的测试结果和数据范围说明。

重复同一命令会读取已保存的匹配结果，不再次运行模型；已有结果属于其他模型或数据时不会覆盖。测试集是清洗后 WM-811K 的留出批次，不能将其指标直接视为实际产线效果。测试结果如实记录，后续不依据该测试集反复调参选模型。
