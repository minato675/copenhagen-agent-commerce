"""Bounded, execution-local memoization of fully validated deterministic results.

Keys contain complete serialized inputs, not object identities or selected
fields. Cache misses use the original validation function; failed validations
are never stored. No cache survives the owning execution, including cancellation.
"""

from collections import OrderedDict
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime
from functools import wraps
import json

from pydantic_core import PydanticSerializationError


_active = ContextVar("fds_execution_validation_memo", default=None)


def _typed_value(value):
    if isinstance(value, datetime):
        return {"datetime": value.isoformat(), "fold": value.fold}
    raise TypeError("unsupported validation memo value")


class _Memo:
    def __init__(self, max_entries, max_key_bytes):
        self.max_entries, self.max_key_bytes = max_entries, max_key_bytes
        self.entries = OrderedDict()
        self.key_bytes = 0
        self.closed = False
        self.hits = self.misses = 0

    def clear(self):
        self.entries.clear()
        self.key_bytes = 0
        self.closed = True


@contextmanager
def validation_memo(*, max_entries=8192, max_key_bytes=64 * 1024 * 1024):
    """Use an isolated cache; nested executions restore the enclosing context."""
    if any(type(value) is not int or value <= 0 for value in (max_entries, max_key_bytes)):
        raise ValueError("validation memo limits must be positive integers")
    memo = _Memo(max_entries, max_key_bytes)
    token = _active.set(memo)
    try:
        yield memo
    finally:
        memo.clear()
        _active.reset(token)


def scoped_validation_memo(function):
    """Open one scope for a complete asynchronous harness invocation."""
    @wraps(function)
    async def wrapped(*args, **kwargs):
        with validation_memo():
            return await function(*args, **kwargs)
    return wrapped


def memoized_validation(namespace, supplied, validate):
    """Reuse only a successful result for the exact complete serialized input.

    Serialization warnings/errors take the original validation path. This keeps
    the original behavior for malformed or unusually copied typed instances.
    A changed model_copy therefore cannot inherit a previous object's result.
    Values are limited to immutable hashes/references and successful check flags.
    """
    memo = _active.get()
    if memo is None or memo.closed:
        return validate()
    try:
        # JSON model serialization can collapse NaN to null. Dump Python values
        # first and forbid nonfinite numbers so copied invalid values cannot hit
        # a valid optional/null input. Datetimes retain a distinct representation.
        payload = json.dumps(supplied.model_dump(mode="python", round_trip=True, warnings="error"),
            default=_typed_value, ensure_ascii=False, allow_nan=False,
            separators=(",", ":")).encode("utf-8")
    except (PydanticSerializationError, TypeError, ValueError):
        return validate()
    key = (namespace, payload)
    size = len(namespace.encode("utf-8")) + len(payload)
    if key in memo.entries:
        memo.hits += 1
        memo.entries.move_to_end(key)
        return memo.entries[key]
    memo.misses += 1
    result = validate()
    if size > memo.max_key_bytes:
        return result
    while memo.entries and (len(memo.entries) >= memo.max_entries or memo.key_bytes + size > memo.max_key_bytes):
        old, _ = memo.entries.popitem(last=False)
        memo.key_bytes -= len(old[0].encode("utf-8")) + len(old[1])
    memo.entries[key] = result
    memo.key_bytes += size
    return result
