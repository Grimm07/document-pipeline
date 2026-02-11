"""OCR text extraction using the GOT-OCR2 vision-language model."""

import logging

from PIL import Image

from app.metrics import MODEL_LOAD_DURATION, OCR_INFERENCE_DURATION

logger = logging.getLogger(__name__)


class OCRService:
    """Extracts text from images using a HuggingFace vision-language model.

    The model is loaded lazily via ``load()`` to keep transformers/torch
    imports out of module scope (required for WSL2 compatibility).
    """

    def __init__(self) -> None:
        """Initialize with no loaded model or processor."""
        self._model = None
        self._processor = None

    def load(self, model_name: str, device: str, torch_dtype: str) -> None:
        """Load the OCR model and processor onto the specified device.

        Args:
            model_name: HuggingFace model ID (e.g. ``GOT-OCR-2.0-hf``).
            device: PyTorch device string (``cuda`` or ``cpu``).
            torch_dtype: PyTorch dtype name (e.g. ``float16``).
        """
        with MODEL_LOAD_DURATION.labels(model_name=model_name).time():
            import torch
            from transformers import AutoModelForImageTextToText, AutoProcessor

            dtype = getattr(torch, torch_dtype, torch.float16)
            logger.info("Loading OCR model: %s (device=%s, dtype=%s)", model_name, device, dtype)
            self._processor = AutoProcessor.from_pretrained(model_name, trust_remote_code=True)
            self._model = AutoModelForImageTextToText.from_pretrained(
                model_name,
                torch_dtype=dtype,
                device_map=device,
                trust_remote_code=True,
            )
            logger.info("OCR model loaded.")

    def extract_text(self, image: Image.Image) -> str:
        """Extract text from a single image using the OCR model.

        Args:
            image: RGB PIL image to process.

        Returns:
            Extracted text, stripped of leading/trailing whitespace.
        """
        with OCR_INFERENCE_DURATION.time():
            inputs = self._processor(images=image, return_tensors="pt")
            inputs = {k: v.to(self._model.device) for k, v in inputs.items()}
            generated_ids = self._model.generate(
                **inputs,
                do_sample=False,
                max_new_tokens=4096,
                stop_strings=["<|im_end|>"],
                tokenizer=self._processor.tokenizer,
            )
            # Decode only the newly generated tokens (skip the input prompt tokens)
            input_len = inputs["input_ids"].shape[1] if "input_ids" in inputs else 0
            decoded = self._processor.tokenizer.decode(
                generated_ids[0][input_len:], skip_special_tokens=True
            )
        return decoded.strip()

    def extract_text_from_images(self, images: list[Image.Image]) -> str:
        """Extract and concatenate text from multiple images.

        Args:
            images: List of RGB PIL images (e.g. PDF pages).

        Returns:
            Concatenated text from all images, separated by double newlines.
        """
        texts = []
        for i, image in enumerate(images):
            logger.info("OCR processing page %d/%d", i + 1, len(images))
            text = self.extract_text(image)
            if text:
                texts.append(text)
        return "\n\n".join(texts)
