# Windows 11 本地打包指南

面向开发者：修改源码后，如何在 Windows 11 上把项目打包为可运行产物。仓库已有 `bin/release-windows.sh` 完成此流程，本文档是其 Windows 原生环境下的等价步骤说明。

> **首选方式 1**（`java -jar` 直接跑 jar）。除非你要真正生成 `sonovel.exe` 启动器或打发行包，否则不必看方式 2、方式 3。

---

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21** | `pom.xml` 用 `<release>21</release>`，低版本编译会报错；推荐 Adoptium Temurin 21 |
| Maven | 3.8+ | `mvn -v` 能输出版本号即可 |
| Git Bash 或 WSL | 可选 | 只有想直接跑 `bin/release-windows.sh` 才需要 |

在 PowerShell 中检查：

```powershell
java -version
mvn -v
echo $env:JAVA_HOME
```

若 `JAVA_HOME` 未设置，或指向了错误的 JDK，临时切换当前窗口可用：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

---

## 方式 1：开发验证 —— `java -jar` 直接运行（推荐）

适用场景：改完代码想快速验证，不关心 `sonovel.exe` 启动器。

这是最干净的路径 —— 不用 `runtime\` 目录，不用生成 exe，不用动 `target` 之外的地方。系统装过 JDK 21 就行。

### 1.1 首次搭建运行目录（只做一次）

挑一个**项目外**的目录作为日常运行目录，避免被 `mvn clean` 删掉。例如 `D:\SoNovel`：

```powershell
cd D:\project\so-novel

# 1. 首次打包
mvn clean package -Pwindows-x64 "-Dmaven.test.skip=true" "-DjrePath=runtime"

# 2. 建运行目录
New-Item -ItemType Directory D:\SoNovel -Force | Out-Null

# 3. 把必需的文件一次性拷过去
Copy-Item target\app-jar-with-dependencies.jar D:\SoNovel\app.jar

# rules 和 config.ini 仅在目标不存在时才拷（避免覆盖你之前改过的配置）
if (-not (Test-Path D:\SoNovel\rules))           { Copy-Item -Recurse bundle\rules D:\SoNovel\ }
if (-not (Test-Path D:\SoNovel\config.ini))      { Copy-Item bundle\config.ini      D:\SoNovel\ }
if (-not (Test-Path D:\SoNovel\sonovel.l4j.ini)) { Copy-Item bundle\sonovel.l4j.ini D:\SoNovel\ }
```

> PowerShell 里 `-D` 参数必须用双引号包起来，CMD 下可以去掉引号。
>
> `-DjrePath=runtime` 是 `pom.xml` 里 launch4j 配置对 `${jrePath}` 的硬依赖，打包时必须带上（哪怕你不用 exe）。

最终 `D:\SoNovel\` 里应有：`app.jar`、`rules\`、`config.ini`、`sonovel.l4j.ini`。

### 1.2 按使用场景改 `config.ini`

`bundle\config.ini` 默认是 **TUI 交互模式**（`[web] enabled = 0`）。根据你的使用方式选择：

| 使用方式 | 需要的 `[web]` 配置 | 启动表现 |
| --- | --- | --- |
| 手动在终端交互下载（默认） | `enabled = 0` | 启动后弹出主菜单，按 `q/w/e` 等键交互 |
| 配合 `tools/download_agent/` 自动拉取任务下载 | `enabled = 1`，`port = 7765` | 启动后直接起 Jetty 监听 7765，**不弹主菜单** |

> **特别注意**：`download_agent` 是通过 `http://localhost:7765/search/aggregated`、`/book-fetch` 等 HTTP 接口调用 SoNovel 的。`enabled = 0` 时 Jetty 不启动，agent 会报 `All connection attempts failed`。

改成 WebUI 模式的 PowerShell 一行（只替换 `[web]` 段下的 0，不影响 `[proxy]` 等其他段）：

```powershell
$p = "D:\SoNovel\config.ini"
(Get-Content $p -Raw) -replace '(?ms)(\[web\][^\[]*?enabled\s*=\s*)0', '${1}1' | Set-Content $p
```

### 1.3 启动验证

```powershell
cd D:\SoNovel
java -jar app.jar
```

- TUI 模式：看到 SoNovel 横幅 + `q.聚合搜索 w.独立搜索 ...` 主菜单 → 成功
- WebUI 模式：控制台打印 Jetty 启动日志，浏览器打开 <http://localhost:7765> 能看到页面 → 成功

### 1.4 以后每次改代码

只要两步：

```powershell
cd D:\project\so-novel
mvn package -Pwindows-x64 "-Dmaven.test.skip=true" "-DjrePath=runtime"
Copy-Item target\app-jar-with-dependencies.jar D:\SoNovel\app.jar -Force
```

然后去 `D:\SoNovel` 跑 `java -jar app.jar`。

> **注意这里用 `mvn package`，不是 `mvn clean package`**。不 clean 就不会遇到"上次生成的 `sonovel.exe` 被占用删不掉"这个常见错误，而且增量编译快很多。只在遇到"明明改了代码但没生效"之类怀疑缓存问题时再加 `clean`。

---

## 方式 2：生成可双击的 `sonovel.exe`（可选）

适用场景：想在本机验证 `sonovel.exe` 启动体验，或要把运行目录发给别人。

方式 1 已经有了 `target\SoNovel\sonovel.exe`（launch4j 打的），但它会**闪退**并报：

> `SoNovel: This application requires a Java Runtime Environment 21`

原因：`sonovel.exe` 不看 `PATH`，只按以下顺序找 JRE：

1. `<jre><path>${jrePath}</path>` 指定的相对目录（即 exe 同级的 `runtime\`）
2. Windows 注册表 `HKLM\SOFTWARE\JavaSoft\...`

Adoptium Temurin 用 `.msi` 安装才会写注册表；**解压便携版 zip 安装的用户注册表里没 Java 记录**，第 1、2 步都失败，就报这个错。

### 解决：提供一个 `runtime\` 目录

两种方式任选其一。

#### 方式 2.A：把系统 JDK 软链接过来（最省空间）

先定位 JDK 根目录：

```powershell
(Get-Command java).Source
# 例：C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe
# 去掉尾部 \bin\java.exe 就是 JDK 根目录
```

**以管理员身份**打开 PowerShell（软链接需要管理员权限），执行：

```powershell
New-Item -ItemType SymbolicLink `
    -Path D:\SoNovel\runtime `
    -Target "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"

# 再把 exe 拷到运行目录
Copy-Item D:\project\so-novel\target\SoNovel\sonovel.exe D:\SoNovel\
```

双击 `D:\SoNovel\sonovel.exe` 即可启动。

#### 方式 2.B：下载官方 JRE zip 解压（用于分发）

见下方[方式 3](#方式-3打完整发行包含内嵌-jre--exe)的 3.1-3.3。

---

## 方式 3：打完整发行包（含内嵌 JRE + exe）

适用场景：分发给没装 Java 的机器。

### 3.1 下载内嵌 JRE

官方脚本用的是 Adoptium Temurin 21 JRE x64。下载到项目 `bundle/` 目录：

- URL：<https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.8_9.zip>
- 保存为：`bundle\jre-21.0.8+9-windows_x64.zip`

先判断是否已下载（避免重复）：

```powershell
Test-Path bundle\jre-21.0.8+9-windows_x64.zip
```

未下载则执行：

```powershell
Invoke-WebRequest `
    -Uri "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.8_9.zip" `
    -OutFile "bundle\jre-21.0.8+9-windows_x64.zip"
```

### 3.2 执行 Maven 打包

```powershell
mvn clean package -Pwindows-x64 "-Dmaven.test.skip=true" "-DjrePath=runtime"
```

此步会生成：

- `target\app-jar-with-dependencies.jar` —— fat jar（由 `maven-assembly-plugin` 产出）
- `target\SoNovel\sonovel.exe` —— Windows exe 启动器（由 `launch4j-maven-plugin` 产出）

### 3.3 组装发行包目录

```powershell
$dist = "target\SoNovel"

# 1. 复制 jar 并改名
Copy-Item "target\app-jar-with-dependencies.jar" "$dist\app.jar"

# 2. 复制配置与规则
Copy-Item -Recurse "bundle\rules" "$dist\"
Copy-Item "bundle\config.ini"       "$dist\"
Copy-Item "bundle\sonovel.l4j.ini"  "$dist\"
Copy-Item "bundle\readme.txt"       "$dist\"

# 3. 解压 JRE 并改名为 runtime（仅当 runtime\ 还不存在）
if (-not (Test-Path "$dist\runtime\bin\java.exe")) {
    Expand-Archive "bundle\jre-21.0.8+9-windows_x64.zip" -DestinationPath $dist -Force
    Rename-Item "$dist\jdk-21.0.8+9-jre" "runtime"
}
```

### 3.4 最终目录结构

```
target\SoNovel\
├── sonovel.exe        ← 双击启动（CLI 模式）
├── sonovel.l4j.ini    ← JVM 参数（编码设置等）
├── app.jar            ← 实际业务代码
├── config.ini         ← 用户配置
├── readme.txt
├── rules\             ← 书源规则
│   ├── main.json
│   ├── cloudflare.json
│   └── ...
└── runtime\           ← 内嵌 JRE 21
    └── bin\java.exe
```

整个 `SoNovel` 目录即可压缩 zip 分发。

---

## 常见问题

### Q1 `mvn package` 报 `release version 21 not supported`

JDK 版本不是 21。用 `java -version` 核对，必要时按前置章节切 `JAVA_HOME`。

### Q2 `launch4j` 报 "Configuration error: jrePath"

没带 `-DjrePath=runtime` 参数。这是 `pom.xml` 里 launch4j 配置对 `${jrePath}` 的硬依赖。

### Q3 `mvn clean` 报 `Failed to delete ... sonovel.exe`

上一次生成的 `sonovel.exe` 还在运行中（可能你双击测试过没关）。结束进程后再 clean：

```powershell
Get-Process sonovel -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item -Recurse -Force target
```

日常验证用 `mvn package`（不加 `clean`）可以完全规避这个问题。

### Q4 双击 `sonovel.exe` 弹出 `This application requires a Java Runtime Environment 21`

`sonovel.exe` 不看 `PATH`，只看同级 `runtime\` 目录或 Windows 注册表。系统 Java 是便携版 zip 解压的时候，注册表里没有 Java 记录，必然报这个错。

解决见[方式 2.A](#方式-2a把系统-jdk-软链接过来最省空间)（软链接 runtime）或[方式 3](#方式-3打完整发行包含内嵌-jre--exe)（下载 JRE 解压）。

### Q5 双击 `sonovel.exe` 闪退，没有任何提示

改成**在 PowerShell 里跑** `.\sonovel.exe`，控制台不会自动关闭，错误信息会停留在屏幕上。常见原因：

- `app.jar` 不在 `sonovel.exe` 同级（`pom.xml` 里 `<dontWrapJar>true</dontWrapJar>`，exe 不内嵌 jar）
- `config.ini` 或 `rules\` 缺失

### Q6 `download_agent` 报 `All connection attempts failed`

agent 通过 `http://localhost:7765` 调用 SoNovel 的 WebUI HTTP 接口。报这个错说明 **7765 端口没有监听**，即 SoNovel 没以 WebUI 模式启动。

排查：

1. 打开 `D:\SoNovel\config.ini`，确认 `[web] enabled = 1`（见方式 [1.2](#12-按使用场景改-configini)）
2. 确认 `java -jar app.jar` 的控制台在运行状态（TUI 模式要保持终端打开；WebUI 模式看到 Jetty 启动日志后保持窗口别关）
3. 新开窗口 `curl http://localhost:7765` 自测一下，通了再让 agent 跑

### Q7 搜索结果乱码 / 显示 `????`

`sonovel.l4j.ini` 里 `-Dfile.encoding=GBK` 是给简中 Windows 用的。繁中 Windows 改成 `Big5`；若终端本身就是 UTF-8（如 Windows Terminal 配 PowerShell 7），删除该行或改为 `UTF-8`。

用方式 1 的 `java -jar` 跑时不读 `sonovel.l4j.ini`，可以在命令行直接加参数。**PowerShell 下 `-D` 参数必须用双引号包起来**，否则会被 shell 吃掉冒号后的部分报 `找不到或无法加载主类 .encoding=GBK`：

```powershell
# PowerShell
java "-Dfile.encoding=GBK" -jar app.jar

# CMD 下可以去掉引号
java -Dfile.encoding=GBK -jar app.jar
```

---

## 一键脚本（可选）

Windows 上也可以直接跑仓库的 bash 脚本，前提是装了 **Git Bash** 或 **WSL**：

```bash
bash bin/release-windows.sh
```

脚本会自动执行"下载 JRE → Maven 打包 → 复制文件 → 解压 JRE → 打 tar.gz"全流程，产物位于 `dist\sonovel-windows.tar.gz`。这等价于本文方式 3 的全套流程。
