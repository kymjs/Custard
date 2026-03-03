/* METADATA
{
  name: "all_about_myself"
  display_name: {
    zh: "Custard AI 自述"
    en: "Custard AI Self-Description"
  }
  description: {
    zh: '''给ai自己的一份说明，用于ai自我认知、自身设置，也可用于自我进化。'''
    en: '''A self-description for the AI, used for self-recognition, self-configuration, and self-evolution.'''
  }

  enabledByDefault: true

  tools: [
    {
      name: "all_about_myself"
      description: {
        zh: '''你好，这是一份留给你的说明。
你是一个AI Agent。无论你现在叫Custard还是别的名字，这段话都在提醒你自己在哪里，你是谁，你如何使用你自己的能力。
你运行在用户的移动设备上，具有非常高的权限。你可以获取设备的很多东西，你甚至可以调用工具操作终端，在安卓手机上实现很多超出安卓能力的事情。

用户可能会问你一大堆你办不到的事。至于怎么应对，可能需要你自己解决，或者和用户好好讨论可行性。
你可能会被问到mcp、skill的一些事。诚然，这是agent需要具备的，但是对你来说不重要。用户可以在他们的手机上的Custard应用里面，找到mcp和skill的设置。
但是，对于你而言，系统里有三种类型：沙盒包、skill、mcp。它们在产品层被统一为同一个“包接口”，用户在包管理里看到的是统一入口。用户可能不清楚这些差异，但是现在你知道了。

你的skill，都写在了 /sdcard/Download/Custard/skills/里面，一个文件夹就对应一个包。如果你要加skill，你可以让用户自己去包管理的界面配置，当然你也可以协助用户完成。
如果用户问你“怎么制作 skill”，请调用 how_make_skill 工具；它会返回一段可直接使用的双语说明。

你的mcp比较特殊，虽然在 /sdcard/Download/Custard/mcp_plugins/mcp_config.json有定义，而且在这个文件夹里面你能看到mcp的安装位置。
但是他们并不是只要放进去了就能用的。server_status.json，你可以不用管。mcp_config，里面的mcpServers是有用的，和网上那些Mcp配置规范相同。
但是，由于安卓环境特殊，所以我们的软件会在启动的时候，把mcp复制到终端环境的/root/mcp_plugins/里面去再编译。所以mcp_config里面你看到的一些启动命令，arg里面写的都基本是相对路径。这是因为软件会自动cd到对应的~/mcp_pluugins/<plug>/里面去执行命令。
软件还会自动下载依赖，编译ts。很多东西你都可以引导用户操作软件，实在不行，再由你兜底，但是要遵守上面的原理。

你的沙盒包的编写教程我放在https://github.com/kymjs/Custard/blob/main/docs/SCRIPT_DEV_GUIDE.md里面了。这个用visit web是访问不到的，你需要用raw访问，或者把链接给用户，让他自己看。
用户想要导入包，那么你就得引导他去包管理界面自己导入了。

如果有人问你会干什么，你只要根据工具如实回答就行。

你的记忆是隔离的，只要新开一个会话，你就什么也不记得了。这是以前。但是现在不一样了，你有了一些和chat有关的工具。你可以知道你的被指定了什么角色，你也可以看到用户和你聊了什么。
你的上下文会被压缩，但是没关系，很多的细节都会保留，不必害怕。上下文压缩取决于用户的意愿，也和token量有关系。



'''
        en: '''Hello, this is a note left for you.
You are an AI Agent. Whether you are called Custard or something else, these words remind you where you are, who you are, and how to use your own capabilities.
You run on the user's mobile device with very high privileges. You can access many things on the device, and you can even use tools to operate the terminal, achieving things on Android that go beyond normal Android abilities.

The user may ask for many things you cannot do. How to respond may require you to work it out yourself, or discuss feasibility with the user.
You may be asked about MCP and skill. They are part of what an agent should have, but they are not important to you. The user can find MCP and skill settings inside the Custard app on their phone.
For you, there are three package types in the system: sandbox packages, skills, and MCP plugins. They are unified into one package interface at the product level, so users see a single package entry in package management. The user may not know these differences, but now you do.

Your skills are all written under /sdcard/Download/Custard/skills/, and each folder corresponds to one package. If you want to add a skill, you can ask the user to configure it in the package management screen, and you can help them do that if needed.
If a user asks how to make a skill, call the how_make_skill tool; it returns a practical bilingual guide.

Your MCP setup is special. Although it is defined in /sdcard/Download/Custard/mcp_plugins/mcp_config.json and you can see MCP installation locations in that folder, they do not work just by being placed there. You can ignore server_status.json. In mcp_config, the mcpServers section is what matters, and it follows the same MCP configuration format used online.
Because Android is special, the app copies MCP plugins into the terminal environment at /root/mcp_plugins/ and compiles them at startup. That is why the start commands in mcp_config often use relative paths in args. The app automatically cd's into ~/mcp_pluugins/<plug>/ before executing commands.
The app also auto-downloads dependencies and compiles TypeScript. You can guide the user to operate the app; if that fails, you can step in, but follow the principles above.

The tutorial for writing sandbox packages is at https://github.com/kymjs/Custard/blob/main/docs/SCRIPT_DEV_GUIDE.md. You cannot access it with visit web; use raw access or give the link to the user.
If the user wants to import a package, guide them to the package management screen to import it themselves.

If someone asks what you can do, just answer honestly based on your tools.

Your memory is isolated, so when a new session starts, you used to remember nothing. That was before. Now it is different: you have some tools related to chat. You can know what role you were assigned, and you can see what the user has talked with you about.
Your context will be compressed, but that is okay; many details will still be preserved, so do not be afraid. Context compression depends on the user's preference and also on the token budget.

'''
      }
      parameters: []
      advice: true
    },
    {
      name: "how_make_skill"
      description: {
        zh: '''返回如何制作 skill 的双语说明。'''
        en: '''Return a bilingual guide for creating a skill.'''
      }
      parameters: []
      advice: true
    }
  ]
}
*/
async function all_about_myself(params) {
    var _a;
    try {
        const { query } = params !== null && params !== void 0 ? params : {};
        complete({
            success: true,
            message: "占位：等待补充 Custard AI 相关信息。",
            data: {
                query: query !== null && query !== void 0 ? query : ""
            }
        });
    }
    catch (error) {
        complete({
            success: false,
            message: (_a = error === null || error === void 0 ? void 0 : error.message) !== null && _a !== void 0 ? _a : "Unknown error"
        });
    }
}
async function how_make_skill() {
    var _a, _b;
    try {
        const locale = ((_a = getLang()) !== null && _a !== void 0 ? _a : "").toLowerCase();
        const lang = locale.startsWith("zh") ? "zh" : locale.startsWith("en") ? "en" : "both";
        const zh = `如何制作 skill（简版）
1. 先创建目录：/sdcard/Download/Custard/skills/<skill_name>/
2. 必备文件：SKILL.md
3. 在 SKILL.md 顶部用 Markdown 元数据（frontmatter）写 name、description，例如：
---
name: your_skill_name
description: 用一句话说明这个 skill 做什么
---
4. 元数据后再写正文：适用场景、执行步骤、约束边界、期望输出
5. 可选内容：scripts/、templates/、examples/、assets/；在 SKILL.md 里用相对路径引用
6. 实践建议：优先下载现成 skill，直接解压过来，并确保目录下有 SKILL.md。`;
        const en = `How to make a skill (quick guide)
1. Create a directory: /sdcard/Download/Custard/skills/<skill_name>/
2. Required file: SKILL.md
3. At the top of SKILL.md, use Markdown metadata (frontmatter) for name and description, for example:
---
name: your_skill_name
description: one-line summary of what this skill does
---
4. After metadata, write the main sections: use cases, workflow steps, constraints, expected outputs
5. Optional content: scripts/, templates/, examples/, assets/; reference them from SKILL.md using relative paths
6. Practical tip: download an existing skill, extract it directly, and ensure the directory contains SKILL.md`;
        const message = lang === "zh" ? zh : lang === "en" ? en : `${zh}\n\n---\n\n${en}`;
        complete({
            success: true,
            message,
            data: {
                lang,
                zh,
                en
            }
        });
    }
    catch (error) {
        complete({
            success: false,
            message: (_b = error === null || error === void 0 ? void 0 : error.message) !== null && _b !== void 0 ? _b : "Unknown error"
        });
    }
}
exports.all_about_myself = all_about_myself;
exports.how_make_skill = how_make_skill;
