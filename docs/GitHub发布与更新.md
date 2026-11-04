# GitHub 发布与 App 更新

仓库：https://github.com/qingjiu1021-love/WaferAI

本机开发文件在 E:/WaferAI。发布脚本把可公开文件同步到独立的 E:/WaferAI-GitHub 工作副本，再提交 GitHub，避免干扰 help-site 原有托管源码仓库。发布前筛除本地账号、设置、密钥、签名、原始与完整加工数据、缓存及构建目录。

## 后续发布

1. 完成代码修改，增加 android/app/build.gradle.kts 的 versionName 和 versionCode。
2. 创建 docs/releases/新版本号.md，写本次真实变化和验证限制。
3. 确认 JAVA_HOME 指向 JDK 17，Android SDK 和原签名文件仍在原电脑。Git Credential Manager 登录 qingjiu1021-love。
4. 双击“发布更新到GitHub.bat”。默认执行 Android 构建、JVM 测试和 lint，再核验 APK 包名、版本、既有签名和 SHA-256。
5. 脚本推送源码，创建草稿 Release，上传完整 APK 后才公开。失败时先检查原因；不要拿不同包覆盖已有正式版本。
6. App 的“我的 → 关于 → 检查版本更新”即可读取新版本。旧版号不能重复发布。

仅准备公开副本、不上传：`py -3.12 scripts/publish_github.py`。

已有当前源码的完整检查结果时，维护者可传 `--checks-passed` 复用结果；修改代码后必须重新检查。提供 `--apk` 可指定已构建且版本、签名匹配的包。

## 升级信息

发布工具在 Release 说明写入 `waferai-version-code` 和 `waferai-apk-sha256`。App 读取 GitHub 最新正式 Release，排除草稿和预发布；只有版本编号增加、包名与官方仓库下载地址匹配时才提供更新。下载由用户确认，再交系统安装。

GitHub Actions 在 main 分支提交后自动运行 Android 编译与检查。它不把临时 CI 签名的 APK 当作更新发布，避免与已安装测试版不兼容。原签名私钥不上传 GitHub。迁移电脑或改用云端发布前，须单独规划稳定签名。

## 私有配置

仓库只存源码与空值示例。账号服务运行时密钥、邮件/短信令牌和 OAuth 凭据由服务端管理，不填进 App、工作流或提交历史。原始 WM-811K 与完整加工数据不上传。当前为沿用测试签名的毕业设计包，不代表已完成应用商店正式发行。
