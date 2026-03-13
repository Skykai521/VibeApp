# build-engine 实现说明

## 当前实现

`build-engine` 已按真实可执行顺序实现为：

1. `Aapt2ResourceCompiler`
2. `JavacCompiler`
3. `D8DexConverter`
4. `AndroidApkBuilder`
5. `DebugApkSigner`

统一编排入口是：

- `build-engine/src/main/java/com/vibe/build/engine/pipeline/DefaultBuildPipeline.kt`

兼容层：

- `EcjCompiler` 仍然保留，但已经只是 `JavacCompiler` 的废弃包装，真实编译器不再走 ECJ。

## 输入模型

核心输入在：

- `build-engine/src/main/java/com/vibe/build/engine/model/BuildModels.kt`

当前 `CompileInput` 的关键字段：

- `workingDirectory`：构建工作目录，按 Android 常规结构组织
- `sourceFiles`：写入 `src/main/java/` 的相对路径文件集
- `resourceFiles`：写入 `src/main/res/` 的相对路径文件集
- `assetFiles`：写入 `src/main/assets/` 的相对路径文件集
- `manifestContents` / `manifestFilePath`：Manifest 输入，若都为空则生成最小默认 Manifest
- `classpathEntries`：参与 Javac/D8/打包的外部 JAR
- `minSdk` / `targetSdk` / `buildType`
- `signingConfig`：可选自定义 `pk8 + x509.pem`，默认回退到内置 debug key

当前 API 的适用范围：

- 以单个 Android 工作目录为主
- 外部依赖当前按 `classpathEntries` 中的 JAR 处理
- AAR 的资源合并、Manifest 合并、transitive dependency 解析仍建议继续放在更上层或沿用 `build-tools` 旧任务体系

## 工作目录约定

`build-engine` 默认按下面的目录工作：

```text
<workingDirectory>/
├── src/main/java/
├── src/main/res/
├── src/main/assets/
├── src/main/resources/
├── src/main/jniLibs/
├── src/main/AndroidManifest.xml
└── build/
    ├── gen/                     # AAPT2 生成的 R.java
    └── bin/
        ├── java/classes/        # JavacTool 输出 .class
        ├── res/project.zip      # AAPT2 compile 输出
        ├── res/R.txt            # AAPT2 text symbols
        ├── generated.apk.res    # AAPT2 link 输出
        ├── classes.dex          # D8 输出
        ├── generated.apk        # 打包后的未签名 APK
        └── signed.apk           # 最终 APK
```

## 各阶段职责

### 1. AAPT2

- 编译 `src/main/res/`
- 链接 Manifest
- 生成 `build/gen/R.java`
- 生成 `build/bin/generated.apk.res`
- 生成 `build/bin/res/R.txt`
- 运行前会优先解析 APK 内 `lib/<abi>/libaapt2.so`，解压到应用私有目录再执行，避免部分设备上 `nativeLibraryDir` 下的可执行文件不可直接启动

### 2. JavacTool

- 编译业务源码
- 同时编译 AAPT2 生成的 `R.java`
- 输出到 `build/bin/java/classes`

### 3. D8

- 把 `build/bin/java/classes` 下的 `.class` 转为 `classes.dex`
- 输出到 `build/bin/`

### 4. APK 打包

- 读取 `generated.apk.res`
- 合并 `classes.dex`
- 打进 `classpathEntries` 中 JAR 的资源内容
- 合并 `src/main/jniLibs` 与 `src/main/resources`
- 产出 `generated.apk`

### 5. APK 签名

- 默认使用 `build-engine/src/main/assets/testkey.pk8.zip`
- 默认使用 `build-engine/src/main/assets/testkey.x509.pem.zip`
- 使用 `apksig` 生成 `signed.apk`

## 与 build-tools 的关系

`build-engine` 没有再继续套用旧的 stub，也没有照抄 `build-tools` 里那套并不完整的签名包装。

当前实现直接复用了这些已经接入的基础能力：

- `BuildModule`：提供 `rt.jar` 和 `lambda-stubs`
- `Aapt2Jni`：AAPT2 调用桥接
- `sdklib ApkBuilder`：APK 打包
- `apksig`：APK V1/V2 签名

## 文档对应关系

以下文档已经按这套实现同步：

- `README.md`
- `docs/build-chain.md`
- `docs/architecture.md`
- `CONTRIBUTING.md`
