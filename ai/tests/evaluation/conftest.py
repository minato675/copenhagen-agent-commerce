"""One immutable batch for deterministic rubric tests; no credential loading."""

import asyncio
import pytest

from copenhagen_fds.evaluation.cases import load_dataset
from copenhagen_fds.evaluation.offline import deny_network, run_mock_dataset


@pytest.fixture(scope="module")
def dataset():
    return load_dataset()


@pytest.fixture(scope="module")
def batch(dataset):
    with asyncio.Runner() as runner:
        runner.get_loop()
        with deny_network():
            return runner.run(run_mock_dataset(dataset))
