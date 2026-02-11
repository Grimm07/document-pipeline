import logging

logger = logging.getLogger(__name__)

MAX_TEXT_CHARS = 10_000


class ClassifierService:
    def __init__(self) -> None:
        self._pipeline = None

    def load(self, model_name: str, device: str, torch_dtype: str) -> None:
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
        if not text or not text.strip():
            return ("unknown", 0.0)

        trimmed = text[:MAX_TEXT_CHARS]
        result = self._pipeline(trimmed, candidate_labels=labels, multi_label=False)
        top_label = result["labels"][0]
        top_score = float(result["scores"][0])
        return (top_label, top_score)
