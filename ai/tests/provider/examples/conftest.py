"""S15P21A207-299: optional local export for manual inspection; not a BE runtime."""

from tests.learning.conftest import population
from tests.learning.policy.conftest import policy
from tests.provider.profiles.conftest import profile_case


def pytest_addoption(parser):
    parser.addoption("--provider-examples", default=None, help="Write validated provider JSON/schema examples under ai/results")
