from unittest.mock import MagicMock, patch

from PIL import Image

from app.services.ocr import OCRService


def _make_mock_tensor(shape: tuple[int, ...]):
    """Create a MagicMock that behaves like a tensor for indexing and .shape."""
    tensor = MagicMock()
    tensor.shape = shape
    # Support .to() returning self
    tensor.to.return_value = tensor
    # Support [0][input_len:] slicing for generated_ids
    tensor.__getitem__ = MagicMock(return_value=MagicMock())
    return tensor


class TestOCRService:
    def _setup_ocr(self, mock_ocr: OCRService, decoded_text: str) -> None:
        input_ids = _make_mock_tensor((1, 10))
        mock_ocr._processor.return_value = {"input_ids": input_ids}
        mock_ocr._model.device = "cpu"
        mock_ocr._model.generate.return_value = _make_mock_tensor((1, 20))
        mock_ocr._processor.tokenizer.decode.return_value = decoded_text

    def test_extract_text_returns_decoded_output(self, mock_ocr):
        self._setup_ocr(mock_ocr, "Hello World")
        img = Image.new("RGB", (100, 100))
        result = mock_ocr.extract_text(img)
        assert result == "Hello World"

    def test_extract_text_strips_whitespace(self, mock_ocr):
        self._setup_ocr(mock_ocr, "  some text  \n")
        img = Image.new("RGB", (100, 100))
        result = mock_ocr.extract_text(img)
        assert result == "some text"

    def test_extract_text_from_images_concatenates(self, mock_ocr):
        self._setup_ocr(mock_ocr, "page text")
        images = [Image.new("RGB", (100, 100)) for _ in range(3)]
        result = mock_ocr.extract_text_from_images(images)
        assert result == "page text\n\npage text\n\npage text"

    def test_extract_text_from_images_skips_empty(self, mock_ocr):
        self._setup_ocr(mock_ocr, "")
        images = [Image.new("RGB", (100, 100))]
        result = mock_ocr.extract_text_from_images(images)
        assert result == ""

    def test_extract_text_calls_generate_with_no_sampling(self, mock_ocr):
        self._setup_ocr(mock_ocr, "text")
        img = Image.new("RGB", (100, 100))
        mock_ocr.extract_text(img)
        _, kwargs = mock_ocr._model.generate.call_args
        assert kwargs["do_sample"] is False
        assert kwargs["max_new_tokens"] == 4096
