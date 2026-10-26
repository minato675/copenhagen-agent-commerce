"""One monotonic deadline shared by every stage of one analysis."""

from collections.abc import Callable
from dataclasses import dataclass, field
from math import isfinite
import time
from typing import Self


@dataclass(frozen=True, init=False)
class AnalysisBudget:
    total_seconds: float
    _deadline: float = field(repr=False)
    _clock: Callable[[], float] = field(repr=False)

    def __init__(self, seconds: float, *, clock: Callable[[], float] = time.monotonic):
        if type(seconds) not in (int, float) or not isfinite(seconds) or seconds <= 0:
            raise ValueError("analysis budget must be positive and finite")
        object.__setattr__(self, "total_seconds", float(seconds))
        object.__setattr__(self, "_deadline", clock() + seconds)
        object.__setattr__(self, "_clock", clock)

    @classmethod
    def start(cls, seconds: float, *, clock: Callable[[], float] = time.monotonic) -> Self:
        return cls(seconds, clock=clock)

    @property
    def remaining_seconds(self) -> float:
        return max(0.0, self._deadline - self._clock())

    @property
    def elapsed_seconds(self) -> float:
        """Consumed time, including overruns; do not infer this from clamped remaining time."""
        return max(0.0, self._clock() - (self._deadline - self.total_seconds))
