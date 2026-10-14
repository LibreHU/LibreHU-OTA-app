#!/usr/bin/env python3
"""Checks that LibreHU Apps can see every update.

For each app of catalog.json and each branch it publishes: the rolling release `latest-<branch>` has a readable
release.txt with a versionCode, and the commit it was built from is the branch's last one (else the CI failed or
is still running: LibreHU Apps shows the older build). For the apps with edition branches (librehu-service, ivi,
main), also reports an edition branch that misses commits of main (a fix pushed on main only does not reach that
edition until main is merged into it).

Usage: python3 tools/check_releases.py   (needs git and network access to github.com)
"""
import json
import os
import subprocess
import tempfile
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def release_txt(repo, branch):
    url = f"https://github.com/{repo}/releases/download/latest-{branch}/release.txt"
    try:
        with urllib.request.urlopen(url, timeout=20) as r:
            text = r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, str(e)
    kv = {}
    for line in text.splitlines():
        if "=" in line and line.split("=", 1)[0].isalnum():
            k, v = line.split("=", 1)
            kv[k] = v.strip()
    return kv, None


def heads(repo):
    out = subprocess.run(["git", "ls-remote", f"https://github.com/{repo}"], capture_output=True, text=True).stdout
    return {ref[11:]: sha for sha, ref in (l.split("\t") for l in out.splitlines()) if ref.startswith("refs/heads/")}


def behind_main(repo, branch):
    """Number of commits of main missing from branch (shallow-free, through a bare clone)."""
    with tempfile.TemporaryDirectory() as d:
        subprocess.run(["git", "clone", "-q", "--bare", "--filter=blob:none", f"https://github.com/{repo}", d], check=True)
        out = subprocess.run(["git", "-C", d, "rev-list", "--count", f"{branch}..main"], capture_output=True, text=True)
        return int(out.stdout.strip() or 0)


def main():
    apps = json.load(open(os.path.join(HERE, "..", "catalog.json")))["apps"]
    problems = 0
    for a in apps:
        repo, branches = a["repo"], a.get("branches") or ["main"]
        h = heads(repo)
        for b in branches:
            kv, err = release_txt(repo, b)
            if kv is None or "versionCode" not in kv:
                print(f"KO  {a['id']:10} {b:16} no release.txt / versionCode ({err or 'missing field'})")
                problems += 1
                continue
            commit, head = kv.get("commit", ""), h.get(b, "")
            fresh = bool(commit) and head.startswith(commit[:7])
            print(f"{'OK ' if fresh else 'OLD'} {a['id']:10} {b:16} versionCode={kv['versionCode']:6} release={commit[:7]} head={head[:7]}")
            problems += 0 if fresh else 1
        if len(branches) > 1 and "main" in h:
            for b in branches:
                if b == "main" or b not in h:
                    continue
                n = behind_main(repo, b)
                if n:
                    print(f"    {a['id']:10} {b:16} misses {n} commit(s) of main: merge main into it")
                    problems += 1
    print("\nAll good." if problems == 0 else f"\n{problems} problem(s).")


if __name__ == "__main__":
    main()
