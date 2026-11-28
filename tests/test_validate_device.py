"""Wave 3 regression tests for A31: malformed nested device data must
produce field feedback, never a server error. Before the fix,
validate_device called display.get before checking that display is an
object, so an admin edit with a string/list display raised
AttributeError (HTTP 500).
"""

import pytest

from backend.app import validate_device


def valid_record():
    return {
        "id": "test-one", "brand": "Test", "model": "One", "uaModel": "One",
        "soc": "SoC", "gpu": "GPU", "cores": 8,
        "display": {"w": 1080, "h": 2400, "dpr": 3.0},
        "ramGb": [8], "storageGb": [128], "osVersions": ["15"], "builds": {},
    }


def test_valid_record_passes():
    assert validate_device(valid_record()) == []


@pytest.mark.parametrize("bad_display", ["1080x2400", [1080, 2400], 1080, True])
def test_non_object_display_is_field_feedback_not_a_crash(bad_display):
    record = valid_record()
    record["display"] = bad_display
    errors = validate_device(record)
    assert "display must be an object" in errors
