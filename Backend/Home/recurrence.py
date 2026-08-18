"""Expanding a repeat rule into concrete dates.

Occurrences are materialised as individual Session rows, so this module only
has to answer "which dates does this rule land on".
"""

import calendar
from datetime import date, timedelta

from .models import Repeat

# A generous ceiling. Enough for a full semester of weekly classes, low enough
# that a bad `until` cannot write thousands of rows.
MAX_OCCURRENCES = 60


def expand(start: date, repeat: str, interval: int = 1, count: int = 1, until=None):
    """Dates for a repeat rule, always including `start`.

    `count` and `until` are both accepted; whichever runs out first wins, and
    the total is capped at MAX_OCCURRENCES either way.

    Monthly repeats keep the day-of-month and *skip* months that are too short
    rather than clamping. A class on the 31st recurring monthly happens only in
    31-day months — surprising either way, but skipping never silently moves a
    class to a date the teacher did not choose.
    """
    if interval < 1:
        raise ValueError("interval must be at least 1")

    if repeat == Repeat.NONE:
        return [start]

    limit = min(max(count, 1), MAX_OCCURRENCES)
    dates = []
    step = 0

    # Bound the walk so a distant `until` with short months cannot spin.
    while len(dates) < limit and step < MAX_OCCURRENCES * 12:
        candidate = _advance(start, repeat, interval, step)
        step += 1

        if candidate is None:  # month too short for this day-of-month
            continue
        if until is not None and candidate > until:
            break
        dates.append(candidate)

    return dates


def _advance(start: date, repeat: str, interval: int, step: int):
    if repeat == Repeat.DAILY:
        return start + timedelta(days=interval * step)

    if repeat == Repeat.WEEKLY:
        return start + timedelta(weeks=interval * step)

    if repeat == Repeat.MONTHLY:
        months = interval * step
        year = start.year + (start.month - 1 + months) // 12
        month = (start.month - 1 + months) % 12 + 1
        if start.day > calendar.monthrange(year, month)[1]:
            return None
        return date(year, month, start.day)

    raise ValueError(f"Unsupported repeat rule: {repeat!r}")
