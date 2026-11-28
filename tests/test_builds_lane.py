"""Wave 5 regression test for C11 (owner decision 2026-11-28): the
Android-build and IPSW collectors get a scheduled monthly lane that
opens draft PRs only. The lane must never write the live catalog,
must surface collector failures honestly (C13 rule), and must fail
loudly on a data alarm instead of opening a PR.
"""

from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "nexora-builds-refresh.yml"


def text():
    return WORKFLOW.read_text(encoding="utf-8")


def test_lane_is_scheduled_monthly_and_manual():
    t = text()
    assert 'cron: "40 5 1 * *"' in t
    assert "workflow_dispatch:" in t


def test_lane_runs_both_autonomous_collectors():
    t = text()
    assert "tools/collect_android_builds.py" in t
    assert "tools/collect_ipsw_builds.py" in t
    # The input-driven app-version collector stays manual by design.
    assert "collect_app_versions" not in t


def test_lane_never_writes_the_live_catalog():
    t = text()
    assert "data/proposals/builds/devices.android-builds.json" in t
    assert "data/proposals/builds/devices.ipsw-builds.json" in t
    prepare = t.split("- name: Prepare review files")[1].split("- name: Upload collection artifacts")[0]
    assert "web/devices.json" not in prepare
    assert "app/src/main/assets/devices.json" not in prepare


def test_lane_opens_draft_pr_only_after_the_gate():
    t = text()
    assert "draft: true" in t
    assert "if: steps.gate.outcome == 'success'" in t
    assert "create-pull-request" in t


def test_collector_steps_report_real_exit_codes():
    t = text()
    assert t.count('echo "exit_code=$RC" >> "$GITHUB_OUTPUT"') == 2
    assert t.count("exit $RC") == 2
    assert t.count("continue-on-error: true") == 2


def test_gate_fails_on_data_alarm():
    t = text()
    gate = t.split("- name: Gate on collection alarms")[1].split("- name: Prepare review files")[0]
    assert '"$ANDROID_RC" = "2"' in gate and '"$IPSW_RC" = "2"' in gate
    assert "exit 1" in gate
