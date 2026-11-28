"""Wave 5 regression test for C13: the specs-refresh workflow must
report real outcomes — a failing strict validation fails its step
(and therefore blocks the draft-PR step), and the collector step
exits with the collector's own code instead of an unconditional
success. Before the fix, the validate step ended with a successful
`cat` and the collector step ended with `exit 0`.
"""

from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / ".github" / "workflows" / "nexora-specs-refresh.yml"


def workflow_text():
    return WORKFLOW.read_text(encoding="utf-8")


def test_strict_gate_fails_its_step():
    text = workflow_text()
    assert 'echo "strict_exit_code=$STRICT_RC" >> "$GITHUB_OUTPUT"' in text
    assert 'if [ "$STRICT_RC" -ne 0 ]; then' in text
    assert 'exit "$STRICT_RC"' in text
    # The draft-PR step stays conditioned on the validate step, so a
    # red validate step cannot produce a PR.
    assert "if: steps.validate.outcome == 'success'" in text


def test_collector_step_reports_its_own_exit_code():
    text = workflow_text()
    assert "continue-on-error: true" in text  # degraded mode still runs the refresh
    assert "exit $RC" in text
    collect_block = text.split("id: collect")[1].split("- name: Apply verified official findings")[0]
    assert "exit 0" not in collect_block, "collector step still swallows failures with exit 0"
