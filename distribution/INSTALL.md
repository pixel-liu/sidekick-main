# SideKick 安装

SideKick 需要 Java 17 或更高版本。解压发行包后，将其中的 `bin` 目录加入 `PATH`。

## Windows

假设发行包解压到 `%LOCALAPPDATA%\SideKick`，将以下目录加入当前用户的 `PATH`：

```text
%LOCALAPPDATA%\SideKick\bin
```

重新打开终端，在需要操作的项目目录中运行：

```powershell
cd D:\code\my-project
sidekick
```

## macOS / Linux

将发行包解压到固定目录，并创建命令链接：

```bash
sudo ln -s /opt/sidekick/bin/sidekick /usr/local/bin/sidekick
```

然后在需要操作的项目目录中运行：

```bash
cd ~/code/my-project
sidekick
```

启动器不会改变当前工作目录，因此 SideKick 会将启动命令所在目录作为项目根。模型 API Key 可使用系统环境变量、当前项目的 `.env`，或用户主目录下的 `.env` 配置；不要将真实密钥提交到代码仓库。

需要传递 JVM 参数时可使用 Java 自带的 `JAVA_TOOL_OPTIONS` 环境变量。
