"""Contract tests for the fail-closed runner evidence gate (not Android tests)."""
import unittest
from verify_step3_instrumentation import EXPECTED, verify


def events(test, code):
    cls, name = test
    return (f"INSTRUMENTATION_STATUS: class={cls}\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            "INSTRUMENTATION_STATUS: numtests=5\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def transcript():
    return "".join(events(test, 1) + events(test, 0) for test in sorted(EXPECTED)) + \
        "INSTRUMENTATION_RESULT: stream=\nTime: 1\n\nOK (5 tests)\nINSTRUMENTATION_CODE: -1\n"


class InstrumentationEvidenceGateTest(unittest.TestCase):
    def test_requires_every_real_test_completion(self):
        self.assertEqual(sorted(EXPECTED), verify(transcript()))

    def test_zero_test_success_is_failure(self):
        with self.assertRaises(ValueError):
            verify("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_discovery_only_is_failure(self):
        with self.assertRaises(ValueError):
            verify("".join(events(test, 1) for test in EXPECTED) + "OK (5 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_missing_test_is_failure(self):
        test = sorted(EXPECTED)[0]
        with self.assertRaises(ValueError):
            verify(transcript().replace(events(test, 1) + events(test, 0), ""))

    def test_failed_skipped_and_assumption_failure_are_not_pass(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                verify(transcript().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}", 1))

    def test_duplicate_test_is_failure(self):
        test = sorted(EXPECTED)[0]
        with self.assertRaises(ValueError):
            verify(events(test, 1) + events(test, 0) + transcript())

    def test_completion_without_start_is_failure(self):
        with self.assertRaises(ValueError):
            verify(transcript().replace(events(sorted(EXPECTED)[0], 1), ""))

    def test_missing_terminal_result_is_failure(self):
        with self.assertRaises(ValueError):
            verify(transcript().replace("INSTRUMENTATION_CODE: -1", ""))

    def test_incorrect_count_is_failure(self):
        with self.assertRaises(ValueError):
            verify(transcript().replace("numtests=5", "numtests=0"))

    def test_unknown_tests_are_failure(self):
        with self.assertRaises(ValueError):
            verify(transcript().replace("Step3ComposeRepairTest", "SomethingElse"))


if __name__ == "__main__":
    unittest.main()
