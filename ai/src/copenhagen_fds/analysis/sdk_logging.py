"""Suppress provider wire logs only within this adapter's async call context."""

from contextlib import contextmanager
from contextvars import ContextVar
import logging


_active = ContextVar("fds_provider_call", default=False)


class _ProviderLogFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        return not _active.get()


_filter = _ProviderLogFilter()


@contextmanager
def private_provider_call():
    # SDK and HTTP clients are imported/constructed before entering this scope.
    # Filters stay attached; the ContextVar prevents affecting other requests.
    for name in tuple(logging.Logger.manager.loggerDict):
        if name.split(".")[0] in {"openai", "httpx2", "httpcore2"}:
            logging.getLogger(name).addFilter(_filter)
    token = _active.set(True)
    try:
        yield
    finally:
        _active.reset(token)
