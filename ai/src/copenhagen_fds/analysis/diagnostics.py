"""Convert errors to fixed classifications without copying messages or values."""

from typing import get_args

from copenhagen_fds.contracts.analysis_diagnostics import PathField, ValidationDiagnostic
from copenhagen_fds.contracts.analysis_results import AnalysisFailure


_FIELDS = frozenset(get_args(PathField))
_TYPES = frozenset({"int_type", "float_type", "string_type", "bool_type", "list_type",
                    "tuple_type", "dict_type", "model_type", "model_attributes_type"})


def schema_failure(phase, code, *, path=(), http_status=None):
    return AnalysisFailure(kind="SCHEMA_ERROR", http_status=http_status, retryable=False,
                           diagnostics=(ValidationDiagnostic(phase=phase, code=code, path=path),))


def validation_failure(error, phase, *, http_status=None):
    diagnostics = []
    for detail in error.errors(include_url=False, include_input=False, include_context=False)[:8]:
        kind = detail["type"]
        location = tuple(part if (isinstance(part, str) and part in _FIELDS) or
                         (type(part) is int and 0 <= part <= 100000) else "*"
                         for part in detail["loc"][:12])
        error_phase = phase
        if kind == "comparison_state":
            error_phase, code = "COMPARISON_STATE", "STATE_CONFLICT"
        elif kind == "duplicate_reference":
            error_phase, code = "ITEM_REFERENCE", "DUPLICATE_REFERENCE"
        elif kind == "missing":
            code = "MISSING_FIELD"
        elif kind == "extra_forbidden":
            code = "EXTRA_FIELD"
        else:
            code = "INVALID_TYPE" if kind in _TYPES else "INVALID_VALUE"
        diagnostics.append(ValidationDiagnostic(phase=error_phase, code=code, path=location))
    return AnalysisFailure(kind="SCHEMA_ERROR", http_status=http_status, retryable=False,
                           diagnostics=tuple(diagnostics))
