"""The worker's spans (BACKEND.md §9.2) and the trace context carried in message headers.

Propagation is done by hand: the `traceparent` header of the incoming message becomes the parent,
and every outgoing message carries the context of the span that sent it. The provider always
exists, so the trace keeps flowing through the headers even when nothing is exported.
"""

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from datetime import datetime

from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Span, Status, StatusCode
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

TRACEPARENT_HEADER = "traceparent"
NODE_ATTRIBUTE = "pipeline.node"
STAGE_ATTRIBUTE = "pipeline.stage"
# "wait" marks time spent parked in a retry queue; the Traces screen draws it differently.
SPAN_STATUS_ATTRIBUTE = "pipeline.span.status"
# Spans that carry it are shown as the broker's, not the worker's.
MESSAGING_SYSTEM_ATTRIBUTE = "messaging.system"

_TRACER_NAME = "fiscal-worker"
_NANOSECONDS = 1_000_000_000
_propagator = TraceContextTextMapPropagator()
_provider = TracerProvider()


def configure(service_name: str, otlp_endpoint: str | None) -> TracerProvider:
    """Exports to an OTLP/HTTP endpoint (Jaeger: http://localhost:4318) when one is given."""
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    if otlp_endpoint:
        exporter = OTLPSpanExporter(endpoint=f"{otlp_endpoint.rstrip('/')}/v1/traces")
        provider.add_span_processor(BatchSpanProcessor(exporter))
    use(provider)
    return provider


def use(provider: TracerProvider) -> None:
    global _provider
    _provider = provider


def extract(headers: Mapping[str, str]) -> Context:
    """The context the sender was in; empty when the message has no `traceparent`."""
    return _propagator.extract(dict(headers))


def current_traceparent() -> str | None:
    """Header value for a message sent from the current span."""
    carrier: dict[str, str] = {}
    _propagator.inject(carrier)
    return carrier.get(TRACEPARENT_HEADER)


@contextmanager
def span(
    name: str, node: str, stage: str, *, parent: Context | None = None, messaging: str | None = None
) -> Iterator[Span]:
    """A span that is current while the block runs. An exception leaving the block marks it failed."""
    attributes = {NODE_ATTRIBUTE: node, STAGE_ATTRIBUTE: stage}
    if messaging is not None:
        attributes[MESSAGING_SYSTEM_ATTRIBUTE] = messaging
    tracer = _provider.get_tracer(_TRACER_NAME)
    with tracer.start_as_current_span(name, context=parent, attributes=attributes) as current:
        yield current


def fail(current: Span, description: str) -> None:
    """Marks a span as failed when the failure is reported by a message, not by an exception."""
    current.set_status(Status(StatusCode.ERROR, description))


def record_wait(name: str, stage: str, started_at: datetime, parent: Context, messaging: str) -> None:
    """A span for time already spent waiting: it starts at `started_at` and ends now."""
    tracer = _provider.get_tracer(_TRACER_NAME)
    waited = tracer.start_span(
        name,
        context=parent,
        start_time=int(started_at.timestamp() * _NANOSECONDS),
        attributes={
            NODE_ATTRIBUTE: "retry",
            STAGE_ATTRIBUTE: stage,
            SPAN_STATUS_ATTRIBUTE: "wait",
            MESSAGING_SYSTEM_ATTRIBUTE: messaging,
        },
    )
    waited.end()


def current_span() -> Span:
    return trace.get_current_span()
