import xml.etree.ElementTree as ET
from pathlib import Path
import runpy

import pytest

# Pytest's configured pythonpath includes src/, but not the scripts/ directory.
# Load this repository-local CLI file explicitly without altering sys.path.
assert_android_onnx_pass = runpy.run_path(
    str(Path(__file__).resolve().parents[1] / "scripts" / "ci_assert_android_onnx.py")
)["assert_android_onnx_pass"]


def report(tmp_path, status=None, cls="dev.russiankeyboard.prototype.OnDeviceRankerTest", name="quantizedOnnxScoresMatchPythonOnTheEmulator"):
    root = ET.Element("testsuite")
    case = ET.SubElement(root, "testcase", {"classname": cls, "name": name})
    if status:
        ET.SubElement(case, status)
    target = tmp_path / "TEST-emulator.xml"
    ET.ElementTree(root).write(target)
    return tmp_path


def test_requires_real_successful_testcase(tmp_path):
    assert assert_android_onnx_pass(report(tmp_path)) == 1


@pytest.mark.parametrize("status", ["skipped", "failure", "error"])
def test_does_not_report_skipped_or_failed_test_as_green(tmp_path, status):
    with pytest.raises(AssertionError, match="skipped or failed"):
        assert_android_onnx_pass(report(tmp_path, status=status))


def test_missing_results_fail_closed(tmp_path):
    with pytest.raises(AssertionError, match="No Android instrumentation"):
        assert_android_onnx_pass(tmp_path)


def test_wrong_test_case_cannot_fake_pass(tmp_path):
    with pytest.raises(AssertionError, match="Expected exactly one"):
        assert_android_onnx_pass(report(tmp_path, name="someOtherTest"))
