"""Zero-shot document classification using a HuggingFace NLI model."""

import logging

logger = logging.getLogger(__name__)

MAX_TEXT_CHARS = 10_000


class ClassifierService:
    """Wraps a HuggingFace zero-shot classification pipeline.

    The model is loaded lazily via ``load()`` to keep transformers/torch
    imports out of module scope (required for WSL2 compatibility).
    """

    def __init__(self) -> None:
        """Initialize with no loaded pipeline."""
        self._pipeline = None

    def load(self, model_name: str, device: str, torch_dtype: str) -> None:
        """Load the classification model onto the specified device.

        Args:
            model_name: HuggingFace model ID (e.g. ``DeBERTa-v3-large-mnli``).
            device: PyTorch device string (``cuda`` or ``cpu``).
            torch_dtype: PyTorch dtype name (e.g. ``float16``).
        """
        import torch
        from transformers import pipeline as hf_pipeline

        dtype = getattr(torch, torch_dtype, torch.float16)
        logger.info("Loading classifier model: %s (device=%s, dtype=%s)", model_name, device, dtype)
        self._pipeline = hf_pipeline(
            "zero-shot-classification",
            model=model_name,
            device=device,
            torch_dtype=dtype,
        )
        logger.info("Classifier model loaded.")

    def classify(self, text: str, labels: list[str]) -> tuple[str, float]:
        """Classify text against candidate labels.

        Args:
            text: Document text to classify (truncated to 10 000 chars).
            labels: Candidate label strings for zero-shot classification.

        Returns:
            Tuple of (top_label, confidence_score). Returns ("unknown", 0.0)
            if text is empty or whitespace-only.
        """
        if not text or not text.strip():
            return ("unknown", 0.0)

        trimmed = text[:MAX_TEXT_CHARS]
        result = self._pipeline(trimmed, candidate_labels=labels, multi_label=False)
        top_label = result["labels"][0]
        top_score = float(result["scores"][0])
        return (top_label, top_score)
