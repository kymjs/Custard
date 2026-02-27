import argparse
import json
import os
import subprocess
import sys
import textwrap
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional


def _decode_output(data: Optional[bytes]) -> str:
    if not data:
        return ""
    return data.decode("utf-8", errors="replace")


def _run_git(args: list[str], cwd: Path) -> str:
    p = subprocess.run(
        ["git", *args],
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed:\n{_decode_output(p.stderr).strip()}")
    return _decode_output(p.stdout)


def _repo_root() -> Path:
    p = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if p.returncode != 0:
        raise RuntimeError("Not a git repository (or git not installed).")
    return Path(_decode_output(p.stdout).strip())


def _load_env(env_path: Path) -> None:
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip().strip('"').strip("'")
        if k and k not in os.environ:
            os.environ[k] = v


def _openai_chat_completion(base_url: str, api_key: str, payload: dict) -> dict:
    url = base_url.rstrip("/") + "/v1/chat/completions"
    data = json.dumps(payload).encode("utf-8")

    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {api_key}")

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8", errors="replace")
        except Exception:
            pass
        raise RuntimeError(f"AI request failed: HTTP {e.code} {e.reason}\n{body}")


def _get_staged_diff(cwd: Path, max_chars: int) -> str:
    diff = _run_git(["diff", "--cached"], cwd=cwd)
    if len(diff) <= max_chars:
        return diff
    return diff[:max_chars] + "\n\n[diff truncated]\n"


def _get_status(cwd: Path) -> str:
    return _run_git(["status", "--porcelain"], cwd=cwd).strip()


def _get_stat(cwd: Path) -> str:
    return _run_git(["diff", "--cached", "--stat"], cwd=cwd).strip()


def _read_multiline_user_input(header: str) -> str:
    print(header)
    lines: list[str] = []
    while True:
        try:
            line = input()
        except EOFError:
            break
        if not line.strip():
            break
        lines.append(line)
    return "\n".join(lines).strip()


def _extract_commit_subject(resp: dict) -> str:
    try:
        msg = resp["choices"][0]["message"]["content"]
    except Exception as e:
        raise RuntimeError(f"Unexpected AI response: {e}\n{json.dumps(resp, ensure_ascii=False)[:2000]}")

    subject = (msg or "").strip().splitlines()[0].strip()
    subject = subject.strip('"').strip("'")

    # Guard: keep it one-line and reasonably short
    if not subject:
        raise RuntimeError("AI returned an empty commit message.")
    
    return subject


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate an English commit message using an OpenAI-compatible API, then run git commit."
    )
    parser.add_argument(
        "--env",
        default=str(Path(__file__).resolve().parent / ".env"),
        help="Path to .env file (default: tools/github/.env)",
    )
    parser.add_argument(
        "--max-diff-chars",
        type=int,
        default=12000,
        help="Max staged diff characters sent to AI",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="Do not prompt; commit immediately",
    )
    parser.add_argument(
        "--push",
        action="store_true",
        help="Run git push after commit",
    )
    parser.add_argument(
        "--stage-all",
        action="store_true",
        help="Run git add -A before generating message",
    )
    parser.add_argument(
        "--extra-zh",
        default="",
        help="Optional extra notes in Chinese to help the AI write a better English commit subject",
    )
    args = parser.parse_args()

    _load_env(Path(args.env))

    base_url = os.environ.get("AI_BASE_URL", "").strip()
    api_key = os.environ.get("AI_API_KEY", "").strip()
    model = os.environ.get("AI_MODEL", "").strip() or "gpt-4o-mini"
    temperature = float(os.environ.get("AI_TEMPERATURE", "0.2") or 0.2)

    if not base_url:
        print("Missing AI_BASE_URL in env.", file=sys.stderr)
        return 2
    if not api_key:
        print("Missing AI_API_KEY in env.", file=sys.stderr)
        return 2

    root = _repo_root()

    if args.stage_all:
        _run_git(["add", "-A"], cwd=root)

    status = _get_status(root)
    if not status:
        print("No changes to commit (working tree clean).")
        return 0

    stat = _get_stat(root)
    diff = _get_staged_diff(root, max_chars=args.max_diff_chars)

    if not diff.strip():
        print(
            "No staged changes found. Stage changes first (git add ...) or run with --stage-all.",
            file=sys.stderr,
        )
        return 2

    extra_zh = (args.extra_zh or "").strip()
    if not extra_zh and (not args.yes) and sys.stdin.isatty():
        extra_zh = _read_multiline_user_input(
            "Optional: enter extra notes in Chinese (press Enter on an empty line to finish; leave blank to skip):"
        )

    system_prompt = (
        "You are an expert software engineer writing a git commit subject line. "
        "You must base the subject on the provided staged change summary (git diff --cached --stat) "
        "and the staged diff, plus optional author notes. "
        "Your subject must reflect ALL meaningful staged changes, including small or scattered updates; "
        "do not ignore secondary changes. "
        "If the staged changes span multiple areas and a single narrow theme would omit changes, "
        "choose a broader but accurate subject and optionally use 'and/plus' or a 'misc'/'follow-up' phrasing "
        "to cover secondary updates while staying concise. "
        "If the diff content is truncated (contains '[diff truncated]'), avoid overly specific wording and rely more on --stat. "
        "Never invent changes that are not present in the inputs. "
        "The output must be a single-line English subject."
    )

    user_prompt = textwrap.dedent(
        f"""
        Write a single-line English git commit subject for the following staged changes.

        Rules:
        - Output ONLY the subject line.
        - Must be English, even if the author notes are Chinese.
        - Imperative mood (e.g., 'Fix', 'Add', 'Refactor').
        - Prefer Conventional Commits if obvious (feat/fix/refactor/chore/docs/test/build), otherwise a normal subject.
        - No trailing period.
        - Try to keep <= 250 characters.

        Optional author notes (may be Chinese):
        {extra_zh or '[none]'}

        git diff --cached --stat:
        {stat}

        git diff --cached:
        {diff}
        """
    ).strip()

    payload = {
        "model": model,
        "temperature": temperature,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    }

    resp = _openai_chat_completion(base_url=base_url, api_key=api_key, payload=payload)
    subject = _extract_commit_subject(resp)

    print("Proposed commit message:\n")
    print(subject)
    print("")

    if not args.yes:
        ans = input("Commit with this message? [y/N] ").strip().lower()
        if ans not in ("y", "yes"):
            print("Aborted.")
            return 1

    _run_git(["commit", "-m", subject], cwd=root)
    print("Committed.")

    if args.push:
        _run_git(["push"], cwd=root)
        print("Pushed.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
