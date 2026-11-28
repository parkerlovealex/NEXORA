"""Temporary verification branch only. Preserve results from every gate, including known failures."""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

temp = Path(os.environ["RUNNER_TEMP"])
repo = temp / "nexora-committed-tree"
subprocess.run(["git", "worktree", "add", "--detach", str(repo), os.environ["VERIFY_COMMIT"]], check=True)
logs = temp / "nexora-alpha30-logs"
logs.mkdir()
manifest = {"finding": os.environ["VERIFY_FINDING"]}
gradle = ["gradle", "--no-daemon", "--console=plain", "--max-workers=2",
          "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -XX:ActiveProcessorCount=4",
          "-Pkotlin.compiler.execution.strategy=in-process"]
pytest = [sys.executable, "-m", "pytest", "-q", "--tb=short", "-p", "no:cacheprovider"]
checks = {}

def git(*args, cwd=repo):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()

def environment(root, suffix):
    return dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONPATH=str(root),
                DATABASE_URL="sqlite:///" + str(temp / (suffix + ".sqlite")),
                NEXORA_ADMIN_PASSWORD=secrets.token_urlsafe(24), NEXORA_SECRET_KEY=secrets.token_urlsafe(48))

def run(name, args, cwd, env):
    proc = subprocess.run(args, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = re.sub(r"Bearer [a-zA-Z0-9._=-]+", "Bearer [test session redacted]", proc.stdout)
    for key in ("NEXORA_ADMIN_PASSWORD", "NEXORA_SECRET_KEY"):
        output = output.replace(env[key], "[test fixture redacted]")
    (logs / (name + ".txt")).write_text(output)
    summary = next((s for s in reversed(output.splitlines()) if s.strip()), "")
    checks[name] = {"exit": proc.returncode, "summary": summary}
    return proc.returncode

def junit(root, label):
    suites = []
    dst = logs / label
    dst.mkdir()
    for path in sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
        shutil.copyfile(path, dst / path.name)
        suites.append(ET.parse(path).getroot())
    failing = [case.get("classname") + "." + case.get("name") for suite in suites for case in suite.findall("testcase")
               if case.find("failure") is not None or case.find("error") is not None]
    count = sum(int(s.get("tests")) for s in suites)
    skipped = sum(int(s.get("skipped", "0")) for s in suites)
    return {"tests": count, "failures": failing, "skipped": skipped}

actual = git("rev-parse", "HEAD")
assert actual == os.environ["VERIFY_COMMIT"], actual
result = {"finding": manifest["finding"], "head": actual, "tree": git("rev-parse", "HEAD^{tree}")}
focus = None

env = environment(repo, "candidate")
jobs = {"python": pytest, "desktop": ["node", "tests/desktop-ua-smoke.js"],
        "android": gradle + [":app:assembleDebug", ":app:testDebugUnitTest", "--rerun", "--continue"]}
for side, data in (("web", "web/devices.json"), ("app", "app/src/main/assets/devices.json")):
    for mode, flags in (("ordinary", []), ("strict", ["--strict"])):
        jobs[f"devices-{side}-{mode}"] = [sys.executable, "tools/validate_devices.py", data, *flags]
        jobs[f"specs-{side}-{mode}"] = [sys.executable, "tools/validate_specs.py", data, "--evidence", "data/evidence/specs/latest.json", *flags]
    for kind, source in (("published", "web/ua_templates.json"), ("proposed", "data/ua_templates.proposed.json")):
        jobs[f"ua-{side}-{kind}"] = [sys.executable, "tools/validate_ua_templates.py", source, "--devices", data]
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    futures = [pool.submit(run, name, args, repo, env) for name, args in jobs.items()]
    for future in futures:
        future.result()
result["junit"] = junit(repo, "candidate-junit")
if focus and focus["kind"] == "jvm":
    result["candidateFocus"] = {}
    for xml in (logs / "candidate-junit").glob("*.xml"):
        for case in ET.parse(xml).getroot().findall("testcase"):
            name = case.get("classname") + "." + case.get("name")
            if name == focus["selector"]:
                result["candidateFocus"][name] = next((state for state in ("failure", "error", "skipped") if case.find(state) is not None), "passed")
if result["junit"]["tests"] == 0:
    print("Gradle diagnostics:")
    print("\n".join((logs / "android.txt").read_text().splitlines()[-60:]))
apk = repo / "app/build/outputs/apk/debug/app-debug.apk"
result["apkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest() if apk.is_file() else None
for name, check in checks.items():
    data = (logs / (name + ".txt")).read_text()
    pair = (re.search(r"Checked .*?, (\d+) errors?, (\d+) warnings?", data) or
            re.search(r"Errors: (\d+).*?Warnings: (\d+)", data, re.S))
    if pair:
        check["counts"] = list(map(int, pair.groups()))
    if name == "python":
        check["failures"] = [s.split(" - ")[0].removeprefix("FAILED ") for s in data.splitlines() if s.startswith("FAILED ")]
result["checks"] = checks
(logs / "summary.json").write_text(json.dumps(result, indent=2))
print("NEXORA_GATE " + json.dumps(result), flush=True)
with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
    summary.write("## " + manifest["finding"] + " verification\n\n")
    summary.write("| Gate | Exit | Result |\n| --- | ---: | --- |\n")
    for name in sorted(checks):
        summary.write(f"| {name} | {checks[name]['exit']} | {checks[name]['summary'].replace('|', '/')} |\n")
if any(checks[name]["exit"] for name in jobs):
    sys.exit(1)
