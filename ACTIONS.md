# Andclaw 能力清单

本文档列出 Andclaw 作为 **Device Owner** 对 Android 设备的完整控制能力。

能力来自 4 个层级：

| 层级 | 来源 | 说明 |
|------|------|------|
| **A** | AccessibilityService | 屏幕读取 + 手势模拟 |
| **I** | Intent 系统 | 启动应用 / 系统级操作 |
| **D** | DevicePolicyManager | Device Owner 企业管理 API |

---

## 1. 屏幕感知

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 读取当前屏幕 UI 树 | A | `MyAiAccessibilityService.dumpScreenInfo()` | `rootInActiveWindow` 递归遍历 |
| 获取节点文本与坐标 | A | `parseNode()` | `AccessibilityNodeInfo.text` + `getBoundsInScreen()` |

---

## 2. 交互操作

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 模拟点击（坐标） | A | `MyAiAccessibilityService.performClick(x, y)` | `dispatchGesture()` + `GestureDescription.StrokeDescription(path, 0, 50)` |

---

## 3. 应用管理

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 启动应用（通过 Intent） | I | `MainActivity.executeIntent()` | `Intent(ACTION_VIEW)` / `ACTION_MAIN` + `startActivity()` |
| 启动指定 Activity | I | `AiAction.packageName` + `className` | `Intent.setComponent()` |
| 安装 APK（静默） | D | `DeviceOwnerImpl.installAPK(file)` | `PackageInstallationUtils.installPackage()` |
| 卸载应用（静默） | D | `DeviceOwnerImpl.uninstallAPK(packageName)` | `PackageInstallationUtils.uninstallPackage()` |
| 隐藏应用 | D | `AppUtils.hideApps(context, pkgs)` | `DPM.setApplicationHidden(admin, pkg, true)` |
| 显示应用 | D | `AppUtils.showApps(context, pkgs)` | `DPM.setApplicationHidden(admin, pkg, false)` |
| 挂起应用 | D | `DevicePolicyManagerGatewayImpl.setPackagesSuspended()` | `DPM.setPackagesSuspended()` |
| 阻止卸载 | D | `DevicePolicyManagerGatewayImpl.setUninstallBlocked()` | `DPM.setUninstallBlocked()` |
| 启用系统应用 | D | `DevicePolicyManagerGatewayImpl.enableSystemApp()` | `DPM.enableSystemApp()` |
| 安装已有包 | D | `DevicePolicyManagerGatewayImpl.installExistingPackage()` | `DPM.installExistingPackage()` |
| 禁止用户关闭应用 | D | `DevicePolicyManagerGatewayImpl.setUserControlDisabledPackages()` | `DPM.setUserControlDisabledPackages()` |
| 保持未安装包列表 | D | `DevicePolicyManagerGatewayImpl.setKeepUninstalledPackages()` | `DPM.setKeepUninstalledPackages()` |
| 设置应用限制 | D | `DevicePolicyManagerGatewayImpl.setApplicationRestrictions()` | `DPM.setApplicationRestrictions()` |
| 授予应用权限 | D | `AppUtils.enablePermission()` | `DPM.setPermissionGrantState(GRANTED)` |
| 查询权限状态 | D | `DevicePolicyManagerGatewayImpl.getPermissionGrantState()` | `DPM.getPermissionGrantState()` |
| 设置权限策略（全局自动授予） | D | `PolicyManagementFragment` | `DPM.setPermissionPolicy(PERMISSION_POLICY_AUTO_GRANT)` |
| 限制输入法 | D | `DevicePolicyManagerGatewayImpl.setPermittedInputMethods()` | `DPM.setPermittedInputMethods()` |

---

## 4. 设备控制

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 锁屏 | D | `DevicePolicyManagerGatewayImpl.lockNow()` | `DPM.lockNow()` |
| 重启设备 | D | `DevicePolicyManagerGatewayImpl.reboot()` | `DPM.reboot()` |
| 恢复出厂设置 | D | `DevicePolicyManagerGatewayImpl.wipeData()` | `DPM.wipeData()` |
| 禁用摄像头 | D | `DevicePolicyManagerGatewayImpl.setCameraDisabled()` | `DPM.setCameraDisabled()` |
| 禁用状态栏 | D | `DevicePolicyManagerGatewayImpl.setStatusBarDisabled()` | `DPM.setStatusBarDisabled()` |
| 禁用锁屏 | D | `DevicePolicyManagerGatewayImpl.setKeyguardDisabled()` | `DPM.setKeyguardDisabled()` |
| 禁用锁屏功能 | D | `DevicePolicyManagerGatewayImpl.setKeyguardDisabledFeatures()` | `DPM.setKeyguardDisabledFeatures()` |
| 控制 USB 数据 | D | `DevicePolicyManagerGatewayImpl.setUsbDataSignalingEnabled()` | `DPM.setUsbDataSignalingEnabled()` |
| 控制定位开关 | D | `DevicePolicyManagerGatewayImpl.setLocationEnabled()` | `DPM.setLocationEnabled()` |
| 请求 Bug 报告 | D | `DevicePolicyManagerGatewayImpl.requestBugreport()` | `DPM.requestBugreport()` |

---

## 5. Kiosk 模式

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 设置 Lock Task 包列表 | D | `DevicePolicyManagerGatewayImpl.setLockTaskPackages()` | `DPM.setLockTaskPackages()` |
| 设置 Lock Task 功能 | D | `DevicePolicyManagerGatewayImpl.setLockTaskFeatures()` | `DPM.setLockTaskFeatures()` |
| 替换默认 Launcher | D | `SetupKioskModeActivity` | `DPM.addPersistentPreferredActivity()` |
| 清除默认 Launcher | D | `KioskModeActivity` | `DPM.clearPackagePersistentPreferredActivities()` |
| 应用 Kiosk 默认策略 | D | `KioskModeHelper.setDefaultKioskPolicies()` | 批量设置用户限制 + 全局设置 |

---

## 6. 用户限制

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 禁止安全模式 | D | `KioskModeHelper` | `DPM.addUserRestriction(DISALLOW_SAFE_BOOT)` |
| 禁止恢复出厂设置 | D | `KioskModeHelper` | `DPM.addUserRestriction(DISALLOW_FACTORY_RESET)` |
| 禁止添加用户 | D | `KioskModeHelper` | `DPM.addUserRestriction(DISALLOW_ADD_USER)` |
| 禁止挂载物理介质 | D | `KioskModeHelper` | `DPM.addUserRestriction(DISALLOW_MOUNT_PHYSICAL_MEDIA)` |
| 禁止调试功能 | D | `KioskModeHelper` | `DPM.addUserRestriction(DISALLOW_DEBUGGING_FEATURES)` |
| 禁止调整音量 | D | `UserRestrictionsDisplayFragment` | `DPM.addUserRestriction(DISALLOW_ADJUST_VOLUME)` |
| 任意用户限制 | D | `DevicePolicyManagerGatewayImpl.setUserRestriction()` | `DPM.addUserRestriction()` / `clearUserRestriction()` |
| 查询用户限制 | D | `DevicePolicyManagerGatewayImpl.getUserRestrictions()` | `DPM.getUserRestrictions()` |

---

## 7. 密码与安全

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 设置密码质量 | D | `DevicePolicyManagerGatewayImpl.setPasswordQuality()` | `DPM.setPasswordQuality()` |
| 设置密码复杂度 | D | `DevicePolicyManagerGatewayImpl.setRequiredPasswordComplexity()` | `DPM.setRequiredPasswordComplexity()` |
| 设置密码重置 Token | D | `ResetPasswordWithTokenFragment` | `DPM.setResetPasswordToken()` |
| 使用 Token 重置密码 | D | `ResetPasswordWithTokenFragment` | `DPM.resetPasswordWithToken()` |
| 设置密码错误上限（自动擦除） | D | `DevicePolicyManagerGatewayImpl.setMaximumFailedPasswordsForWipe()` | `DPM.setMaximumFailedPasswordsForWipe()` |
| 检查密码是否满足要求 | D | `DevicePolicyManagerGatewayImpl.isActivePasswordSufficient()` | `DPM.isActivePasswordSufficient()` |
| 生成密钥对 | D | `DevicePolicyManagerGatewayImpl.generateKeyPair()` | `DPM.generateKeyPair()` |
| 删除密钥对 | D | `DevicePolicyManagerGatewayImpl.removeKeyPair()` | `DPM.removeKeyPair()` |
| 授予密钥对给应用 | D | `DevicePolicyManagerGatewayImpl.grantKeyPairToApp()` | `DPM.grantKeyPairToApp()` |
| 撤销应用密钥对 | D | `DevicePolicyManagerGatewayImpl.revokeKeyPairFromApp()` | `DPM.revokeKeyPairFromApp()` |
| 安装 CA 证书 | D | `ProfileOwnerService` | `DPM.installCaCertificate()` |

---

## 8. 网络控制

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| APN 覆盖 | D | `OverrideApnFragment` | `DPM.addOverrideApn()` / `removeOverrideApn()` / `setOverrideApnsEnabled()` |
| 设置私有 DNS | D | `PrivateDnsModeFragment` | `DPM.setGlobalSetting()` |
| 计量数据限制 | D | `DevicePolicyManagerGatewayImpl.setMeteredDataDisabledPackages()` | `DPM.setMeteredDataDisabledPackages()` |
| 优先网络服务 | D | `DevicePolicyManagerGatewayImpl.setPreferentialNetworkServiceEnabled()` | `DPM.setPreferentialNetworkServiceEnabled()` |
| WiFi 配置管理 | D | `WifiConfigCreationDialog` | WiFi API |

---

## 9. 系统设置

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 修改 Global 设置 | D | `DevicePolicyManagerGatewayImpl.setGlobalSetting()` | `DPM.setGlobalSetting()` |
| 修改 Secure 设置 | D | `DevicePolicyManagerGatewayImpl.setSecureSetting()` | `DPM.setSecureSetting()` |
| 禁用 ADB 调试 | D | `KioskModeHelper` | `DPM.setGlobalSetting(ADB_ENABLED, "0")` |
| 禁用开发者选项 | D | `KioskModeHelper` | `DPM.setGlobalSetting(DEVELOPMENT_SETTINGS_ENABLED, "0")` |
| 系统更新策略 | D | `SystemUpdatePolicyFragment` | `DPM.setSystemUpdatePolicy()` |
| 设置锁屏信息 | D | `DevicePolicyManagerGatewayImpl.setDeviceOwnerLockScreenInfo()` | `DPM.setDeviceOwnerLockScreenInfo()` |
| 设置组织名称 | D | `DevicePolicyManagerGatewayImpl.setOrganizationName()` | `DPM.setOrganizationName()` |

---

## 10. 监控与日志

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 启用安全日志 | D | `DevicePolicyManagerGatewayImpl.setSecurityLoggingEnabled()` | `DPM.setSecurityLoggingEnabled()` |
| 获取安全日志 | D | `DevicePolicyManagerGatewayImpl.retrieveSecurityLogs()` | `DPM.retrieveSecurityLogs()` |
| 获取重启前安全日志 | D | `DevicePolicyManagerGatewayImpl.retrievePreRebootSecurityLogs()` | `DPM.retrievePreRebootSecurityLogs()` |
| 启用网络日志 | D | `DevicePolicyManagerGatewayImpl.setNetworkLoggingEnabled()` | `DPM.setNetworkLoggingEnabled()` |
| 获取网络日志 | D | `DevicePolicyManagerGatewayImpl.retrieveNetworkLogs()` | `DPM.retrieveNetworkLogs()` |
| 请求 Bug 报告 | D | `DevicePolicyManagerGatewayImpl.requestBugreport()` | `DPM.requestBugreport()` |

---

## 11. 用户管理

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 创建用户 | D | `DevicePolicyManagerGatewayImpl.createAndManageUser()` | `DPM.createAndManageUser()` |
| 删除用户 | D | `DevicePolicyManagerGatewayImpl.removeUser()` | `DPM.removeUser()` |
| 切换用户 | D | `DevicePolicyManagerGatewayImpl.switchUser()` | `DPM.switchUser()` |
| 后台启动用户 | D | `DevicePolicyManagerGatewayImpl.startUserInBackground()` | `DPM.startUserInBackground()` |
| 停止用户 | D | `DevicePolicyManagerGatewayImpl.stopUser()` | `DPM.stopUser()` |
| 登出用户 | D | `DevicePolicyManagerGatewayImpl.logoutUser()` | `DPM.logoutUser()` |
| 设置用户图标 | D | `DevicePolicyManagerGatewayImpl.setUserIcon()` | `DPM.setUserIcon()` |
| 设置会话消息 | D | `DevicePolicyManagerGatewayImpl.setStartUserSessionMessage()` | `DPM.setStartUserSessionMessage()` |
| 设置关联 ID | D | `DevicePolicyManagerGatewayImpl.setAffiliationIds()` | `DPM.setAffiliationIds()` |
| 获取次级用户 | D | `DevicePolicyManagerGatewayImpl.getSecondaryUsers()` | `DPM.getSecondaryUsers()` |

---

## 12. Intent 系统

通过 `MainActivity.executeIntent()` 执行，支持任意 Android Intent。

| 能力 | action | data / extras 示例 |
|------|--------|---------------------|
| 打开网页/应用 | `android.intent.action.VIEW` | `data: "https://..."` |
| 设置闹钟 | `android.intent.action.SET_ALARM` | `extras: {"android.intent.extra.alarm.HOUR": 8}` |
| 发送内容 | `android.intent.action.SEND` | `extras: {"android.intent.extra.TEXT": "..."}` |
| 拨打电话 | `android.intent.action.DIAL` | `data: "tel:10086"` |
| 发送短信 | `android.intent.action.SENDTO` | `data: "smsto:10086"` |
| 打开设置 | `android.provider.Settings.*` | 各种系统设置页 |
| 打开文件 | `android.intent.action.VIEW` | `data: "content://..."` + MIME type |
| 分享内容 | `android.intent.action.SEND` | `extras: {"android.intent.extra.STREAM": uri}` |
| 启动指定应用 | `android.intent.action.MAIN` | `packageName` + `className` |

---

## 13. 其他能力

| 能力 | 层级 | 实现 | API |
|------|------|------|-----|
| 悬浮窗（急停按钮） | — | `FloatingStopWindow.show()` | `WindowManager` + `TYPE_APPLICATION_OVERLAY` |
| 语音输入 | — | `MainActivity.startVoiceRecognition()` | `SpeechRecognizer` |
| AI 决策（LLM） | — | `Utils.callLLMWithHistory()` | OpenAI-compatible API |
| 防循环检测 | — | `MainActivity.handleAction()` | 最近 3 次动作指纹对比 |
| 人工确认 | — | `MainActivity.showActionConfirmDialog()` | `click` 操作需用户授权 |
| 跨配置文件应用 | D | `DevicePolicyManagerGatewayImpl.setCrossProfilePackages()` | `DPM.setCrossProfilePackages()` |
| 委托管理范围 | D | `DevicePolicyManagerGatewayImpl.setDelegatedScopes()` | `DPM.setDelegatedScopes()` |
| 所有权转移 | D | `DevicePolicyManagerGatewayImpl.transferOwnership()` | `DPM.transferOwnership()` |
| 移除 Device Owner | D | `DevicePolicyManagerGatewayImpl.clearDeviceOwnerApp()` | `DPM.clearDeviceOwnerApp()` |
| 恢复出厂保护 | D | `FactoryResetProtectionPolicyFragment` | Factory Reset Protection API |
