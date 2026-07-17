[![OSL](https://cdn.kymjs.com:8843/qiniu/image/logo3.png)](https://kymjs.com/works/)
=================

## 特殊许可

特殊授权许可声明：本项目所有代码仅面向中国大陆地区授权(不含港澳台海南)可免费使用。如有使用在中国大陆之外地区，需缴纳一千万人民币的一次性使用授权费。本使用定义为对源代码、图片资源、及其衍生编译的文本和二进制产物的所有行为，包括不限于学习、教育、商业使用。  
本特殊许可在与开源许可冲突部分，以本许可为准。本人对本特殊许可享有最终解释权。  
本许可自2026年3月23日11时00分生效。  


---

<br>

<div align="center">  
  以猫之名，赋 AI 以灵  

**关于名字**：奶黄包，是为了纪念一只因心脏病离开的加菲猫而命名。

</div>

<br>

一个专用于**操作手机**的 AI Agent。可独立运行，也可以作为扩展提供给其他Agent调用，拥有强大的工具调用能力。  

* 演示视频：[【硬核打工手册的作品】Agent 控制手机 手机端的 Agent，能单独... ](https://v.douyin.com/BOnxPZU09WM)
* 应用下载：点击下方图例中的压缩包即可 [https://github.com/kymjs/Custard/releases](https://github.com/kymjs/Custard/releases)  

<img width="1770" height="1026" alt="image" src="https://github.com/user-attachments/assets/d102fe77-2fe1-4a79-bb0c-90ede6eb3cf7" />


<br>

### 新增桌面端PC，让手机控制更自然(当然，脱离桌面端也能单独运行)。

开放生态，让其他 Agent 也能直接控制手机。  
Custard 用 **H.264 硬件编码** 画面跟手、延迟低。你在 Mac 上用鼠标点、键盘打，手机立刻响应——像在用一块外接触摸屏。

<br>

### 插线就能用，不折腾 WiFi

- **USB 连接**：数据线插上，点「USB 连接」，免配网
- **WiFi 直连**：同一局域网也行

两种通道都支持远程触控和键盘输入，日常开发、演示、远程协助都顺手。

<br>

### Mac ↔ 手机剪贴板互通

电脑上复制的链接、代码、文字，手机粘贴就有；手机复制的内容，Mac 也能直接拿到。**跨设备协作少切一次屏。**

<br>

### 为大模型而生的「眼睛」和「手」

Custard 不只是投屏工具，它给 AI 装上了：

| 能力 | 说明 |
|------|------|
| **读屏** | 获取当前界面结构、前台应用，可选附带截图 |
| **点击 / 滑动** | 百分比坐标，AI 不用猜像素 |
| **输入文字** | 支持中文（奶黄包输入法） |
| **开应用** | 说「打开微信」就行 |
| **Home / 返回** | 系统导航一键完成 |
| **剪贴板读写** | 跨设备传内容 |

内置聊天、Cursor Skill、MCP、本地 HTTP API——**同一套能力，多种用法**，你用什么 AI 工具都能接上。

<br>

### 安全在本机

Agent API 只监听 `127.0.0.1`，不暴露到公网。Token 鉴权、工具开关、操作审计日志——**你能控什么、AI 能做什么，一目了然。**

---

<br>

## 三分钟上手

### 1. 安装 Custard

1. Mac 打开 **CustardMac**，连接手机会自动安装 **Custard Android**
2. 手机开启无障碍服务，授权屏幕录制
3. USB 或 WiFi 连接成功（CustardMac 显示已连接）

### 2. 安装本 Skill

在 CustardMac 的 **Agent 端口** 页面：

1. 开启 Agent API
2. 点击 **「安装 Skill」**

或手动：

```bash
git clone --depth 1 https://github.com/kymjs/Custard-Skill.git \
  ~/.cursor/skills/custard-phone-control
```

然后在 `scripts/config.env` 里填入 CustardMac 显示的 Token。

### 3. 验证

```bash
bash ~/.cursor/skills/custard-phone-control/scripts/custard-tool status
```

看到 `phone_connected: true`，就可以在 Cursor 里让 AI 操作手机了。

---

## 试试这些指令

在 Cursor 对话里直接说（需本机 Agent，Cloud Agent 无法访问 localhost）：

```
帮我看一下手机现在在什么界面
```

```
打开微信，点搜索框，输入「奶黄包」
```

```
把手机剪贴板的内容读给我
```

AI 会自动调用读屏、点击、输入等能力——**你描述目标，它执行步骤。**

---

<br>

## 常见问题

**Q：iPhone 手机能用吗？**  
目前仅支持 **Android**，也在做 **iOS**、**Harmony**版本，但**iOS**复杂度更高，需要等一段时间。  

**Q：必须安装 Agent 才能用 Custard 吗？**  
不必须，Custard 本身就是一个 Agent，完全不需要其他 Agent。

**Q：支持哪些三方 Agent？**  
Custard 已适配的 Agent 有：Hermes、OpenClaw、Cursor、Codex。其他国产Agent没有做单独测试，但理论上也能支持。

**Q：Cloud Agent 能用吗？**  
不能。API 在本机，请用 **本机 Agent**。

**Q：银行或股票证券 App 能调用吗？**  
部分安全界面（FLAG_SECURE）无法截图或读 UI，这是系统限制。大部分证券 APP 都能正常使用。


## 以下为部分运行截图
<img width="2186" height="1628" alt="image" src="https://github.com/user-attachments/assets/9a5f04f8-755e-4b35-8751-78ffd5453ed9" />

<img width="2186" height="1628" alt="image" src="https://github.com/user-attachments/assets/c20c1709-3b1f-469b-94be-972a2b0d7e4f" />

<img width="2186" height="1628" alt="image" src="https://github.com/user-attachments/assets/587c9639-8503-420b-a822-45eb3f75e49a" />



