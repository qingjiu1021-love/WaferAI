# PDF 报告导出

在 App 导入晶圆后，点击“导出 PDF 报告”，在系统保存窗口选择位置并点击 SAVE（保存）。默认文件名包含批次、晶圆编号和北京时间，用户可修改名称。保存成功后，App 显示“PDF 报告已保存到所选位置。”

报告在 Android 端使用 PdfDocument 生成，不增加后端接口或额外 Python 依赖。保存使用 CreateDocument 和 Storage Access Framework，仅写入用户选择的位置，不申请全部存储访问权限。取消保存后回到当前晶圆，导入与识别数据不变。

## 内容与数据一致性

报告包含批次、晶圆编号、来源文件、原始矩阵行列数、原始晶圆图、芯片数量、良率、预测类别、模型得分、模型版本、识别记录时间、预处理版本和模型 SHA-256。尚未识别时可导出晶圆图及良率，并明确记录“尚未进行缺陷识别”。

点击导出时固定当前页面数据的快照。模型得分标为当前样本的分类倾向，不作为整体测试准确率。良率始终由原始芯片数量计算。报告时间使用北京时间；识别记录时间保留后端 UTC 标记。

默认 A4 页面，文本自动换行，空间不足时自动分页。中文使用 Android 字体渲染；晶圆图保留原始矩阵的宽高比和 0/1/2 编码颜色，较小矩阵叠加白色网格。文件先在内存中完整生成，再写入所选 URI；生成和写入在后台线程执行。

## 已完成验证

Android 构建成功；在 Pixel_2 API 36 模拟器中完成取消保存、再次导出和保存成功检查。实际导出 WM811K-lot2 / W20 的 PDF，经 Poppler 渲染检查，无中文缺字、重叠或裁切。文件为 595×842 pt 的一页 A4、253,870 字节；提取文字后核对批次、W20、2145、315、2460、87.20%、Center、cnn-v1 和完整模型哈希均正确。

样例保存在模拟器 Downloads：`WaferAI_WM811K-lot2_W20_20261102_222602.pdf`。电脑侧样例另存于本次工作区的 `output/pdf/WaferAI-W20-report.pdf`，用于验证和演示，不修改后端原始数据。

参考：[Android PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)、[Android 文档保存机制](https://developer.android.com/training/data-storage/shared/documents-files)。
