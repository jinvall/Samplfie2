"""Core models, enumerations, stable IDs, and lifecycle transition rules for APIDACS."""

from __future__ import annotations

import hashlib
from enum import Enum
from typing import Dict, List, Set


class Confidence(str, Enum):
    CONFIRMED = "Confirmed"
    STRONG = "Strong"
    INFERRED = "Inferred"
    UNKNOWN = "Unknown"
    CONTRADICTORY = "Contradictory"


class VariableStage(str, Enum):
    DECLARED = "DECLARED"
    INITIALIZED = "INITIALIZED"
    ASSIGNED = "ASSIGNED"
    READ = "READ"
    PROPAGATED = "PROPAGATED"
    TRANSFORMED = "TRANSFORMED"
    CONSUMED = "CONSUMED"
    VERIFIED = "VERIFIED"
    DISCONNECTED = "DISCONNECTED"
    UNRESOLVED = "UNRESOLVED"
    OBSOLETE = "OBSOLETE"


class FeatureStatus(str, Enum):
    PLANNED = "PLANNED"
    STUBBED = "STUBBED"
    PARTIALLY_IMPLEMENTED = "PARTIALLY_IMPLEMENTED"
    IMPLEMENTED_UNVERIFIED = "IMPLEMENTED_UNVERIFIED"
    IMPLEMENTED_TESTED = "IMPLEMENTED_TESTED"
    VERIFIED = "VERIFIED"
    BROKEN = "BROKEN"
    DEPRECATED = "DEPRECATED"


class FindingState(str, Enum):
    NEW = "NEW"
    REGISTERED = "REGISTERED"
    PENDING = "PENDING"
    INVESTIGATING = "INVESTIGATING"
    INVESTIGATED = "INVESTIGATED"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    APPROVED = "APPROVED"
    REMEDIATION_PLANNED = "REMEDIATION_PLANNED"
    REMEDIATION_IN_PROGRESS = "REMEDIATION_IN_PROGRESS"
    VERIFICATION_PENDING = "VERIFICATION_PENDING"
    VERIFYING = "VERIFYING"
    RESOLUTION_VALIDATION = "RESOLUTION_VALIDATION"
    CLOSED = "CLOSED"
    STALE = "STALE"
    REOPENED = "REOPENED"
    BLOCKED = "BLOCKED"
    DEFERRED = "DEFERRED"


class PrimaryClassification(str, Enum):
    CONFIRMED_UNUSED = "CONFIRMED_UNUSED"
    DISCONNECTED_DATA_FLOW = "DISCONNECTED_DATA_FLOW"
    INCOMPLETE_IMPLEMENTATION = "INCOMPLETE_IMPLEMENTATION"
    INDIRECTLY_CONSUMED = "INDIRECTLY_CONSUMED"
    FRAMEWORK_MANAGED = "FRAMEWORK_MANAGED"
    EXTERNAL_CONTRACT = "EXTERNAL_CONTRACT"
    INTENTIONALLY_UNUSED = "INTENTIONALLY_UNUSED"
    OBSOLETE = "OBSOLETE"
    FALSE_POSITIVE = "FALSE_POSITIVE"
    UNRESOLVED = "UNRESOLVED"


class EvidenceConfidence(str, Enum):
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INSUFFICIENT = "INSUFFICIENT"


class EvidenceFreshness(str, Enum):
    CURRENT = "CURRENT"
    STALE = "STALE"
    INVALIDATED = "INVALIDATED"
    UNVERIFIED = "UNVERIFIED"


class ImpactCategory(str, Enum):
    NONE_DEMONSTRATED = "NONE_DEMONSTRATED"
    POTENTIAL = "POTENTIAL"
    LOCALIZED = "LOCALIZED"
    FEATURE_LEVEL = "FEATURE_LEVEL"
    CROSS_COMPONENT = "CROSS_COMPONENT"
    SYSTEM_WIDE = "SYSTEM_WIDE"
    UNKNOWN = "UNKNOWN"


class Disposition(str, Enum):
    FIX_REQUIRED = "FIX_REQUIRED"
    REMOVE_APPROVED = "REMOVE_APPROVED"
    RETAIN_INTENTIONAL = "RETAIN_INTENTIONAL"
    RETAIN_EXTERNAL = "RETAIN_EXTERNAL"
    RETAIN_FRAMEWORK = "RETAIN_FRAMEWORK"
    FALSE_POSITIVE_ACCEPTED = "FALSE_POSITIVE_ACCEPTED"
    NO_CHANGE_REQUIRED = "NO_CHANGE_REQUIRED"
    FURTHER_INVESTIGATION = "FURTHER_INVESTIGATION"
    DEFERRED = "DEFERRED"


class VerificationCategory(str, Enum):
    STATIC_ANALYSIS = "Static analysis"
    COMPILATION_BUILD = "Compilation/build"
    UNIT_TESTING = "Unit testing"
    INTEGRATION_TESTING = "Integration testing"
    UI_TESTING = "UI testing"
    E2E_TESTING = "End-to-end testing"
    DEVICE_PLATFORM_TESTING = "Device/platform testing"
    RUNTIME_TESTING = "Runtime testing"
    PERFORMANCE_TESTING = "Performance testing"
    REGRESSION_TESTING = "Regression testing"


class EnforcementMode(str, Enum):
    AUDIT = "AUDIT"
    MONITOR = "MONITOR"
    ENFORCEMENT = "ENFORCEMENT"


class ComplianceDecision(str, Enum):
    PASS = "PASS"
    PASS_WITH_WARNINGS = "PASS_WITH_WARNINGS"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    BLOCKED = "BLOCKED"
    ANALYSIS_INCOMPLETE = "ANALYSIS_INCOMPLETE"


MANDATORY_REPORT_FILES: List[str] = [
    "PROJECT_INTEGRITY.md",
    "DEPENDENCIES.md",
    "VARIABLES.md",
    "TYPES.md",
    "FUNCTIONS.md",
    "DATA_FLOW.md",
    "FEATURE_MAP.md",
    "UI_BACKEND_MAP.md",
    "ISSUES.md",
    "CHANGE_IMPACT.md",
    "TEST_COVERAGE.md",
    "EXCEPTIONS.md",
    "REGISTRY_STATUS.md",
    "CHANGE_HISTORY.md",
]


NO_CHANGE_DISPOSITIONS: Set[str] = {
    Disposition.RETAIN_INTENTIONAL.value,
    Disposition.RETAIN_EXTERNAL.value,
    Disposition.RETAIN_FRAMEWORK.value,
    Disposition.FALSE_POSITIVE_ACCEPTED.value,
    Disposition.NO_CHANGE_REQUIRED.value,
}


# Valid state transitions enforced by APIDACSI lifecycle engine
VALID_FINDING_TRANSITIONS: Dict[str, Set[str]] = {
    FindingState.NEW.value: {
        FindingState.REGISTERED.value,
        FindingState.PENDING.value,
    },
    FindingState.REGISTERED.value: {
        FindingState.PENDING.value,
        FindingState.INVESTIGATING.value,
        FindingState.DEFERRED.value,
        FindingState.BLOCKED.value,
    },
    FindingState.PENDING.value: {
        FindingState.INVESTIGATING.value,
        FindingState.INVESTIGATED.value,
        FindingState.DEFERRED.value,
        FindingState.BLOCKED.value,
    },
    FindingState.INVESTIGATING.value: {
        FindingState.INVESTIGATED.value,
        FindingState.REVIEW_REQUIRED.value,
        FindingState.BLOCKED.value,
        FindingState.DEFERRED.value,
        FindingState.STALE.value,
    },
    FindingState.INVESTIGATED.value: {
        FindingState.REVIEW_REQUIRED.value,
        FindingState.REMEDIATION_PLANNED.value,
        FindingState.APPROVED.value,
        FindingState.RESOLUTION_VALIDATION.value,
        FindingState.INVESTIGATING.value,
        FindingState.STALE.value,
        FindingState.BLOCKED.value,
        FindingState.DEFERRED.value,
    },
    FindingState.REVIEW_REQUIRED.value: {
        FindingState.APPROVED.value,
        FindingState.REMEDIATION_PLANNED.value,
        FindingState.INVESTIGATING.value,
        FindingState.DEFERRED.value,
        FindingState.BLOCKED.value,
        FindingState.STALE.value,
    },
    FindingState.REMEDIATION_PLANNED.value: {
        FindingState.APPROVED.value,
        FindingState.REVIEW_REQUIRED.value,
        FindingState.INVESTIGATING.value,
        FindingState.BLOCKED.value,
        FindingState.DEFERRED.value,
        FindingState.STALE.value,
    },
    FindingState.APPROVED.value: {
        FindingState.REMEDIATION_IN_PROGRESS.value,
        FindingState.RESOLUTION_VALIDATION.value,
        FindingState.CLOSED.value,  # only via closure validation for no-change dispositions
        FindingState.STALE.value,
        FindingState.BLOCKED.value,
    },
    FindingState.REMEDIATION_IN_PROGRESS.value: {
        FindingState.VERIFICATION_PENDING.value,
        FindingState.BLOCKED.value,
        FindingState.STALE.value,
    },
    FindingState.VERIFICATION_PENDING.value: {
        FindingState.VERIFYING.value,
        FindingState.REMEDIATION_IN_PROGRESS.value,
        FindingState.STALE.value,
        FindingState.BLOCKED.value,
    },
    FindingState.VERIFYING.value: {
        FindingState.RESOLUTION_VALIDATION.value,
        FindingState.VERIFICATION_PENDING.value,
        FindingState.REMEDIATION_PLANNED.value,
        FindingState.BLOCKED.value,
        FindingState.STALE.value,
    },
    FindingState.RESOLUTION_VALIDATION.value: {
        FindingState.CLOSED.value,
        FindingState.REMEDIATION_PLANNED.value,
        FindingState.INVESTIGATING.value,
        FindingState.BLOCKED.value,
        FindingState.STALE.value,
    },
    FindingState.CLOSED.value: {
        FindingState.REOPENED.value,
        FindingState.STALE.value,
    },
    FindingState.STALE.value: {
        FindingState.INVESTIGATING.value,
        FindingState.PENDING.value,
        FindingState.REOPENED.value,
    },
    FindingState.REOPENED.value: {
        FindingState.PENDING.value,
        FindingState.INVESTIGATING.value,
        FindingState.REMEDIATION_PLANNED.value,
    },
    FindingState.BLOCKED.value: {
        FindingState.PENDING.value,
        FindingState.INVESTIGATING.value,
        FindingState.REMEDIATION_PLANNED.value,
        FindingState.DEFERRED.value,
        FindingState.STALE.value,
    },
    FindingState.DEFERRED.value: {
        FindingState.PENDING.value,
        FindingState.INVESTIGATING.value,
        FindingState.REOPENED.value,
    },
}


def stable_id(prefix: str, *parts: str, length: int = 12) -> str:
    """Create a deterministic identifier from canonical parts."""
    normalized = "::".join("" if p is None else str(p) for p in parts)
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:length]
    return f"{prefix}_{digest}"


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()
