"""Prepare reviewed source; optionally publish source + a signed APK to GitHub.

GitHub credentials stay in memory via Git Credential Manager / GITHUB_TOKEN.
No signing keys, local accounts, private datasets or application data are copied.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

OWNER, REPO = "qingjiu1021-love", "WaferAI"
CERTIFICATE = "47a82580dd209e6c8f541e197866492ecafe0926a2c16b9d6e48aeae831a51e1"
DIRECTORIES = {"android", "backend", "docs", "help-site", "scripts", ".github"}
ROOT_FILES = {"README.md", ".gitignore", ".gitattributes", "CHANGELOG.md", "NOTICE.md",
              "启动账号测试服务.bat", "安装独立版到手机.bat", "发布更新到GitHub.bat"}
IGNORED = {".git", ".gradle", ".kotlin", ".idea", "build", "__pycache__", ".venv", "node_modules",
           ".wrangler", ".sites-runtime", "dist", ".next", ".vinext", ".test-work", "coverage", ".pytest_cache", ".agents", ".codex"}
SUFFIXES = {".apk", ".aab", ".log", ".pyc", ".pkl", ".pt", ".npy", ".npz", ".sqlite3", ".sqlite", ".db", ".jks", ".keystore", ".pem", ".key", ".tar", ".gz", ".zip", ".tsbuildinfo"}
SECRET = re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")


def command(args, cwd=None, capture=False):
    return subprocess.run([str(a) for a in args], cwd=cwd, check=True, text=True,
                          encoding="utf-8", errors="replace", stdout=subprocess.PIPE if capture else None).stdout


def files(source):
    for root, dirs, names in os.walk(source, followlinks=False):
        root = Path(root)
        relative = root.relative_to(source)
        dirs[:] = [d for d in dirs if d not in IGNORED and not (root / d).is_symlink()
                   and (relative.parts or d in DIRECTORIES)]
        for name in sorted(names):
            path = root / name
            if not relative.parts and name not in ROOT_FILES:
                continue
            if path.is_symlink() or path.suffix.lower() in SUFFIXES or name in {"local.properties", "next-env.d.ts", ".DS_Store"}:
                continue
            if (name.startswith(".env") and not name.endswith(".example")) or name.startswith(".dev.vars"):
                continue
            if ".db-" in name or ".sqlite" in name or name.startswith(".git") and name not in {".gitignore", ".gitattributes", ".gitkeep"}:
                continue
            yield path.relative_to(source)


def version(source):
    text = (source / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    return re.search(r'versionName = "(\d+\.\d+\.\d+)"', text)[1], int(re.search(r"versionCode = (\d+)", text)[1])


def stage(source, checkout):
    if checkout == source or checkout.is_relative_to(source) or source.is_relative_to(checkout):
        raise ValueError("Publishing checkout must be separate from the working project.")
    checkout.mkdir(parents=True, exist_ok=True)
    if not (checkout / ".git").exists():
        if any(checkout.iterdir()):
            raise ValueError("Publishing checkout must start empty.")
        command(["git", "init", "-b", "main", checkout])
    command(["git", "config", "core.autocrlf", "false"], cwd=checkout)
    command(["git", "config", "core.quotepath", "false"], cwd=checkout)
    existing = command(["git", "remote", "get-url", "origin"], cwd=checkout, capture=True) if command(["git", "remote"], cwd=checkout, capture=True).strip() else ""
    remote = f"https://github.com/{OWNER}/{REPO}.git"
    if existing.strip() not in {"", remote}:
        raise ValueError("Publishing checkout points to a different repository.")
    if not existing:
        command(["git", "remote", "add", "origin", remote], cwd=checkout)
    selected = sorted(files(source))
    for relative in selected:
        data = (source / relative).read_bytes()
        if len(data) > 20 * 1024 * 1024:
            raise ValueError(f"Unexpected large source file: {relative}")
        if SECRET.search(data):
            raise ValueError(f"Possible credential in source; review before publishing: {relative}")
    keep = {p.as_posix() for p in selected}
    tracked = command(["git", "ls-files", "-z"], cwd=checkout, capture=True).split("\0")
    for relative in filter(None, tracked):
        if relative not in keep:
            path = (checkout / relative).resolve()
            if not path.is_relative_to(checkout):
                raise ValueError("Unsafe tracked path")
            path.unlink(missing_ok=True)
    for relative in selected:
        target = checkout / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / relative, target)
    command(["git", "add", "--all"], cwd=checkout)
    paths = command(["git", "ls-files"], cwd=checkout, capture=True).splitlines()
    for required in ["android/app/src/main/java/com/example/waferai/data/account/AccountClient.kt", "android/app/src/main/java/com/example/waferai/data/account/GithubUpdates.kt", "android/gradle/wrapper/gradle-wrapper.jar", "backend/.env.accounts.example"]:
        if required not in paths:
            raise ValueError("Required source was excluded: " + required)
    print(json.dumps({"source_files": len(paths), "checkout": str(checkout), "repository": remote}, ensure_ascii=False))
    return selected


def github_credential():
    if os.environ.get("GITHUB_TOKEN"):
        return os.environ["GITHUB_TOKEN"]
    result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n", text=True, capture_output=True, check=True)
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    if not values.get("password"):
        raise ValueError("Sign into GitHub through Git Credential Manager first.")
    return values["password"]


def api(path, token, method="GET", body=None, raw=None):
    url = path if path.startswith("https://uploads.github.com/") else "https://api.github.com" + path
    data = raw if raw is not None else None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "User-Agent": "WaferAI-Publisher",
        "Content-Type": "application/vnd.android.package-archive" if raw is not None else "application/json"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def check_apk(source, apk, name, code):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = source / "android/local.properties"
    if not sdk and local.exists():
        sdk = re.search(r"^sdk.dir=(.+)$", local.read_text(), re.M)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        raise ValueError("Set ANDROID_HOME to verify the APK.")
    build_tools = sorted((Path(sdk) / "build-tools").iterdir(), key=lambda p: [int(v) for v in re.findall(r"\d+", p.name)])[-1]
    badging = command([build_tools / ("aapt2.exe" if os.name == "nt" else "aapt2"), "dump", "badging", apk], capture=True)
    for expected in ["name='com.example.waferai'", f"versionCode='{code}'", f"versionName='{name}'"]:
        if expected not in badging.splitlines()[0]:
            raise ValueError("APK package or version does not match source.")
    signed = command([build_tools / ("apksigner.bat" if os.name == "nt" else "apksigner"), "verify", "--print-certs", apk], capture=True)
    if CERTIFICATE not in signed.lower().replace(":", ""):
        raise ValueError("APK signing key differs from existing installations. Do not publish it as an update.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--checkout", type=Path)
    parser.add_argument("--publish", action="store_true", help="Push source and publish the APK (default only prepares source)")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--notes", type=Path)
    parser.add_argument("--checks-passed", action="store_true", help="Use only after this exact source passed build, unit tests and lint")
    args = parser.parse_args()
    source = args.source.resolve()
    checkout = (args.checkout or source.with_name(source.name + "-GitHub")).resolve()
    name, code = version(source)
    if args.publish and not args.checks_passed:
        command(["cmd", "/c", "gradlew.bat", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug", "--console=plain"] if os.name == "nt" else ["bash", "gradlew", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug", "--console=plain"], cwd=source / "android")
    stage(source, checkout)
    if not args.publish:
        return
    apk = (args.apk or source / "android/app/build/outputs/apk/debug/app-debug.apk").resolve()
    check_apk(source, apk, name, code)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    token = github_credential()
    user = api("/user", token)
    if user["login"].lower() != OWNER.lower():
        raise ValueError("GitHub credential belongs to a different account.")
    try:
        repo = api(f"/repos/{OWNER}/{REPO}", token)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        repo = api("/user/repos", token, "POST", {"name": REPO, "private": False, "description": "WaferAI Android 晶圆缺陷识别、良率分析与 AI 助手", "auto_init": False})
    if repo["private"] or repo["owner"]["login"].lower() != OWNER.lower():
        raise ValueError("Repository visibility or owner differs from the approved publication.")
    # Never overwrite an existing tag/release with a different build.
    tag = "v" + name
    try:
        api(f"/repos/{OWNER}/{REPO}/releases/tags/{tag}", token)
        raise ValueError("This version already has a release. Increment versionName and versionCode first.")
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    if command(["git", "diff", "--cached", "--name-only"], cwd=checkout, capture=True).strip():
        command(["git", "-c", f"user.name={OWNER}", "-c", f"user.email={user['id']}+{OWNER}@users.noreply.github.com", "commit", "--quiet", "-m", "Release " + tag], cwd=checkout)
    commit = command(["git", "rev-parse", "HEAD"], cwd=checkout, capture=True).strip()
    # Normal Git credential handling; no credential is ever placed in a URL.
    command(["git", "push", "origin", "main"], cwd=checkout)
    notes_path = args.notes or source / "docs/releases" / (name + ".md")
    notes = notes_path.read_text(encoding="utf-8") if notes_path.exists() else "WaferAI " + name
    notes += f"\n\n<!-- waferai-version-code:{code} -->\n<!-- waferai-apk-sha256:{digest} -->\n\nSHA-256: `{digest}`\n"
    release = api(f"/repos/{OWNER}/{REPO}/releases", token, "POST", {"tag_name": tag, "target_commitish": commit, "name": "WaferAI " + name, "body": notes, "draft": True, "prerelease": False})
    upload = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote("WaferAI-" + name + ".apk")
    asset = api(upload, token, "POST", raw=apk.read_bytes())
    if asset["size"] != apk.stat().st_size:
        raise ValueError("Incomplete APK upload; release remains a draft.")
    published = api(f"/repos/{OWNER}/{REPO}/releases/{release['id']}", token, "PATCH", {"draft": False, "make_latest": "true"})
    print(json.dumps({"repository": repo["html_url"], "release": published["html_url"], "apk": asset["browser_download_url"], "sha256": digest}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"GitHub request failed ({error.code}); publication stopped.", file=sys.stderr)
        sys.exit(1)
