import logging

from PIL import Image

logger = logging.getLogger(__name__)


class OCRService:
    def __init__(self) -> None:
        self._model = None
        self._processor = None

    def load(self, model_name: str, device: str, torch_dtype: str) -> None:
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
        texts = []
        for i, image in enumerate(images):
            logger.info("OCR processing page %d/%d", i + 1, len(images))
            text = self.extract_text(image)
            if text:
                texts.append(text)
        return "\n\n".join(texts)
