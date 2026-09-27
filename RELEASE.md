# 发版 / 上传流程须知

## 1. 日常提交（不消耗任何 CI 额度）

```bash
git add -A && git commit -m "改动说明" && git push
```

- 只改 `pc/`、README、文档 → **不触发**任何 CI
- 改了 `app/**` 或 Gradle 配置 → 触发 Release 工作流，但 `versionName` 没变时**自动跳过编译**（约 10 秒即结束）
- 想跳过本次 CI：提交信息里加 `[skip ci]`

## 2. 发布新版 APP（标准流程：本地编译 + 草稿直发）

```bash
# ① 递增版本号（versionCode 自动取 git 提交数，不用管）
#    app/build.gradle.kts → versionName = "X.Y.Z"

# ② 本地编译
./gradlew assembleRelease

# ③ 建草稿 Release 并附上 APK（改名后上传）
cp app/build/outputs/apk/release/app-release.apk /tmp/TextRelay-X.Y.Z.apk
gh release create vX.Y.Z --draft --title "TextRelay vX.Y.Z" \
    --notes "更新说明" /tmp/TextRelay-X.Y.Z.apk

# ④ 推送代码
git push
```

推送后 CI 发现草稿 → **直接发布**（约 10 秒，不编译、零额度消耗）。

## 3. 云端编译（可选）

Actions 页面 → **Release** → **Run workflow**：

- 默认：`versionName` 已发布过则自动跳过
- 勾选 **force**：删除已有版本并强制重新编译发布

## 4. 注意事项

- **签名材料不入库**：`app/textrelay.keystore` 和 `keystore.properties`（存密码）只在本地，换机 / 重装系统前必须备份，丢失后只能卸载重装
- 密码也可用环境变量 `TEXTRELAY_STORE_PASSWORD` / `TEXTRELAY_KEY_PASSWORD` 提供，不从文件读
- 密码、密钥**永远不要**写进代码或提交到仓库
- `release.yml` 的 `paths` 只监听 `app/**` 和 Gradle 相关文件——新增源码目录时记得同步这份清单
- 更新日志自动取「上一个版本标签以来的提交」，所以**提交信息写清楚**，日志才好看
