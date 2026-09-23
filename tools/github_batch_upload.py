#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
import hashlib
import re
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

API_VERSION = "2022-11-28"
DEFAULT_TIMEOUT_SECONDS = 45 * 60
POLL_SECONDS = 8
REQUIRED_WORKFLOWS = ("android-ci.yml", "simulator-e2e.yml")


class GitHubError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None):
        super().__init__(message)
        self.status = status


def api_request(
    method: str,
    path: str,
    *,
    token: str,
    api_url: str,
    body: dict[str, Any] | None = None,
    expected: tuple[int, ...] = (200, 201, 204),
) -> Any:
    url = api_url.rstrip("/") + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": API_VERSION,
            "User-Agent": "gym-buddy-python-publisher",
            **({"Content-Type": "application/json"} if data is not None else {}),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            if response.status not in expected:
                raise GitHubError(f"{method} {path}: HTTP {response.status}")
            if not raw:
                return None
            return json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        raise GitHubError(
            f"{method} {path}: HTTP {exc.code}: {payload[:2000]}", status=exc.code
        ) from exc


def repo_path(repo: str, suffix: str) -> str:
    return f"/repos/{repo}/{suffix.lstrip('/')}"


def q(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def validate_workflows(value: Any) -> list[str]:
    # Both independent gates are mandatory; [] must never become vacuous success.
    if (not isinstance(value, list) or len(value) != len(REQUIRED_WORKFLOWS)
            or any(not isinstance(item, str) for item in value)
            or set(value) != set(REQUIRED_WORKFLOWS)):
        raise ValueError("dispatch_workflows must contain android-ci.yml and simulator-e2e.yml exactly once")
    return list(REQUIRED_WORKFLOWS)


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be an object")
    required = {"base", "expected_base_sha", "branch", "commit_message", "files"}
    missing = required - set(manifest)
    if missing:
        raise ValueError(f"manifest missing fields: {sorted(missing)}")
    if manifest["base"] != "main":
        raise ValueError("publisher currently allows base=main only")
    for field in ("expected_base_sha", "expected_head_sha"):
        if field in manifest and (not isinstance(manifest[field], str)
                or not re.fullmatch(r"[0-9a-f]{40}", manifest[field])):
            raise ValueError(f"{field} must be a full commit SHA")
    branch = manifest["branch"]
    if (not isinstance(branch, str) or not re.fullmatch(r"integration/[A-Za-z0-9][A-Za-z0-9_./-]*", branch)
            or ".." in branch or any(not part or part.startswith(".")
            or part.endswith((".", ".lock")) for part in branch.split("/"))):
        raise ValueError("unsafe target branch; use integration/<slice-name>")
    if not isinstance(manifest["commit_message"], str) or not manifest["commit_message"].strip():
        raise ValueError("commit_message must be non-empty text")
    manifest["dispatch_workflows"] = validate_workflows(
        manifest.get("dispatch_workflows", list(REQUIRED_WORKFLOWS)))
    if not isinstance(manifest.get("auto_merge", False), bool):
        raise ValueError("auto_merge must be a boolean")
    for field in ("pr_title", "pr_body"):
        if field in manifest and (not isinstance(manifest[field], str) or not manifest[field].strip()):
            raise ValueError(f"{field} must be non-empty text")
    files = manifest["files"]
    if not isinstance(files, list) or not 1 <= len(files) <= 256:
        raise ValueError("manifest files must contain 1..256 entries")
    seen: set[str] = set()
    total_bytes = 0
    for item in files:
        if not isinstance(item, dict):
            raise ValueError("file entries must be objects")
        path_value = item.get("path")
        if (not isinstance(path_value, str) or not path_value
                or any(part in ("", ".", "..", ".git") for part in path_value.split("/"))
                or "\\" in path_value or any(ord(c) < 32 for c in path_value)):
            raise ValueError(f"unsafe repository path: {path_value!r}")
        if path_value in seen:
            raise ValueError(f"duplicate repository path: {path_value}")
        seen.add(path_value)
        if item.get("mode", "100644") not in ("100644", "100755"):
            raise ValueError(f"{path_value}: only regular text files are supported")
        if "content_utf8" in item:
            if "content_base64" in item or not isinstance(item["content_utf8"], str):
                raise ValueError(f"{path_value}: provide exactly one content encoding")
            item["content_base64"] = base64.b64encode(item.pop("content_utf8").encode("utf-8")).decode("ascii")
        encoded = item.get("content_base64")
        if not isinstance(encoded, str):
            raise ValueError(f"{path_value}: content_base64 text is required")
        decoded = base64.b64decode(encoded, validate=True)
        decoded.decode("utf-8")
        total_bytes += len(decoded)
        if total_bytes > 2 * 1024 * 1024:
            raise ValueError("publisher input exceeds 2 MiB")
        if "sha256" in item and item["sha256"] != hashlib.sha256(decoded).hexdigest():
            raise ValueError(f"{path_value}: SHA-256 mismatch")
    return manifest


def read_head(branch: str, *, repo: str, token: str, api_url: str) -> str | None:
    try:
        ref = api_request("GET", repo_path(repo, f"git/ref/heads/{q(branch)}"),
                          token=token, api_url=api_url)
        return ref["object"]["sha"]
    except GitHubError as exc:
        if exc.status == 404:
            return None
        raise


def require_head(branch: str, expected: str | None, **auth: Any) -> None:
    actual = read_head(branch, **auth)
    if actual != expected:
        raise GitHubError(f"branch moved: {branch}; expected {expected}, found {actual}; refusing stale upload")


def create_target_commit(
    manifest: dict[str, Any], *, repo: str, token: str, api_url: str,
) -> str:
    auth = dict(repo=repo, token=token, api_url=api_url)
    base, branch = manifest["base"], manifest["branch"]
    expected_base = manifest["expected_base_sha"]
    expected_head = manifest.get("expected_head_sha")
    # Check all overwrite preconditions before even creating blobs.
    require_head(base, expected_base, **auth)
    require_head(branch, expected_head, **auth)
    parent_sha = expected_head or expected_base
    if expected_head:
        comparison = api_request("GET", repo_path(repo, f"compare/{expected_base}...{expected_head}"),
                                 token=token, api_url=api_url)
        if comparison.get("status") not in ("ahead", "identical"):
            raise GitHubError("target branch does not contain the pinned base; rebase and revalidate first")
    parent = api_request("GET", repo_path(repo, f"git/commits/{parent_sha}"),
                         token=token, api_url=api_url)
    tree_entries: list[dict[str, str]] = []
    for item in manifest["files"]:
        decoded = base64.b64decode(item["content_base64"], validate=True)
        blob = api_request("POST", repo_path(repo, "git/blobs"), token=token, api_url=api_url,
                           body={"content": decoded.decode("utf-8"), "encoding": "utf-8"}, expected=(201,))
        expected_blob = hashlib.sha1(f"blob {len(decoded)}\0".encode() + decoded).hexdigest()
        if blob.get("sha") != expected_blob:
            raise GitHubError(f"uploaded blob differs from local bytes: {item['path']}")
        tree_entries.append({"path": item["path"], "mode": item.get("mode", "100644"),
                             "type": "blob", "sha": blob["sha"]})
    tree = api_request("POST", repo_path(repo, "git/trees"), token=token, api_url=api_url,
                      body={"base_tree": parent["tree"]["sha"], "tree": tree_entries}, expected=(201,))
    commit = api_request("POST", repo_path(repo, "git/commits"), token=token, api_url=api_url,
                        body={"message": manifest["commit_message"], "tree": tree["sha"],
                              "parents": [parent_sha]}, expected=(201,))
    commit_sha = commit["sha"]
    require_head(base, expected_base, **auth)
    require_head(branch, expected_head, **auth)
    if expected_head is None:
        api_request("POST", repo_path(repo, "git/refs"), token=token, api_url=api_url,
                    body={"ref": f"refs/heads/{branch}", "sha": commit_sha}, expected=(201,))
    else:
        # A concurrent writer after the read is still protected by non-fast-forward rejection.
        api_request("PATCH", repo_path(repo, f"git/refs/heads/{q(branch)}"),
                    token=token, api_url=api_url, body={"sha": commit_sha, "force": False})
    require_head(branch, commit_sha, **auth)
    print(f"PYTHON_UPLOAD_COMMIT={commit_sha}")
    print(f"PYTHON_UPLOAD_BRANCH={branch}")
    return commit_sha


def dispatch_workflow(
    workflow: str,
    branch: str,
    *,
    repo: str,
    token: str,
    api_url: str,
) -> None:
    api_request(
        "POST",
        repo_path(repo, f"actions/workflows/{q(workflow)}/dispatches"),
        token=token,
        api_url=api_url,
        body={"ref": branch},
        expected=(204,),
    )
    print(f"DISPATCHED={workflow}")


def workflow_runs(workflow: str, branch: str, **auth: Any) -> list[dict[str, Any]]:
    query = urllib.parse.urlencode(
        {"branch": branch, "event": "workflow_dispatch", "per_page": 100})
    result = api_request("GET", repo_path(auth["repo"], f"actions/workflows/{q(workflow)}/runs?{query}"),
                         token=auth["token"], api_url=auth["api_url"])
    return result.get("workflow_runs", [])


def find_workflow_run(
    workflow: str, branch: str, head_sha: str, *,
    repo: str, token: str, api_url: str, after_run_id: int = 0,
) -> dict[str, Any] | None:
    matches = [run for run in workflow_runs(workflow, branch, repo=repo, token=token, api_url=api_url)
               if run.get("head_sha") == head_sha and run.get("head_branch") == branch
               and run.get("event") == "workflow_dispatch" and run.get("id", 0) > after_run_id]
    return max(matches, key=lambda run: run["id"], default=None)


def all_jobs(run_id: int, *, repo: str, token: str, api_url: str) -> list[dict[str, Any]]:
    jobs: list[dict[str, Any]] = []
    page = 1
    while True:
        result = api_request("GET", repo_path(repo, f"actions/runs/{run_id}/jobs?per_page=100&page={page}"),
                             token=token, api_url=api_url)
        batch = result.get("jobs", [])
        jobs.extend(batch)
        if len(batch) < 100:
            return jobs
        page += 1


# A successful workflow is insufficient when a blocking test step was skipped.
REQUIRED_STEPS = {
    "android-ci.yml": ("Python publisher regression tests", "JVM and Room regression tests",
                       "Assemble debug and release APKs", "Verify release has no INTERNET permission"),
    "simulator-e2e.yml": ("Harness-derived temporal contract gate", "Harness-derived camera/tracking contract gate",
                          "Harness-derived primary-subject-lock contract gate", "Harness-derived form/cue contract gate",
                          "Production movement vertical-slice gate", "Run emulator E2E",
                          "Verify posted MediaPipe and production results"),
}


def verify_gate_steps(workflow: str, run: dict[str, Any], **auth: Any) -> None:
    if (run.get("status") != "completed" or run.get("conclusion") != "success"
            or run.get("path", "").split("@")[0] != f".github/workflows/{workflow}"):
        raise GitHubError(f"{workflow}: not a successful run of the required workflow")
    jobs = all_jobs(int(run["id"]), **auth)
    steps = [step for job in jobs if job.get("conclusion") == "success" for step in job.get("steps", [])]
    for name in REQUIRED_STEPS[workflow]:
        matches = [step for step in steps if step.get("name") == name]
        if not matches or any(step.get("conclusion") != "success" for step in matches):
            raise GitHubError(f"{workflow}: required step absent or not successful: {name}; {run.get('html_url')}")


def first_failed_step(
    run_id: int,
    *,
    repo: str,
    token: str,
    api_url: str,
) -> str | None:
    jobs = all_jobs(run_id, repo=repo, token=token, api_url=api_url)
    for job in jobs:
        if job.get("conclusion") in ("failure", "cancelled", "timed_out", "action_required"):
            for step in job.get("steps", []):
                if step.get("conclusion") in ("failure", "cancelled", "timed_out"):
                    return f"{job.get('name')}: {step.get('name')}"
            return str(job.get("name"))
    return None


def wait_for_workflows(
    workflows: list[str],
    branch: str,
    head_sha: str,
    *,
    repo: str,
    token: str,
    api_url: str,
    timeout_seconds: int,
    after_run_ids: dict[str, int] | None = None,
) -> list[dict[str, Any]]:
    workflows = validate_workflows(workflows)
    if timeout_seconds <= 0:
        raise ValueError("timeout_seconds must be positive")
    deadline = time.monotonic() + timeout_seconds
    runs: dict[str, dict[str, Any]] = {}

    while time.monotonic() < deadline:
        require_head(branch, head_sha, repo=repo, token=token, api_url=api_url)
        runs = {}  # Never retain an earlier PASS if the current query loses that run.
        for workflow in workflows:
            run = find_workflow_run(
                workflow,
                branch,
                head_sha,
                repo=repo,
                token=token,
                api_url=api_url,
                after_run_id=(after_run_ids or {}).get(workflow, 0),
            )
            if run is not None:
                runs[workflow] = run

        if len(runs) == len(workflows) and all(
            run.get("status") == "completed" for run in runs.values()
        ):
            break
        time.sleep(POLL_SECONDS)
    else:
        raise TimeoutError(
            f"timed out waiting for workflows: {', '.join(workflows)}"
        )

    ordered = [runs[name] for name in workflows]
    failed = [run for run in ordered if run.get("conclusion") != "success"]
    if failed:
        details = []
        for run in failed:
            step = first_failed_step(
                int(run["id"]),
                repo=repo,
                token=token,
                api_url=api_url,
            )
            details.append(
                f"{run.get('name')}={run.get('conclusion')}"
                + (f" ({step})" if step else "")
                + f" {run.get('html_url')}"
            )
        raise GitHubError("workflow failure: " + "; ".join(details))
    for workflow, run in zip(workflows, ordered):
        verify_gate_steps(workflow, run, repo=repo, token=token, api_url=api_url)
    require_head(branch, head_sha, repo=repo, token=token, api_url=api_url)
    return ordered


def ensure_pull_request(manifest: dict[str, Any], commit_sha: str, **auth: Any) -> dict[str, Any]:
    repo, token, api_url = auth["repo"], auth["token"], auth["api_url"]
    query = urllib.parse.urlencode({"state": "open", "base": "main", "head": f"{repo.split('/')[0]}:{manifest['branch']}"})
    existing = api_request("GET", repo_path(repo, f"pulls?{query}"), token=token, api_url=api_url)
    if existing:
        if (len(existing) != 1 or existing[0]["head"]["sha"] != commit_sha
                or existing[0]["head"]["ref"] != manifest["branch"]
                or existing[0]["head"]["repo"]["full_name"] != repo
                or existing[0]["base"]["ref"] != "main"):
            raise GitHubError("pull request head does not match the uploaded commit")
        return existing[0]
    return api_request("POST", repo_path(repo, "pulls"), token=token, api_url=api_url,
                       body={"title": manifest.get("pr_title", manifest["commit_message"].splitlines()[0]),
                             "body": manifest.get("pr_body", "Published by Python. Required CI is pending.")
                                     + f"\n\nPublisher commit: `{commit_sha}`; base: `{manifest['expected_base_sha']}`.",
                             "head": manifest["branch"], "base": "main"}, expected=(201,))


def post_status(pr_number: int, text: str, **auth: Any) -> None:
    api_request("POST", repo_path(auth["repo"], f"issues/{pr_number}/comments"),
                token=auth["token"], api_url=auth["api_url"], body={"body": text}, expected=(201,))


def merge_verified(manifest: dict[str, Any], pr_number: int, commit_sha: str,
                   runs: list[dict[str, Any]], **auth: Any) -> None:
    # Re-check current CI attempts, base and head immediately before the SHA-guarded merge.
    if len(runs) != len(REQUIRED_WORKFLOWS) or len({run["id"] for run in runs}) != len(runs):
        raise GitHubError("both distinct workflow results are required for merge")
    for workflow, known in zip(REQUIRED_WORKFLOWS, runs):
        current = api_request("GET", repo_path(auth["repo"], f"actions/runs/{known['id']}"),
                              token=auth["token"], api_url=auth["api_url"])
        if (current.get("head_sha") != commit_sha or current.get("status") != "completed"
                or current.get("conclusion") != "success" or current.get("head_branch") != manifest["branch"]
                or current.get("event") != "workflow_dispatch"
                or current.get("run_attempt") != known.get("run_attempt")):
            raise GitHubError("CI changed after verification; refusing merge")
        verify_gate_steps(workflow, current, **auth)
    require_head("main", manifest["expected_base_sha"], **auth)
    require_head(manifest["branch"], commit_sha, **auth)
    pr = api_request("GET", repo_path(auth["repo"], f"pulls/{pr_number}"),
                     token=auth["token"], api_url=auth["api_url"])
    if (pr.get("state") != "open" or pr.get("draft") or pr["base"]["ref"] != "main"
            or pr["head"]["ref"] != manifest["branch"] or pr["head"]["sha"] != commit_sha
            or pr["head"]["repo"]["full_name"] != auth["repo"]):
        raise GitHubError("pull request changed; refusing merge")
    result = api_request("PUT", repo_path(auth["repo"], f"pulls/{pr_number}/merge"),
                         token=auth["token"], api_url=auth["api_url"],
                         body={"sha": commit_sha, "merge_method": "squash"}, expected=(200,))
    if result.get("merged") is not True:
        raise GitHubError("GitHub did not confirm a successful merge")


def write_summary(manifest: dict[str, Any], commit_sha: str, runs: list[dict[str, Any]],
                  *, status: str = "CI_PASS", error: str | None = None, pr_url: str | None = None) -> str:
    lines = ["## Python publisher", "", f"Status: **{status}**",
             f"Branch: `{manifest.get('branch', 'not validated')}`", f"Commit: `{commit_sha or 'not uploaded'}`"]
    if pr_url:
        lines.append(f"Pull request: {pr_url}")
    for run in runs:
        lines.append(f"{run.get('name')}: **{run.get('conclusion')}** — {run.get('html_url')}")
    if error:
        lines.append(f"Failure: {error}")
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    repo, run_id = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_RUN_ID")
    if repo and run_id:
        lines.append(f"Publisher run: {server}/{repo}/actions/runs/{run_id}")
    text = "\n\n".join(lines) + "\n"
    if summary_path := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary_path).open("a", encoding="utf-8") as stream:
            stream.write(text)
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args()
    manifest: dict[str, Any] = {}
    commit_sha = ""
    runs: list[dict[str, Any]] = []
    pr: dict[str, Any] | None = None
    merged = False
    auth = {"repo": os.environ.get("GITHUB_REPOSITORY", ""),
            "token": os.environ.get("GITHUB_TOKEN", ""),
            "api_url": os.environ.get("GITHUB_API_URL", "https://api.github.com")}
    try:
        if not auth["token"] or not auth["repo"]:
            raise RuntimeError("GITHUB_TOKEN and GITHUB_REPOSITORY are required")
        if args.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        manifest = load_manifest(args.manifest)
        commit_sha = create_target_commit(manifest, **auth)
        pr = ensure_pull_request(manifest, commit_sha, **auth)
        floors = {}
        for workflow in REQUIRED_WORKFLOWS:
            floors[workflow] = max((run["id"] for run in workflow_runs(workflow, manifest["branch"], **auth)), default=0)
            require_head(manifest["branch"], commit_sha, **auth)
            dispatch_workflow(workflow, manifest["branch"], **auth)
        runs = wait_for_workflows(list(REQUIRED_WORKFLOWS), manifest["branch"], commit_sha,
                                 **auth, timeout_seconds=args.timeout_seconds, after_run_ids=floors)
        # Notification failure prevents automatic merging rather than claiming full success.
        text = write_summary(manifest, commit_sha, runs, status="CI_PASS_AWAITING_REVIEW", pr_url=pr["html_url"])
        post_status(pr["number"], text, **auth)
        if manifest.get("auto_merge", False):
            merge_verified(manifest, pr["number"], commit_sha, runs, **auth)
            merged = True
            text = write_summary(manifest, commit_sha, runs, status="MERGED", pr_url=pr["html_url"])
            post_status(pr["number"], text, **auth)
        print("PYTHON_UPLOAD_AND_CI_PASS")
        print(f"PULL_REQUEST={pr['html_url']}")
        return 0
    except (RuntimeError, ValueError, KeyError, TypeError, OSError) as exc:
        error = str(exc).replace(auth["token"], "[REDACTED]") if auth["token"] else str(exc)
        status = "MERGED_NOTIFICATION_FAILED" if merged else "FAILED"
        try:
            text = write_summary(manifest, commit_sha, runs, status=status, error=error,
                                 pr_url=pr.get("html_url") if pr else None)
        except OSError:
            text = f"Publisher {status}; commit {commit_sha or 'not uploaded'}; {error}"
            print(text, file=sys.stderr)
        if pr:
            try:
                post_status(pr["number"], text, **auth)
            except (RuntimeError, OSError) as notification_error:
                print(f"Failure notification also failed: {type(notification_error).__name__}", file=sys.stderr)
        escaped = error.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error::{escaped}", file=sys.stderr)
        print("PYTHON_UPLOAD_OR_CI_FAILED", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
