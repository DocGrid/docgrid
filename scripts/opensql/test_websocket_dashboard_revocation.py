"""Pin down the timing boundary used by the cloud revocation observation."""

import unittest

from websocket_dashboard_revocation import classify, stomp_frames


class RevocationTimingTest(unittest.TestCase):
    """Separate a newly created push from pre-revocation in-flight delivery."""

    def test_new_decision_candidate_after_http_200(self):
        self.assertEqual(classify(1003, 1008, 1001.0), "200_이후_새_판정_후보")

    def test_late_delivery_of_old_push(self):
        self.assertEqual(classify(999, 1010, 1001.0), "200_전_발행_늦은_수신")

    def test_clock_boundary_is_ambiguous(self):
        self.assertEqual(classify(1001, 1002, 1001.0), "경계_시각_불명확")

    def test_split_coalesced_stomp_frames(self):
        self.assertEqual(stomp_frames("CONNECTED\n\n\0MESSAGE\nx:y\n\n{}\0"),
                         ["CONNECTED\n\n", "MESSAGE\nx:y\n\n{}"])


if __name__ == "__main__":
    unittest.main()
