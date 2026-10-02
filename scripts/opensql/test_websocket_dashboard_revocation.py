"""Check that dual-backend revocation evidence keeps routing and timing boundaries explicit."""

import unittest

from websocket_dashboard_revocation import backend_for, classify, parse_dashboard_frame, stomp_frames


class DashboardAbRevocationTest(unittest.TestCase):
    """Cover deterministic A/B assignment and response-boundary classification only."""

    def test_assigns_the_requested_tail_to_b(self):
        self.assertEqual([backend_for(index, 5, 2) for index in range(5)],
                         ["A", "A", "A", "B", "B"])

    def test_single_backend_mode_remains_a(self):
        self.assertEqual([backend_for(index, 3, 0) for index in range(3)],
                         ["A", "A", "A"])

    def test_separates_new_decision_from_late_delivery(self):
        self.assertEqual(classify(1002, 1005, 1000), "200_이후_새_판정_후보")
        self.assertEqual(classify(998, 1005, 1000), "200_전_발행_늦은_수신")
        self.assertEqual(classify(1000, 1005, 1000), "경계_시각_불명확")

    def test_split_coalesced_stomp_frames(self):
        self.assertEqual(stomp_frames("CONNECTED\n\n\0MESSAGE\nx:y\n\n{}\0"),
                         ["CONNECTED\n\n", "MESSAGE\nx:y\n\n{}"])

    def test_count_only_does_not_misread_real_summary_as_send_timestamp(self):
        frame = 'MESSAGE\n\n{"documents":{"total":12,"searchable":8}}'
        self.assertEqual(parse_dashboard_frame(frame, True), (None, None))
        self.assertEqual(parse_dashboard_frame(frame, False), (12, 8))


if __name__ == "__main__":
    unittest.main()
