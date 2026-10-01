"""Ensure SQL statistics are classified without retaining raw SQL or database names."""

import json
import unittest

from dashboard_pgstat_snapshot import classify, safe_rows


class DashboardPgstatSnapshotTest(unittest.TestCase):
    """Keep authorization query counters while discarding SQL text and identifiers."""

    def test_classifies_single_user_role_query(self):
        sql = ("select r1_0.code from user_roles ur1_0 join roles r1_0 "
               "on r1_0.id=ur1_0.role_id where ur1_0.user_id=$1")
        self.assertEqual(classify(sql), "사용자별_역할_조회")

    def test_classifies_batch_admin_query(self):
        sql = ("select distinct ur1_0.user_id from user_roles ur1_0 join roles r1_0 "
               "on r1_0.id=ur1_0.role_id where ur1_0.user_id in ($1) and r1_0.code=$2")
        self.assertEqual(classify(sql), "admin_일괄_조회")

    def test_drops_query_text_before_output(self):
        source = {"datname": "private-db", "queryid": 42, "calls": 20,
                  "total_exec_time": 115.5, "rows": 20,
                  "query": "SELECT pg_is_in_recovery()",
                  "extra_private_text": "secret@example.invalid"}
        result = safe_rows(json.dumps(source), "private-db")
        output = json.dumps(result)
        self.assertEqual(result[0]["누적_호출"], 20)
        self.assertNotIn("private-db", output)
        self.assertNotIn("secret@example.invalid", output)

    def test_other_database_is_excluded(self):
        source = {"datname": "other", "queryid": 42, "calls": 20,
                  "total_exec_time": 115.5, "rows": 20,
                  "query": "SELECT pg_is_in_recovery()"}
        self.assertEqual(safe_rows(json.dumps(source), "app"), [])

    def test_aggregates_same_statement_across_database_users(self):
        rows = [
            {"datname": "app", "queryid": 1, "calls": 3, "total_exec_time": 1.2,
             "rows": 3, "query": "SELECT pg_is_in_recovery()"},
            {"datname": "app", "queryid": 1, "calls": 4, "total_exec_time": 2.3,
             "rows": 4, "query": "SELECT pg_is_in_recovery()"},
        ]
        result = safe_rows("\n".join(json.dumps(row) for row in rows), "app")
        self.assertEqual(result[0]["누적_호출"], 7)
        self.assertEqual(result[0]["통계_행수"], 2)


if __name__ == "__main__":
    unittest.main()
