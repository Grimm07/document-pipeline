from unittest.mock import MagicMock, patch

import pytest
from PIL import Image

from app.services.bbox_extractor import BBoxExtractor


@pytest.fixture
def extractor():
    service = BBoxExtractor()
    service._ocr = MagicMock()
    return service


class TestBBoxExtractor:
    def test_detect_boxes_returns_rectangles(self, extractor):
        # PaddleOCR returns 4-point polygons; we convert to axis-aligned rects
        extractor._ocr.ocr.return_value = [
            [
                [[10.0, 20.0], [100.0, 20.0], [100.0, 50.0], [10.0, 50.0]],
                [[5.0, 60.0], [200.0, 60.0], [200.0, 90.0], [5.0, 90.0]],
            ]
        ]
        img = Image.new("RGB", (300, 200))
        boxes = extractor.detect_boxes(img)

        assert len(boxes) == 2
        assert boxes[0] == {"x": 10.0, "y": 20.0, "width": 90.0, "height": 30.0}
        assert boxes[1] == {"x": 5.0, "y": 60.0, "width": 195.0, "height": 30.0}

    def test_detect_boxes_empty_result(self, extractor):
        extractor._ocr.ocr.return_value = [None]
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_detect_boxes_no_detections(self, extractor):
        extractor._ocr.ocr.return_value = [[]]
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_detect_boxes_none_result(self, extractor):
        extractor._ocr.ocr.return_value = None
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_load_initializes_paddleocr(self):
        mock_paddle_cls = MagicMock()
        mock_paddleocr = MagicMock()
        mock_paddleocr.PaddleOCR = mock_paddle_cls

        import sys
        sys.modules["paddleocr"] = mock_paddleocr
        try:
            extractor = BBoxExtractor()
            extractor.load()
            mock_paddle_cls.assert_called_once_with(
                use_angle_cls=False, lang="en", det=True, rec=False, show_log=False
            )
            assert extractor._ocr is not None
        finally:
            del sys.modules["paddleocr"]
