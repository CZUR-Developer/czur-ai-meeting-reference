# Backend reference implementation

This module will contain the protocol-facing meeting orchestrator and its
replaceable model, storage, identity, and notification ports. Keep domain
objects independent of HTTP frameworks and provider SDKs. Provider-specific
response conversion belongs in the partner's driver implementation.

The module currently provides the project boundary only; production services
and credentials are intentionally out of scope for this public repository.
