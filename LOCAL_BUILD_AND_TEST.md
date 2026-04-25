# 本地打包、安装与复测说明

适用包名：`com.github.metacubex.clash.meta`

## 先看这里

- `README.md` 里的 `Build 1~6` 是完整环境准备流程，主要用于第一次搭建编译环境、配置签名、做通用构建时参考。
- 日常本地打包通常只需要两步：
  1. `source ./android-env.sh`
  2. `./gradlew :app:assembleMetaRelease -x downloadGeoFiles`
- 如果只是把 APK 发给别人安装，对方只需要拿到打包好的 APK，不需要执行仓库里的构建步骤。

## 1. 加载环境

```bash
cd /Users/wangyegao/work/www/wwwroot/extern/github.com/clashVerge
source ./android-env.sh
```

## 2. 日常打包 Meta Release

```bash
cd /Users/wangyegao/work/www/wwwroot/extern/github.com/clashVerge
source ./android-env.sh
./gradlew :app:assembleMetaRelease -x downloadGeoFiles
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

如果同时连了多台设备，请在 `adb` 后面加 `-s 设备ID`，例如：

```bash
adb -s A8NMBB5414104798 install -r -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk
```

## 5. 从 0 开始重新复测

如果要尽量模拟“新安装用户”，推荐直接卸载再重装。通常这样就够了：

```bash
adb uninstall com.github.metacubex.clash.meta
adb install -g app/build/outputs/apk/meta/release/cmfa-2.11.26-meta-arm64-v8a-release.apk
```

说明：

- 卸载再重装会清掉应用数据、已验证钱包记录、首次启动标记。
- 这样更适合复测“首次引导授权”和“第一次打开钱包是否弹框”。
- 大多数情况下，想从 0 开始测流程，直接“卸载后重新安装”即可。
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
