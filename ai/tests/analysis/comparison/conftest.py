"""Shared synthetic fixtures; only the internal asyncio socketpair is allowed."""

import asyncio
import socket
import pytest

from copenhagen_fds.contracts import IntentExtractionOutput
from copenhagen_fds.openai_settings import load_openai_settings
from .support import StubAdapter, completion, output


@pytest.fixture
def run_async():
    # Windows asyncio needs its internal wake-up socketpair before network blocking.
    with asyncio.Runner() as runner:
        runner.get_loop()
        yield runner.run


@pytest.fixture(autouse=True)
def offline_analysis_tests(monkeypatch, run_async):
    def forbidden(*args, **kwargs):
        pytest.fail("comparison tests must not open network connections")
    monkeypatch.setattr(socket, "create_connection", forbidden)
    monkeypatch.setattr(socket.socket, "connect", forbidden)
    monkeypatch.setattr(socket.socket, "connect_ex", forbidden)


@pytest.fixture
def settings():
    return load_openai_settings({
        "FDS_OPENAI_TIMEOUT_SECONDS": "5", "FDS_EVALUATION_TIMEOUT_SECONDS": "10",
    })


@pytest.fixture
def extracted(extraction_payload):
    extraction_payload["ambiguities"] = None
    return extraction_payload


@pytest.fixture
def comparison_request(analysis_request, extracted):
    return analysis_request.with_extracted_intent(IntentExtractionOutput.model_validate(extracted))


@pytest.fixture
def adapter(settings, extracted):
    return StubAdapter(settings, {
        "extraction": completion("extraction", extracted), "comparison": completion("comparison", output()),
    })
