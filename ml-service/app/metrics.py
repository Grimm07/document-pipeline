"""Prometheus metric definitions for the ML service."""

from prometheus_client import Counter, Gauge, Histogram

CLASSIFIER_INFERENCE_DURATION = Histogram(
    "ml_classifier_inference_duration_seconds",
    "Time spent on zero-shot classification inference",
)

OCR_INFERENCE_DURATION = Histogram(
    "ml_ocr_inference_duration_seconds",
    "Time spent on OCR text extraction",
)

BBOX_DETECTION_DURATION = Histogram(
    "ml_bbox_detection_duration_seconds",
    "Time spent on bounding box detection",
)

MODELS_LOADED = Gauge(
    "ml_models_loaded",
    "Whether all ML models are loaded and ready (1=ready, 0=loading)",
)

MODEL_LOAD_DURATION = Histogram(
    "ml_model_load_duration_seconds",
    "Time to load a model",
    ["model_name"],
)

PIPELINE_REQUESTS_TOTAL = Counter(
    "ml_pipeline_requests_total",
    "Total pipeline requests",
    ["endpoint", "status"],
)

PIPELINE_DURATION = Histogram(
    "ml_pipeline_duration_seconds",
    "End-to-end pipeline request duration",
    ["endpoint"],
)
