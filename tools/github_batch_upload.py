#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import json
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


class GitHubError(RuntimeError):
    pass


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
            f"{method} {path}: HTTP {exc.code}: {payload[:2000]}"
        ) from exc


def repo_path(repo: str, suffix: str) -> str:
    return f"/repos/{repo}/{suffix.lstrip('/')}"


def q(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    required = {"base", "branch", "commit_message", "files"}
    missing = required - set(manifest)
    if missing:
        raise ValueError(f"manifest missing fields: {sorted(missing)}")
    if manifest["base"] != "main":
        raise ValueError("publisher currently allows base=main only")
    branch = str(manifest["branch"])
    if not branch.startswith("integration/"):
        raise ValueError("target branch must start with integration/")
    files = manifest["files"]
    if not isinstance(files, list) or not files:
        raise ValueError("manifest files must be a non-empty list")
    seen: set[str] = set()
    for item in files:
        path_value = str(item.get("path", ""))
        if not path_value or path_value.startswith("/") or ".." in Path(path_value).parts:
            raise ValueError(f"unsafe repository path: {path_value!r}")
        if path_value in seen:
            raise ValueError(f"duplicate repository path: {path_value}")
        seen.add(path_value)
        if "content_base64" not in item:
            raise ValueError(f"{path_value}: content_base64 is required")
        base64.b64decode(item["content_base64"], validate=True)
    return manifest


def create_target_commit(
    manifest: dict[str, Any],
    *,
    repo: str,
    token: str,
    api_url: str,
) -> str:
    base = manifest["base"]
    branch = manifest["branch"]
    base_ref = api_request(
        "GET",
        repo_path(repo, f"git/ref/heads/{q(base)}"),
        token=token,
        api_url=api_url,
    )
    parent_sha = base_ref["object"]["sha"]
    base_commit = api_request(
        "GET",
        repo_path(repo, f"git/commits/{parent_sha}"),
        token=token,
        api_url=api_url,
    )
    base_tree_sha = base_commit["tree"]["sha"]

    tree_entries: list[dict[str, str]] = []
    for item in manifest["files"]:
        decoded = base64.b64decode(item["content_base64"])
        text = decoded.decode("utf-8")
        blob = api_request(
            "POST",
            repo_path(repo, "git/blobs"),
            token=token,
            api_url=api_url,
            body={"content": text, "encoding": "utf-8"},
            expected=(201,),
        )
        tree_entries.append(
            {
                "path": item["path"],
                "mode": item.get("mode", "100644"),
                "type": "blob",
                "sha": blob["sha"],
            }
        )

    tree = api_request(
        "POST",
        repo_path(repo, "git/trees"),
        token=token,
        api_url=api_url,
        body={"base_tree": base_tree_sha, "tree": tree_entries},
        expected=(201,),
    )
    commit = api_request(
        "POST",
        repo_path(repo, "git/commits"),
        token=token,
        api_url=api_url,
        body={
            "message": manifest["commit_message"],
            "tree": tree["sha"],
            "parents": [parent_sha],
        },
        expected=(201,),
    )
    commit_sha = commit["sha"]

    try:
        api_request(
            "GET",
            repo_path(repo, f"git/ref/heads/{q(branch)}"),
            token=token,
            api_url=api_url,
        )
    except GitHubError as exc:
        if "HTTP 404" not in str(exc):
            raise
        api_request(
            "POST",
            repo_path(repo, "git/refs"),
            token=token,
            api_url=api_url,
            body={"ref": f"refs/heads/{branch}", "sha": commit_sha},
            expected=(201,),
        )
    else:
        api_request(
            "PATCH",
            repo_path(repo, f"git/refs/heads/{q(branch)}"),
            token=token,
            api_url=api_url,
            body={"sha": commit_sha, "force": True},
        )

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


def find_workflow_run(
    workflow: str,
    branch: str,
    head_sha: str,
    *,
    repo: str,
    token: str,
    api_url: str,
) -> dict[str, Any] | None:
    query = urllib.parse.urlencode(
        {"branch": branch, "event": "workflow_dispatch", "per_page": 30}
    )
    result = api_request(
        "GET",
        repo_path(repo, f"actions/workflows/{q(workflow)}/runs?{query}"),
        token=token,
        api_url=api_url,
    )
    for run in result.get("workflow_runs", []):
        if run.get("head_sha") == head_sha:
            return run
    return None


def first_failed_step(
    run_id: int,
    *,
    repo: str,
    token: str,
    api_url: str,
) -> str | None:
    jobs = api_request(
        "GET",
        repo_path(repo, f"actions/runs/{run_id}/jobs?per_page=100"),
        token=token,
        api_url=api_url,
    )
    for job in jobs.get("jobs", []):
        if job.get("conclusion") == "failure":
            for step in job.get("steps", []):
                if step.get("conclusion") == "failure":
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
) -> list[dict[str, Any]]:
    deadline = time.monotonic() + timeout_seconds
    runs: dict[str, dict[str, Any]] = {}

    while time.monotonic() < deadline:
        for workflow in workflows:
            run = find_workflow_run(
                workflow,
                branch,
                head_sha,
                repo=repo,
                token=token,
                api_url=api_url,
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
    return ordered


def write_summary(
    manifest: dict[str, Any],
    commit_sha: str,
    runs: list[dict[str, Any]],
) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    lines = [
        "## Python publisher",
        "",
        f"- Target branch: `{manifest['branch']}`",
        f"- Commit: `{commit_sha}`",
    ]
    for run in runs:
        lines.append(
            f"- {run.get('name')}: **{run.get('conclusion')}** — {run.get('html_url')}"
        )
    Path(summary_path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument(
        "--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS
    )
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
    if not token or not repo:
        raise RuntimeError("GITHUB_TOKEN and GITHUB_REPOSITORY are required")

    manifest = load_manifest(args.manifest)
    commit_sha = create_target_commit(
        manifest,
        repo=repo,
        token=token,
        api_url=api_url,
    )
    workflows = list(
        manifest.get(
            "dispatch_workflows",
            ["android-ci.yml", "simulator-e2e.yml"],
        )
    )
    for workflow in workflows:
        dispatch_workflow(
            workflow,
            manifest["branch"],
            repo=repo,
            token=token,
            api_url=api_url,
        )
    runs = wait_for_workflows(
        workflows,
        manifest["branch"],
        commit_sha,
        repo=repo,
        token=token,
        api_url=api_url,
        timeout_seconds=args.timeout_seconds,
    )
    write_summary(manifest, commit_sha, runs)
    print("PYTHON_UPLOAD_AND_CI_PASS")
    for run in runs:
        print(
            f"WORKFLOW={run.get('name')} CONCLUSION={run.get('conclusion')} URL={run.get('html_url')}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
