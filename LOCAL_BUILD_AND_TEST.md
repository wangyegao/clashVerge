# 本地打包与复测命令

适用包名：`com.github.metacubex.clash.meta`

## 1. 环境变量

```bash
cd /Users/wangyegao/work/www/wwwroot/extern/github.com/clashVerge
source ./android-env.sh
```

## 2. 打包 Meta Release

```bash
cd /Users/wangyegao/work/www/wwwroot/extern/github.com/clashVerge
source ./android-env.sh
./gradlew assembleMetaRelease -x downloadGeoFiles
```

## 3. APK 输出目录

```bash
app/build/outputs/apk/meta/release/
```

常用产物：

- `app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk`
- `app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-universal-release.apk`

## 4. 安装到手机

先确认设备已连接：

```bash
adb devices -l
```

安装 ARM64 包：

```bash
adb install -r -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk
```

安装通用包：

```bash
adb install -r -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-universal-release.apk
```

## 5. 从 0 开始重新复测

如果要尽量模拟“新安装用户”，推荐直接卸载再重装：

```bash
adb uninstall com.github.metacubex.clash.meta
adb install -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk
```

说明：

- 卸载再重装会清掉应用数据、已验证钱包记录、首次启动标记。
- 这样更适合复测“首次引导授权”和“第一次打开钱包是否弹框”。
- 重装后仍需要重新完成应用内配置导入。
- `adb install -g` 只能自动授予普通运行时权限，`查看使用情况` 和 `悬浮窗` 这类特殊权限仍需要按系统实际情况重新开启。

如果只是想重测应用内状态，不关心重新安装过程，也可以只清数据：

```bash
adb shell pm clear com.github.metacubex.clash.meta
adb install -r -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk
```

## 6. 复测建议顺序

1. 卸载并重装 APK。
2. 打开 Clash。
3. 按引导开启 `查看使用情况` 和 `悬浮窗`。
4. 导入并激活配置。
5. 启动 Clash。
6. 打开目标钱包。
7. 确认是否出现风险弹框。
8. 输入承诺文字并确认。
9. 再次打开同一钱包，确认是否不再重复弹框。
