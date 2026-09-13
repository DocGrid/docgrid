"""Assert grouping, inhibition, and resolved delivery from captured webhook events."""

import json
import sys
from pathlib import Path


def load_events(path):
    """Read complete JSON lines while tolerating an empty file during polling."""
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def contains_alert(events, alertname):
    """Return whether any delivered alert has the requested alert name."""
    return any(
        alert.get("labels", {}).get("alertname") == alertname
        for event in events
        for alert in event.get("alerts", [])
    )


def assert_firing(events):
    """Require one grouped two-instance firing delivery and no inhibited delivery."""
    grouped = [
        event
        for event in events
        if event.get("status") == "firing"
        and event.get("commonLabels", {}).get("alertname") == "DocGridAlertmanagerE2EGrouped"
    ]
    assert grouped, "grouped firing webhook has not arrived"
    instances = {
        alert.get("labels", {}).get("instance")
        for alert in grouped[-1].get("alerts", [])
    }
    assert instances == {"backend-a", "backend-b"}, f"unexpected grouped instances: {instances}"
    assert not contains_alert(events, "DocGridAlertmanagerE2EDerived"), "inhibited alert was delivered"


def assert_resolved(events):
    """Require the grouped alert to be delivered again with resolved status."""
    assert_firing(events)
    resolved = [
        event
        for event in events
        if event.get("status") == "resolved"
        and event.get("commonLabels", {}).get("alertname") == "DocGridAlertmanagerE2EGrouped"
    ]
    assert resolved, "grouped resolved webhook has not arrived"


if __name__ == "__main__":
    event_log = Path(sys.argv[1])
    mode = sys.argv[2]
    checks = {"firing": assert_firing, "resolved": assert_resolved}
    checks[mode](load_events(event_log))
