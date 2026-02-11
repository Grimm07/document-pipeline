"""Application settings loaded from environment variables.

All settings use the ``ML_`` prefix (e.g. ``ML_DEVICE=cpu``).
Defaults target a CUDA-capable host with HuggingFace model cache at ``/models``.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """ML service configuration backed by environment variables.

    Attributes:
        classifier_model: HuggingFace model ID for zero-shot classification.
        ocr_model: HuggingFace model ID for OCR text extraction.
        candidate_labels: Comma-separated document type labels for classification.
        device: PyTorch device string (``cuda`` or ``cpu``).
        torch_dtype: PyTorch dtype name (e.g. ``float16``, ``float32``).
        ocr_max_pdf_pages: Maximum number of PDF pages to process for OCR.
        hf_home: Local directory for the HuggingFace model cache.
    """

    model_config = {"env_prefix": "ML_"}

    classifier_model: str = "MoritzLaurer/DeBERTa-v3-large-mnli-fever-anli-ling-wanli"
    ocr_model: str = "stepfun-ai/GOT-OCR-2.0-hf"
    candidate_labels: str = (
        "invoice,contract,report,letter,receipt,form,memo,resume,specification,manual"
    )
    device: str = "cuda"
    torch_dtype: str = "float16"
    ocr_max_pdf_pages: int = 10
    hf_home: str = "/models"

    def get_candidate_labels(self) -> list[str]:
        """Parse the comma-separated candidate_labels string into a list."""
        return [label.strip() for label in self.candidate_labels.split(",")]


settings = Settings()
