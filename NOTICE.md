# 来源与许可说明

本仓库为 WaferAI 毕业设计项目。暂未为项目原创代码另行授予开源许可证；公开源码不等于授权任意再分发。第三方依赖按各自许可证使用，保留于对应目录及 Android assets/licenses 中。

- WM-811K 来源、镜像许可信息和划分规则见 docs/WM811K训练数据准备.md。仓库不包含原始数据集及完整加工数据。Android 内置小型训练演示子集沿用已记录的 Kaggle 镜像 CC0 元数据；这不构成对原始发布者权利的额外担保。
- 内置 CNN 及手机训练演示属于教学研究用途，不能替代实际产线测试或直接用于工艺放行。
- Python、Android、ONNX Runtime、Parquet 读取库、网站模板等第三方组件保留原有许可声明。
- GitHub 发布的是毕业设计测试包，沿用本机测试签名；正式商用或应用商店发行应单独规划正式签名、审核后的政策与服务配置。

- 4.6.0 内置的 27 张真实演示样本参考 wafertools/wafermap 固定版本，来源、映射规则和数据集许可边界见 docs/wafertools真实演示样本.md；随包保留 Paul Robins 的 MIT 声明。

- 4.10.0 的导航胶囊和分级玻璃参考 FlClash（GPL-3.0）提交 7c9627ff 的交互设计，使用独立的 Jetpack Compose 实现。Parquet 导出使用 hyparquet-writer 0.18.0 与 hyparquet 1.32.0（MIT）。版本锁定和入口在 `scripts/parquet-writer`，许可证随包置于 `assets/licenses`。

- 4.10.2 的底栏继续以 Jetpack Compose 实现，采用 FlClash 同一提交的弹簧参数、速度形变公式和滑动时序。来源见 [FlClash 的 navigation_dock.dart](https://github.com/chen08209/FlClash/blob/7c9627fffb35f2615fbfaa64b4b3a0c5834c52ed/lib/ui/navigation_dock.dart)。

- 星夜主题背景使用文森特·梵高 1889 年的《星夜》。随包图像将 Google Art Project 的公有领域复刻图转为 WebP，屏幕显示时裁切并叠加遮罩。作品收藏于纽约现代艺术博物馆。[图像来源及许可](https://commons.wikimedia.org/wiki/File:Van_Gogh_-_Starry_Night_-_Google_Art_Project.jpg)
