#!/usr/bin/env bash
# 安装「奶黄包」手机控制 Skill（含读屏子 Skill、Cursor Rule、MCP 配置）
# 对外品牌名：奶黄包；技术目录名：custard-phone-control
#
# 用法:
#   bash install.sh [Token] [--skip-clone] [--link-project [DIR]]
#
# 奶黄包「Agent 端口」→「安装 Skill」效果相同（带 --skip-clone）
set -euo pipefail

SKILL_NAME="custard-phone-control"
SCREEN_SKILL_NAME="android-phone-screen"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SKILLS_ROOT="${CURSOR_SKILLS_DIR:-$HOME/.cursor/skills}"
DEST="$SKILLS_ROOT/$SKILL_NAME"
SCREEN_DEST="$SKILLS_ROOT/$SCREEN_SKILL_NAME"
TOKEN=""
SKIP_CLONE=false
LINK_PROJECT=""
GITHUB_URL="https://github.com/kymjs/Custard-Skill.git"

usage() {
  cat <<'EOF'
Usage: bash install.sh [Token] [--skip-clone] [--link-project [DIR]]

  --skip-clone       跳过 git clone（已由 CustardMac 或本地复制完成）
  --link-project     在当前或指定项目创建 .cursor/skills 符号链接
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-clone) SKIP_CLONE=true; shift ;;
    --link-project)
      if [[ $# -ge 2 && "$2" != --* ]]; then
        LINK_PROJECT="$2"
        shift 2
      else
        LINK_PROJECT="$(pwd)"
        shift
      fi
      ;;
    -h|--help) usage; exit 0 ;;
    *)
      if [[ -z "$TOKEN" ]]; then
        TOKEN="$1"
      else
        echo "Unknown argument: $1" >&2
        usage >&2
        exit 1
      fi
      shift
      ;;
  esac
done

mkdir -p "$SKILLS_ROOT"

# 备份已有 config.env
TEMP_CONFIG=""
if [[ -f "$DEST/scripts/config.env" ]]; then
  TEMP_CONFIG="$(mktemp)"
  cp "$DEST/scripts/config.env" "$TEMP_CONFIG"
fi

if [[ "$SKIP_CLONE" == true ]]; then
  if [[ ! -f "$DEST/SKILL.md" ]]; then
    echo "Error: --skip-clone but $DEST/SKILL.md not found." >&2
    exit 1
  fi
elif command -v git >/dev/null 2>&1; then
  if [[ -d "$DEST/.git" ]]; then
    echo "Updating existing clone..."
    git -C "$DEST" pull --ff-only
  else
    echo "Cloning from $GITHUB_URL ..."
    rm -rf "$DEST"
    git clone --depth 1 "$GITHUB_URL" "$DEST"
  fi
elif [[ -f "$SCRIPT_DIR/SKILL.md" ]]; then
  echo "git not found, copying local skill files..."
  rm -rf "$DEST"
  cp -R "$SCRIPT_DIR" "$DEST"
else
  echo "Error: install git or run from skill source directory." >&2
  exit 1
fi

chmod +x "$DEST/scripts/custard-tool" 2>/dev/null || true
chmod +x "$DEST/scripts/custard-mcp-server.mjs" 2>/dev/null || true
chmod +x "$DEST/install.sh" 2>/dev/null || true

mkdir -p "$DEST/scripts"
CONFIG="$DEST/scripts/config.env"
if [[ -n "$TEMP_CONFIG" && -f "$TEMP_CONFIG" ]]; then
  cp "$TEMP_CONFIG" "$CONFIG"
  rm -f "$TEMP_CONFIG"
elif [[ -n "$TOKEN" ]]; then
  cat > "$CONFIG" <<EOF
CUSTARD_AGENT_TOKEN=$TOKEN
CUSTARD_TOOL_HOST=127.0.0.1
CUSTARD_TOOL_PORT=27184
CUSTARD_SKILL_DIR=$DEST
CUSTARD_TOOL_SOURCE=agent
EOF
else
  if [[ -f "$DEST/scripts/config.env.example" ]]; then
    cp "$DEST/scripts/config.env.example" "$CONFIG"
  else
    touch "$CONFIG"
  fi
  echo "CUSTARD_SKILL_DIR=$DEST" >> "$CONFIG"
  echo "CUSTARD_TOOL_SOURCE=agent" >> "$CONFIG"
fi

if [[ -n "$TOKEN" ]]; then
  if grep -q '^CUSTARD_AGENT_TOKEN=' "$CONFIG"; then
    if [[ "$(uname)" == Darwin ]]; then
      sed -i '' "s/^CUSTARD_AGENT_TOKEN=.*/CUSTARD_AGENT_TOKEN=$TOKEN/" "$CONFIG"
    else
      sed -i "s/^CUSTARD_AGENT_TOKEN=.*/CUSTARD_AGENT_TOKEN=$TOKEN/" "$CONFIG"
    fi
  else
    echo "CUSTARD_AGENT_TOKEN=$TOKEN" >> "$CONFIG"
  fi
fi

if ! grep -q '^CUSTARD_SKILL_DIR=' "$CONFIG"; then
  echo "CUSTARD_SKILL_DIR=$DEST" >> "$CONFIG"
fi

# shellcheck source=/dev/null
source "$CONFIG"
HOST="${CUSTARD_TOOL_HOST:-127.0.0.1}"
PORT="${CUSTARD_TOOL_PORT:-27184}"
MCP_TOKEN="${CUSTARD_AGENT_TOKEN:-}"

# P2: 安装读屏子 Skill
install_screen_skill() {
  local src="$DEST/android-phone-screen"
  local skill_file="$src/SKILL.md"
  if [[ ! -f "$skill_file" && -f "$src/android-phone-screen.skill.md" ]]; then
    skill_file="$src/android-phone-screen.skill.md"
  fi
  if [[ ! -f "$skill_file" ]]; then
    echo "Warning: android-phone-screen skill file missing, skip." >&2
    return 0
  fi
  rm -rf "$SCREEN_DEST"
  mkdir -p "$SCREEN_DEST"
  cp "$skill_file" "$SCREEN_DEST/SKILL.md"
  echo "Installed screen skill: $SCREEN_DEST"
}

# P0: Cursor Rule
install_cursor_rule() {
  local rule_dir="${CURSOR_RULES_DIR:-$HOME/.cursor/rules}"
  local template="$DEST/templates/custard-android.mdc"
  local rule_file="$rule_dir/custard-android.mdc"
  mkdir -p "$rule_dir"
  if [[ -f "$template" ]]; then
    cp "$template" "$rule_file"
  else
    cat > "$rule_file" <<'EOF'
---
description: Route Android真机读屏/截图/操控到奶黄包 Skills（custard-phone-control / android-phone-screen）
alwaysApply: true
---

# 奶黄包 Android 真机

涉及 Android 真机读屏、截图、点击、输入，或提及「奶黄包」时，优先 `android-phone-screen`（奶黄包读屏）或 `custard-phone-control`（奶黄包操控），通过 custard-tool 或 MCP custard 调用。
EOF
  fi
  echo "Installed Cursor rule: $rule_file"
}

# P1: MCP 配置（合并 ~/.cursor/mcp.json）
install_mcp_config() {
  command -v python3 >/dev/null 2>&1 || {
    echo "Warning: python3 not found, skip mcp.json." >&2
    return 0
  }
  command -v node >/dev/null 2>&1 || {
    echo "Warning: node not found, skip mcp.json." >&2
    return 0
  }
  local mcp_script="$DEST/scripts/custard-mcp-server.mjs"
  if [[ ! -f "$mcp_script" ]]; then
    echo "Warning: $mcp_script missing, skip mcp.json." >&2
    return 0
  fi
  python3 - "$mcp_script" "$HOST" "$PORT" "$MCP_TOKEN" <<'PY'
import json, os, sys
mcp_script, host, port, token = sys.argv[1:5]
path = os.path.expanduser("~/.cursor/mcp.json")
data = {}
if os.path.isfile(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
servers = data.setdefault("mcpServers", {})
env = {
    "CUSTARD_TOOL_HOST": host,
    "CUSTARD_TOOL_PORT": port,
}
if token:
    env["CUSTARD_AGENT_TOKEN"] = token
servers["custard"] = {
    "command": "node",
    "args": [mcp_script],
    "env": env,
}
os.makedirs(os.path.dirname(path), exist_ok=True)
with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2, ensure_ascii=False)
    f.write("\n")
print(f"Updated MCP config: {path}")
PY
}

# P2: 项目级 Skill 符号链接
link_project_skills() {
  local root="$LINK_PROJECT"
  if [[ -z "$root" ]]; then
    return 0
  fi
  if [[ ! -d "$root" ]]; then
    echo "Warning: project dir not found: $root" >&2
    return 0
  fi
  local proj_skills="$root/.cursor/skills"
  mkdir -p "$proj_skills"
  ln -sfn "$DEST" "$proj_skills/$SKILL_NAME"
  if [[ -f "$SCREEN_DEST/SKILL.md" ]]; then
    ln -sfn "$SCREEN_DEST" "$proj_skills/$SCREEN_SKILL_NAME"
  fi
  echo "Linked project skills under: $proj_skills"
}

install_screen_skill
install_cursor_rule
install_mcp_config
if [[ -n "$LINK_PROJECT" ]]; then
  link_project_skills
fi

echo ""
echo "Done: $DEST"
echo "Screen skill: $SCREEN_DEST"
echo "Run: bash \"$DEST/scripts/custard-tool\" status"
echo "MCP: restart Cursor after mcp.json update (if configured)."
