# App Module 代码清理指南

> 本文档记录 `app/src/main` 中已识别的无用代码和资源，按风险等级分类。
> 每次清理后请更新对应条目状态。最近审计时间：2026-03-22。

## 审计方法

1. 以 `NavigationGraph.kt` 为入口，追踪所有可达的 Screen/ViewModel
2. 交叉引用 `AndroidManifest.xml` 中声明的组件
3. 对每个 Kotlin 文件/资源文件搜索 import 和 XML 引用
4. 对 `Route.kt` 中的常量搜索导航调用

---

## 1. 安全删除（无任何引用）

| 文件 | 类型 | 说明 |
|------|------|------|
| `presentation/common/TokenInputField.kt` | Composable | 无任何外部 import |
| `presentation/common/PlatformCheckBoxItem.kt` | Composable | 无任何外部 import |
| `presentation/ui/setup/SetupPlatformListScreen.kt` | Screen | 不在 NavigationGraph 中注册，commit `10ef670` 已跳过此步骤 |
| `util/DefaultHashMap.kt` | 工具类 | 无任何外部 import |
| `util/ScrollStateSaver.kt` | 工具类 | 依赖 `DefaultHashMap`，无任何外部 import |
| `res/drawable/ic_play_store.xml` | Drawable | 无任何 Kotlin 或 XML 引用 |
| `res/drawable/ic_f_droid.xml` | Drawable | 无任何 Kotlin 或 XML 引用 |
| `res/drawable/ic_vibe_app_no_padding.xml` | Drawable | 无任何直接引用（旧图标变体） |
| `res/.DS_Store` | 系统文件 | macOS 元数据 |
| `assets/.DS_Store` | 系统文件 | macOS 元数据 |
| `assets/templates/EmptyActivity/app/src/main/.DS_Store` | 系统文件 | macOS 元数据 |
| `assets/templates/EmptyActivity/app/src/main/res/.DS_Store` | 系统文件 | macOS 元数据 |

### 无引用的字符串资源

以下 `res/values/strings.xml` 中的字符串无任何 `R.string.*` 引用：

```
supported_soon, ai_generated, retry_ai_title, apply_generated_title,
model_custom_example, platform_added_successfully,
project_initializing, no_projects_yet,
sample_item_title, sample_item_description
```

### Route.kt 中仅声明未使用的常量

以下路由常量仅在 `Route.kt` 中定义，无任何导航调用：

```
SELECT_PLATFORM, TOKEN_INPUT,
OPENAI_MODEL_SELECT, ANTHROPIC_MODEL_SELECT, GOOGLE_MODEL_SELECT,
GROQ_MODEL_SELECT, OLLAMA_MODEL_SELECT, OLLAMA_API_ADDRESS,
OPENAI_SETTINGS, ANTHROPIC_SETTINGS, GOOGLE_SETTINGS,
GROQ_SETTINGS, OLLAMA_SETTINGS,
SETUP_PLATFORM_LIST
```

这些是 V1 时代遗留的按平台分页路由，V2 已改为动态 `PLATFORM_SETTINGS/{platformUid}` 路由。

---

## 2. 可评估删除（V1→V2 迁移系统）

> **前置条件**：确认已无用户仍在从 V1 版本升级。如果 V1→V2 迁移已完成且不再需要支持从旧版本升级，以下文件可以整体移除。

### 2.1 迁移 UI

| 文件 | 说明 |
|------|------|
| `presentation/ui/migrate/MigrateScreen.kt` | V1→V2 迁移引导界面 |
| `presentation/ui/migrate/MigrateViewModel.kt` | 调用 `migrateToPlatformV2()` 和 `migrateToChatRoomV2MessageV2()` |

### 2.2 迁移专用图标

以下 `presentation/icons/` 下的文件**仅被** `MigrateScreen.kt` 引用：

| 文件 | 使用者 |
|------|--------|
| `presentation/icons/Block.kt` | MigrateScreen |
| `presentation/icons/Complete.kt` | MigrateScreen |
| `presentation/icons/Error.kt` | MigrateScreen |
| `presentation/icons/Migrating.kt` | MigrateScreen |
| `presentation/icons/Ready.kt` | MigrateScreen |

### 2.3 V1 数据库层

| 文件 | 说明 |
|------|------|
| `data/database/ChatDatabase.kt` | V1 Room 数据库，仅被 DatabaseModule 和迁移逻辑引用 |
| `data/database/dao/ChatRoomDao.kt` | V1 ChatRoom DAO |
| `data/database/dao/MessageDao.kt` | V1 Message DAO |
| `data/database/entity/ChatRoom.kt` | V1 ChatRoom 实体（含 `APITypeConverter`） |
| `data/database/entity/Message.kt` | V1 Message 实体 |

### 2.4 V1 设置/平台数据类型

| 文件 | 说明 |
|------|------|
| `data/dto/Platform.kt` | V1 平台数据类，使用 `ApiType` 枚举。被 `SettingViewModel`(V1)、`SettingRepository.fetchPlatforms()` 引用 |
| `data/dto/APIModel.kt` | V1 模型描述数据类，仅被 `MapStringResources.kt` 中已废弃的函数引用 |
| `data/model/ApiType.kt` | V1 平台枚举（OPENAI/ANTHROPIC/GOOGLE/GROQ/OLLAMA），被多个 V1 文件引用 |
| `presentation/ui/setting/SettingViewModel.kt` | V1 设置 ViewModel，已被 `SettingViewModelV2.kt` 替代，不在 NavigationGraph 中使用 |

### 2.5 V1 相关的 DataStore 条目

`data/datastore/SettingDataSourceImpl.kt` 中仍保留着按 `ApiType` 索引的 Preferences Key map（如 `ApiType.OPENAI to stringPreferencesKey("openai_token")`）。这些是 V1 平台设置的存储方式，V2 使用 Room `PlatformV2` 表。删除迁移系统时需同步清理。

### 2.6 迁移后清理步骤

删除上述文件后需同步修改：

1. `NavigationGraph.kt` — 移除 `migrationScreenNavigation()`
2. `Route.kt` — 移除 `MIGRATE_V2` 常量
3. `di/DatabaseModule.kt` — 移除 `provideChatDatabase()`、`provideChatRoomDao()`、`provideMessageDao()` 等 V1 provider
4. `di/ChatRepositoryModule.kt` — 移除 `chatRoomDao`、`messageDao` 参数
5. `data/repository/ChatRepository.kt` / `ChatRepositoryImpl.kt` — 移除 `fetchChatList()`、`migrateToChatRoomV2MessageV2()` 等 V1 方法
6. `data/repository/SettingRepository.kt` / `SettingRepositoryImpl.kt` — 移除 `fetchPlatforms()`、`updatePlatforms()`、`migrateToPlatformV2()` 等 V1 方法

---

## 3. 可评估删除（MapStringResources.kt 中的废弃函数）

`util/MapStringResources.kt` 中以下函数**无任何外部调用**：

```kotlin
getPlatformTitleResources()
getPlatformDescriptionResources()
getPlatformAPILabelResources()
getPlatformHelpLinkResources()
generateOpenAIModelList()
generateAnthropicModelList()
generateGoogleModelList()
generateGroqModelList()
```

这些是 V1 时代按固定枚举映射平台名称/模型列表的函数。V2 改为动态平台配置后已无用。

**注意**：同文件中的 `getClientTypeDisplayName()`、`getDynamicThemeTitle()`、`getThemeModeTitle()` 仍被 `SettingScreen.kt` 使用，不可删除。

---

## 4. 可评估精简（模板 assets）

`assets/templates/EmptyActivity/` 中以下文件与实际构建管线无关（构建使用 AAPT2+Javac+D8，不使用 Gradle）：

| 文件 | 说明 |
|------|------|
| `build.gradle`（根目录） | Gradle 根配置，不被构建管线使用 |
| `app/build.gradle` | Gradle app 配置，不被构建管线使用 |
| `settings.gradle` | Gradle settings |
| `gradle.properties` | Gradle 属性 |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle wrapper JAR（~60KB） |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle wrapper 配置 |
| `gradlew` | Gradle wrapper 脚本 |
| `gradlew.bat` | Gradle wrapper Windows 脚本 |
| `.gitignore`（根目录 + app 目录） | Git 忽略文件 |
| `app/proguard-rules.pro` | ProGuard 规则 |

> **风险提示**：如果未来有计划支持用户导出项目到 Android Studio，保留这些文件是有意义的。否则可以移除以减少 APK 体积和模板解压时间。

---

## 5. 字符串资源清理建议

`res/values/strings.xml` 中保留了大量 V1 时代的模型描述字符串（如 `gpt_4o_description`、`claude_3_5_sonnet_description` 等），仅被 `MapStringResources.kt` 中的废弃函数引用。如果清理了第 3 节的废弃函数，这些字符串资源也可以一并移除。

需要逐个确认的字符串（可能仅被已废弃代码引用）：
- `gpt_4o`, `gpt_4o_description`, `gpt_4o_mini`, `gpt_4o_mini_description`, ...
- `claude_3_5_sonnet`, `claude_3_5_sonnet_description`, ...
- `gemini_*`, `llama_*`, `gemma_*` 系列
- `openai_description`, `anthropic_description`, `google_description`, `groq_description`, `ollama_description`
- `openai_api_key`, `anthropic_api_key`, `google_api_key`, `groq_api_key`, `ollama_api_key`
- `openai_api_help`, `anthropic_api_help`, `google_api_help`, `groq_api_help`, `ollama_api_help`

---

## 6. 其他技术债务

| 位置 | 说明 |
|------|------|
| `presentation/VibeApp.kt:11` | TODO: Dagger issue #3601 的 workaround，`@Inject @ApplicationContext lateinit var context` 未被使用 |
| `res/xml/backup_rules.xml` | 内容全部注释，无实际备份规则生效 |
| `res/xml/data_extraction_rules.xml` | 内容全部注释，无实际数据提取规则生效 |

---

## 7. 不应删除的文件说明

以下文件看起来可能无用但实际有引用，不应删除：

| 文件 | 原因 |
|------|------|
| `presentation/icons/Done.kt` | 被 `SetupCompleteScreen` 使用 |
| `presentation/icons/VibeAppStartScreen.kt` | 被 `StartScreen` 使用 |
| `presentation/common/PrimaryLongButton.kt` | 被 StartScreen、MigrateScreen、SetupPlatformListScreen、SetupCompleteScreen 使用 |
| `presentation/common/RadioItem.kt` | 被 `SettingScreen` 使用 |
| `util/PinnedExitUntilCollapsedScrollBehavior.kt` | 被 `PlatformSettingScreen`、`SettingScreen` 使用 |
| `util/PlatformName.kt` | 被 `ChatViewModel` 使用 |
| `util/FileUtils.kt` | 被 `ChatRepositoryImpl` 使用 |
| `util/Strings.kt` (`isValidUrl`) | 被 `PlatformSettingDialogs` 使用 |
| `util/ApiStateFlowExtensions.kt` | 被 `ChatRepository` 使用 |
| `data/dto/ThemeSetting.kt` | 被 `ThemeViewModel`、`SettingRepository` 使用 |
| `res/drawable/ic_vibe.webp` | 被 `ChatBubble`、`StartScreen` 使用 |

---

## 清理操作模板

每次执行清理时，遵循以下步骤：

1. **确认引用**：对目标文件执行 `grep -r "ClassName\|filename"` 确认无残留引用
2. **删除文件**：移除目标文件
3. **修复编译**：`./gradlew assembleDebug` 确认编译通过
4. **更新本文档**：在对应条目后标注 `[已清理 YYYY-MM-DD]`
5. **提交**：单独 commit，标注清理范围

---

## 变更记录

| 日期 | 操作 |
|------|------|
| 2026-03-22 | 初始审计，识别出 5 类可清理项 |
| 2026-03-22 | [已清理] 第1节：安全删除全部完成（8个文件 + 14个Route常量 + .DS_Store文件） |
| 2026-03-22 | [已清理] 第2节：V1→V2迁移系统完整移除（MigrateScreen/ViewModel、V1数据库层、V1 DTO/Model、V1 DataStore/Repository方法、MainViewModel迁移逻辑） |
| 2026-03-22 | [已清理] 第3节：MapStringResources.kt 废弃函数移除（7个函数 + APIModel/ApiType引用） |
| 2026-03-22 | [已清理] ModelConstants.kt 清理（移除V1模型列表、getDefaultAPIUrl、OPENAI_PROMPT、ANTHROPIC_MAXIMUM_TOKEN） |
| 2026-03-22 | [已清理] ApiType.kt 删除（所有引用已移除） |
