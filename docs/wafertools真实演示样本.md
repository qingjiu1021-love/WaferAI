# wafertools 真实演示样本

4.6.0 在首页「载入演示晶圆」增加真实样本选择器：27 张真实样本（9 类各 3 张）和原来的 3 张人工演示。支持搜索类别、批次和晶圆编号，按类别筛选。

## 固定来源

- [真实数据交互页](https://wafertools.github.io/wafermap/examples/real-data.html)
- [固定版本数据](https://github.com/wafertools/wafermap/blob/79f0c202f162c1c62fc3d027f6a88aef8b62ffe0/docs/examples/wm811k-sample.json)
- 原文件 SHA-256：fec21cd1fadea982a8171b6c6a74c3b86cc7179dedc87fd0ed80211d172a2b7d
- [上游转换说明](https://github.com/wafertools/wafermap/blob/79f0c202f162c1c62fc3d027f6a88aef8b62ffe0/scripts/convert-wm811k.py)

原始来源为 WM-811K，非人工重新绘图。将中心化坐标反向还原为原始二维矩阵，0 为非芯片，1 为合格，2 为不合格。保持原始行列、方向和全部有效芯片，不做裁剪或缩放。27 个输入校验值均不同，总 JSON 约 102 KB。

完整逐片元数据与校验值位于 Android assets/samples/wafertools-manifest.json。原始标签保留在目录元数据和知识条目中，不进入导入 JSON，也不作为已完成的 AI 预测。数据库继续复用原有去重逻辑，载入已存在的同一矩阵会打开已有记录。

## 知识库

内置 40 条新知识：4 条来源、坐标及使用说明，9 类模式解释，27 张样本的实际尺寸、批次、芯片数量和良率。加上既有 4 条，共 44 条内置知识。重启、断网或在线缓存更新不会移除它们。可在「工具 → 知识库」搜索 wafertools、样本名或批次；AI 切换基础助手并关闭联网搜索后可离线检索这些条目。知识内容为项目整理说明，空间分布不作为工艺根因判定。

## 许可与评估边界

wafertools/wafermap 仓库使用 MIT，随包保留原作者 Copyright (c) 2026 Paul Robins 及完整许可：assets/licenses/wafertools-wafermap-MIT.txt。WM-811K 的 Kaggle qingyi 镜像在核对时标示 CC0: Public Domain；数据原始来源与镜像许可元数据需分开看待，详见 WM811K训练数据准备.md。

展示样本不是均衡评估集，不自动加入训练，不改动已冻结数据划分或模型权重，不据此更新准确率。none 表示未标注特定缺陷模式，不表示全部芯片合格；原始标签也可能含错误。

重新生成（固定来源 SHA-256 校验不符时停止）：

```powershell
backend/.venv/Scripts/python.exe scripts/import_wafertools_samples.py
```

此脚本只更新真实展示 assets、元数据清单和目录枚举，不读取或覆盖用户数据库、知识缓存及训练产物。
