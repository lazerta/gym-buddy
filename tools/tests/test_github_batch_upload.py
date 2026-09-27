"""Publisher contracts: no live network or credentials; requests are independently recorded."""
from __future__ import annotations

import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "github_batch_upload.py"
spec = importlib.util.spec_from_file_location("publisher", MODULE)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
BASE = "a" * 40
HEAD = "b" * 40
NEW = "c" * 40
AUTH = {"repo": "owner/repo", "token": "test-only", "api_url": "https://example.invalid"}
WORKFLOWS = ["android-ci.yml", "simulator-e2e.yml"]


def manifest(**changes):
    value = {"base": "main", "expected_base_sha": BASE, "branch": "integration/slice",
             "commit_message": "Test slice", "files": [{"path": "tools/example.py",
             "content_base64": base64.b64encode(b"print('hello')\n").decode()}]}
    value.update(changes)
    return value


def load(value):
    with tempfile.TemporaryDirectory() as root:
        path = Path(root) / "request.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return p.load_manifest(path)


class FakeGitHub:
    def __init__(self, existing=None, base=BASE):
        self.existing, self.base = existing, base
        self.calls = []
        self.corrupt_blob = False

    def __call__(self, method, path, **kw):
        self.calls.append((method, path, kw.get("body")))
        if path.endswith("git/ref/heads/main"):
            return {"object": {"sha": self.base}}
        if method == "GET" and "/git/ref/heads/" in path:
            if self.existing is None:
                raise p.GitHubError("GET ref: HTTP 404", status=404)
            return {"object": {"sha": self.existing}}
        if method == "GET" and "/git/commits/" in path:
            return {"tree": {"sha": "tree-" + path.rsplit("/", 1)[-1]}}
        if method == "GET" and "/compare/" in path:
            return {"status": "ahead"}
        body = kw.get("body", {})
        if path.endswith("git/blobs"):
            data = (base64.b64decode(body["content"]) if body["encoding"] == "base64"
                    else body["content"].encode("utf-8"))
            digest = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
            return {"sha": "wrong" if self.corrupt_blob else digest}
        if path.endswith("git/trees"):
            return {"sha": "new-tree"}
        if path.endswith("git/commits"):
            return {"sha": NEW}
        if method in ("POST", "PATCH") and "/git/ref" in path:
            self.existing = body["sha"]
            return {"object": {"sha": body["sha"]}}
        raise AssertionError((method, path, kw))

    @property
    def writes(self):
        return [call for call in self.calls if call[0] != "GET"]


class ManifestTests(unittest.TestCase):
    def test_valid_manifest_and_default_gates(self):
        self.assertEqual(load(manifest())["branch"], "integration/slice")

    def test_gates_cannot_be_empty_missing_duplicated_or_unknown(self):
        for gates in ([], ["android-ci.yml"], WORKFLOWS + ["android-ci.yml"],
                      ["not-a-gate.yml"], "android-ci.yml"):
            with self.subTest(gates=gates), self.assertRaises(ValueError):
                load(manifest(dispatch_workflows=gates))

    def test_explicit_two_gates_are_accepted(self):
        self.assertEqual(load(manifest(dispatch_workflows=WORKFLOWS))["dispatch_workflows"], WORKFLOWS)

    def test_base_must_be_pinned(self):
        value = manifest()
        value.pop("expected_base_sha")
        with self.assertRaises(ValueError):
            load(value)

    def test_invalid_base_sha(self):
        for sha in (None, "main", "a" * 39, "g" * 40):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                load(manifest(expected_base_sha=sha))

    def test_unsafe_paths_and_modes(self):
        for path in (".", "a/./b", "a//b", "../x", "/x", ".git/config", "a\\b"):
            value = manifest()
            value["files"][0]["path"] = path
            with self.subTest(path=path), self.assertRaises(ValueError):
                load(value)
        value = manifest()
        value["files"][0]["mode"] = "120000"
        with self.assertRaises(ValueError):
            load(value)

    def test_invalid_branch_and_empty_message(self):
        for branch in ("main", "integration/", "integration/a..b", "integration/a.lock", "integration/a b"):
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                load(manifest(branch=branch))
        with self.assertRaises(ValueError):
            load(manifest(commit_message=""))

    def test_invalid_utf8_and_hash_mismatch_fail_before_upload(self):
        value = manifest()
        value["files"][0]["content_base64"] = base64.b64encode(b"\xff").decode()
        with self.assertRaises(ValueError):
            load(value)
        value = manifest()
        value["files"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            load(value)

    def test_duplicate_paths(self):
        value = manifest()
        value["files"] *= 2
        with self.assertRaises(ValueError):
            load(value)


class PublishingTests(unittest.TestCase):
    def test_stale_base_causes_zero_writes(self):
        api = FakeGitHub(base=HEAD)
        with patch.object(p, "api_request", api), self.assertRaises(p.GitHubError):
            p.create_target_commit(manifest(), **AUTH)
        self.assertEqual(api.writes, [])

    def test_existing_branch_without_expected_head_causes_zero_writes(self):
        api = FakeGitHub(existing=HEAD)
        with patch.object(p, "api_request", api), self.assertRaises(p.GitHubError):
            p.create_target_commit(manifest(), **AUTH)
        self.assertEqual(api.writes, [])

    def test_mismatched_expected_head_causes_zero_writes(self):
        api = FakeGitHub(existing=HEAD)
        with patch.object(p, "api_request", api), self.assertRaises(p.GitHubError):
            p.create_target_commit(manifest(expected_head_sha=BASE), **AUTH)
        self.assertEqual(api.writes, [])

    def test_new_branch_uses_pinned_parent(self):
        api = FakeGitHub()
        with patch.object(p, "api_request", api), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(p.create_target_commit(manifest(), **AUTH), NEW)
        commits = [body for _, path, body in api.writes if path.endswith("git/commits")]
        self.assertEqual(commits[0]["parents"], [BASE])
        self.assertFalse(any(body.get("force") for _, _, body in api.writes))

    def test_expected_head_update_preserves_parent_and_never_forces(self):
        api = FakeGitHub(existing=HEAD)
        with patch.object(p, "api_request", api), contextlib.redirect_stdout(io.StringIO()):
            p.create_target_commit(manifest(expected_head_sha=HEAD), **AUTH)
        commits = [body for _, path, body in api.writes if path.endswith("git/commits")]
        trees = [body for _, path, body in api.writes if path.endswith("git/trees")]
        self.assertEqual(commits[0]["parents"], [HEAD])
        self.assertEqual(trees[0]["base_tree"], "tree-" + HEAD)
        updates = [body for method, _, body in api.writes if method == "PATCH"]
        self.assertEqual(updates, [{"sha": NEW, "force": False}])

    def test_corrupt_upload_never_updates_a_ref(self):
        api = FakeGitHub()
        api.corrupt_blob = True
        with patch.object(p, "api_request", api), self.assertRaises(p.GitHubError):
            p.create_target_commit(manifest(), **AUTH)
        self.assertFalse(any("/git/ref" in path for _, path, _ in api.writes))


class GateTests(unittest.TestCase):
    def test_empty_gates_cannot_produce_pass_even_directly(self):
        with self.assertRaises(ValueError):
            p.wait_for_workflows([], "integration/slice", NEW, **AUTH, timeout_seconds=1)

    def test_wrong_sha_run_is_ignored(self):
        with patch.object(p, "api_request", return_value={"workflow_runs": [{"head_sha": HEAD}]}):
            self.assertIsNone(p.find_workflow_run(WORKFLOWS[0], "integration/slice", NEW, **AUTH))


def run(workflow=WORKFLOWS[0], **changes):
    value = {"id": 101 if workflow == WORKFLOWS[0] else 102, "head_sha": NEW,
             "head_branch": "integration/slice", "event": "workflow_dispatch", "run_attempt": 1,
             "path": ".github/workflows/" + workflow, "name": workflow, "status": "completed",
             "conclusion": "success", "html_url": "https://github.com/owner/repo/actions/runs/101"}
    value.update(changes)
    return value


def jobs(workflow):
    return [{"name": "ci", "conclusion": "success", "steps": [
        {"name": name, "conclusion": "success"} for name in p.REQUIRED_STEPS[workflow]]}]


def pr():
    return {"number": 30, "html_url": "https://github.com/owner/repo/pull/30", "state": "open", "draft": False,
            "head": {"sha": NEW, "ref": "integration/slice", "repo": {"full_name": AUTH["repo"]}},
            "base": {"ref": "main"}}


class WorkflowRegressionTests(unittest.TestCase):
    def test_old_run_wrong_branch_and_wrong_event_never_satisfy_dispatch(self):
        candidates = [run(id=99), run(id=102, head_branch="main"), run(id=103, event="push"), run(id=104, head_sha=HEAD)]
        with patch.object(p, "workflow_runs", return_value=candidates):
            self.assertIsNone(p.find_workflow_run(WORKFLOWS[0], "integration/slice", NEW, **AUTH, after_run_id=100))

    def test_latest_new_matching_run_selected(self):
        with patch.object(p, "workflow_runs", return_value=[run(id=103), run(id=101), run(id=99)]):
            self.assertEqual(p.find_workflow_run(WORKFLOWS[0], "integration/slice", NEW, **AUTH, after_run_id=100)["id"], 103)

    def test_missing_skipped_and_failed_required_steps_rejected(self):
        for conclusion in (None, "skipped", "failure", "cancelled"):
            value = jobs(WORKFLOWS[0])
            if conclusion is None:
                value[0]["steps"].pop()
            else:
                value[0]["steps"][0]["conclusion"] = conclusion
            with self.subTest(conclusion=conclusion), patch.object(p, "all_jobs", return_value=value), self.assertRaises(p.GitHubError):
                p.verify_gate_steps(WORKFLOWS[0], run(), **AUTH)

    def test_optional_cache_step_may_skip(self):
        value = jobs(WORKFLOWS[1])
        value[0]["steps"].append({"name": "Create AVD snapshot", "conclusion": "skipped"})
        with patch.object(p, "all_jobs", return_value=value):
            p.verify_gate_steps(WORKFLOWS[1], run(WORKFLOWS[1]), **AUTH)

    def test_workflow_identity_must_match_gate(self):
        with self.assertRaises(p.GitHubError):
            p.verify_gate_steps(WORKFLOWS[0], run(WORKFLOWS[1]), **AUTH)

    def test_jobs_pagination_preserves_later_failure(self):
        pages = [{"jobs": [{"name": "passed", "conclusion": "success"}] * 100},
                 {"jobs": [{"name": "late job", "conclusion": "failure", "steps": [
                     {"name": "late step", "conclusion": "failure"}]}]}]
        with patch.object(p, "api_request", side_effect=pages) as api:
            self.assertEqual(p.first_failed_step(123, **AUTH), "late job: late step")
        self.assertIn("page=2", api.call_args_list[1].args[1])

    def test_timeout_cannot_return_pass(self):
        with patch.object(p, "require_head"), patch.object(p, "find_workflow_run", return_value=None), \
                patch.object(p.time, "monotonic", side_effect=[0, 0, 2]), patch.object(p.time, "sleep"), self.assertRaises(TimeoutError):
            p.wait_for_workflows(WORKFLOWS, "integration/slice", NEW, **AUTH, timeout_seconds=1)

    def test_cancelled_workflow_cannot_pass(self):
        with patch.object(p, "require_head"), patch.object(p, "find_workflow_run", side_effect=[run(conclusion="cancelled"), run(WORKFLOWS[1])]), \
                patch.object(p, "first_failed_step", return_value="ci: cancelled"), self.assertRaisesRegex(p.GitHubError, "cancelled"):
            p.wait_for_workflows(WORKFLOWS, "integration/slice", NEW, **AUTH, timeout_seconds=1)

    def test_branch_change_while_waiting_is_failure(self):
        with patch.object(p, "read_head", return_value=HEAD), self.assertRaisesRegex(p.GitHubError, "branch moved"):
            p.wait_for_workflows(WORKFLOWS, "integration/slice", NEW, **AUTH, timeout_seconds=1)

    def test_both_completed_runs_still_require_actual_steps(self):
        with patch.object(p, "require_head"), patch.object(p, "find_workflow_run", side_effect=[run(), run(WORKFLOWS[1])]), \
                patch.object(p, "all_jobs", side_effect=[jobs(WORKFLOWS[0]), jobs(WORKFLOWS[1])]):
            self.assertEqual(len(p.wait_for_workflows(WORKFLOWS, "integration/slice", NEW, **AUTH, timeout_seconds=1)), 2)

    def test_403_containing_404_text_is_not_branch_absence(self):
        with patch.object(p, "api_request", side_effect=p.GitHubError("HTTP 403: text mentions HTTP 404", status=403)), self.assertRaises(p.GitHubError):
            p.read_head("integration/slice", **AUTH)

    def test_stale_base_discovered_after_upload_never_updates_ref(self):
        api = FakeGitHub()
        with patch.object(p, "api_request", api), patch.object(p, "require_head", side_effect=[None, None, p.GitHubError("base moved")]), self.assertRaises(p.GitHubError):
            p.create_target_commit(manifest(), **AUTH)
        self.assertFalse(any("/git/ref" in path for _, path, _ in api.writes))


class PullRequestAndMergeTests(unittest.TestCase):
    def test_existing_matching_pr_is_reused_without_creation(self):
        with patch.object(p, "api_request", return_value=[pr()]) as api:
            self.assertEqual(p.ensure_pull_request(manifest(), NEW, **AUTH)["number"], 30)
        self.assertEqual(api.call_count, 1)

    def test_existing_wrong_repository_pr_is_rejected(self):
        value = pr()
        value["head"]["repo"]["full_name"] = "other/repo"
        with patch.object(p, "api_request", return_value=[value]), self.assertRaises(p.GitHubError):
            p.ensure_pull_request(manifest(), NEW, **AUTH)

    def test_pr_creation_records_pinned_commit(self):
        with patch.object(p, "api_request", side_effect=[[], pr()]) as api:
            p.ensure_pull_request(manifest(), NEW, **AUTH)
        self.assertIn(NEW, api.call_args.kwargs["body"]["body"])

    def test_merge_requires_two_distinct_workflows_before_any_api_call(self):
        for value in ([], [run()], [run(), run()]):
            with self.subTest(runs=value), patch.object(p, "api_request") as api, self.assertRaises(p.GitHubError):
                p.merge_verified(manifest(), 30, NEW, value, **AUTH)
            api.assert_not_called()

    def test_merge_never_happens_when_a_verified_run_is_rerun(self):
        with patch.object(p, "api_request", return_value=run(run_attempt=2)) as api, self.assertRaises(p.GitHubError):
            p.merge_verified(manifest(), 30, NEW, [run(), run(WORKFLOWS[1])], **AUTH)
        self.assertFalse(any(call.args[0] == "PUT" for call in api.call_args_list))

    def test_merge_is_sha_guarded_and_non_success_is_rejected(self):
        for merged in (True, False):
            with self.subTest(merged=merged), patch.object(p, "api_request", side_effect=[run(), run(WORKFLOWS[1]), pr(), {"merged": merged}]) as api, \
                    patch.object(p, "require_head"), patch.object(p, "verify_gate_steps"):
                if merged:
                    p.merge_verified(manifest(), 30, NEW, [run(), run(WORKFLOWS[1])], **AUTH)
                else:
                    with self.assertRaises(p.GitHubError):
                        p.merge_verified(manifest(), 30, NEW, [run(), run(WORKFLOWS[1])], **AUTH)
                self.assertEqual(api.call_args.kwargs["body"], {"sha": NEW, "merge_method": "squash"})


class MainReportingTests(unittest.TestCase):
    def execute(self, *, value=None, notification=None, waiting=None):
        with tempfile.TemporaryDirectory() as root, contextlib.ExitStack() as stack:
            request = Path(root) / "request.json"
            request.write_text(json.dumps(value or manifest()), encoding="utf-8")
            summary = Path(root) / "summary.md"
            stack.enter_context(patch.dict(os.environ, {"GITHUB_TOKEN": "test-secret", "GITHUB_REPOSITORY": "owner/repo", "GITHUB_STEP_SUMMARY": str(summary)}))
            stack.enter_context(patch.object(p.sys, "argv", ["publisher", "--manifest", str(request)]))
            upload = stack.enter_context(patch.object(p, "create_target_commit", return_value=NEW))
            stack.enter_context(patch.object(p, "ensure_pull_request", return_value=pr()))
            stack.enter_context(patch.object(p, "workflow_runs", return_value=[]))
            stack.enter_context(patch.object(p, "require_head"))
            dispatch = stack.enter_context(patch.object(p, "dispatch_workflow"))
            stack.enter_context(patch.object(p, "wait_for_workflows", side_effect=waiting, return_value=[run(), run(WORKFLOWS[1])]))
            notify = stack.enter_context(patch.object(p, "post_status", side_effect=notification))
            merge = stack.enter_context(patch.object(p, "merge_verified"))
            out, err = io.StringIO(), io.StringIO()
            stack.enter_context(contextlib.redirect_stdout(out))
            stack.enter_context(contextlib.redirect_stderr(err))
            result = p.main()
            return result, summary.read_text(), out.getvalue(), err.getvalue(), upload, dispatch, notify, merge

    def test_success_dispatches_both_and_notifies_without_default_merge(self):
        result, text, out, _, _, dispatch, notify, merge = self.execute()
        self.assertEqual(result, 0)
        self.assertEqual([c.args[0] for c in dispatch.call_args_list], WORKFLOWS)
        self.assertIn("CI_PASS_AWAITING_REVIEW", text)
        self.assertIn("PYTHON_UPLOAD_AND_CI_PASS", out)
        notify.assert_called_once()
        merge.assert_not_called()

    def test_empty_gates_fail_before_upload(self):
        result, text, out, _, upload, dispatch, _, merge = self.execute(value=manifest(dispatch_workflows=[]))
        self.assertEqual(result, 1)
        self.assertIn("FAILED", text)
        self.assertNotIn("PYTHON_UPLOAD_AND_CI_PASS", out)
        upload.assert_not_called()
        dispatch.assert_not_called()
        merge.assert_not_called()

    def test_failure_is_reported_and_secret_redacted(self):
        result, text, out, err, _, _, notify, merge = self.execute(waiting=p.GitHubError("failure test-secret"))
        self.assertEqual(result, 1)
        self.assertIn("[REDACTED]", text)
        self.assertNotIn("test-secret", text + out + err)
        self.assertNotIn("PYTHON_UPLOAD_AND_CI_PASS", out)
        notify.assert_called_once()
        merge.assert_not_called()

    def test_notification_failure_prevents_requested_merge(self):
        result, text, out, _, _, _, _, merge = self.execute(value=manifest(auto_merge=True), notification=p.GitHubError("comment forbidden"))
        self.assertEqual(result, 1)
        self.assertIn("FAILED", text)
        self.assertNotIn("PYTHON_UPLOAD_AND_CI_PASS", out)
        merge.assert_not_called()

    def test_failed_final_notification_does_not_claim_merge_was_undone(self):
        result, text, out, _, _, _, _, merge = self.execute(value=manifest(auto_merge=True), notification=[None, p.GitHubError("comment failed"), None])
        self.assertEqual(result, 1)
        self.assertIn("MERGED_NOTIFICATION_FAILED", text)
        self.assertNotIn("PYTHON_UPLOAD_AND_CI_PASS", out)
        merge.assert_called_once()

    def test_explicit_merge_only_after_ci_notification(self):
        result, text, _, _, _, _, notify, merge = self.execute(value=manifest(auto_merge=True))
        self.assertEqual(result, 0)
        self.assertIn("Status: **MERGED**", text)
        self.assertEqual(notify.call_count, 2)
        merge.assert_called_once()


class TransportTests(unittest.TestCase):
    def test_utf8_input_normalizes_exact_bytes(self):
        value = manifest(files=[{"path": "docs/example.md", "content_utf8": "unicode → λ\n"}])
        self.assertEqual(base64.b64decode(load(value)["files"][0]["content_base64"]), "unicode → λ\n".encode("utf-8"))

    def test_ambiguous_input_encoding_fails(self):
        value = manifest()
        value["files"][0]["content_utf8"] = "ambiguous"
        with self.assertRaises(ValueError):
            load(value)


if __name__ == "__main__":
    unittest.main()
