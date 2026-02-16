from unittest.mock import MagicMock

import numpy as np
import pytest
from PIL import Image

from app.services.bbox_extractor import BBoxExtractor


def _make_predict_result(polygons):
    """Build mock predict() output matching PaddleOCR 3.x TextDetection format.

    Args:
        polygons: List of 4-point polygon coordinate lists, e.g.
            [[[10, 20], [100, 20], [100, 50], [10, 50]]].

    Returns:
        List with one result dict containing dt_polys as numpy arrays.
    """
    if not polygons:
        return [{"dt_polys": np.array([]), "dt_scores": np.array([])}]
    dt_polys = [np.array(p, dtype=np.int16) for p in polygons]
    dt_scores = [0.95] * len(polygons)
    return [{"dt_polys": dt_polys, "dt_scores": dt_scores}]


@pytest.fixture
def extractor():
    service = BBoxExtractor()
    service._detector = MagicMock()
    return service


class TestBBoxExtractor:
    def test_detect_boxes_returns_rectangles(self, extractor):
        # PaddleOCR returns 4-point polygons; we convert to axis-aligned rects
        extractor._detector.predict.return_value = _make_predict_result(
            [
                [[10, 20], [100, 20], [100, 50], [10, 50]],
                [[5, 60], [200, 60], [200, 90], [5, 90]],
            ]
        )
        img = Image.new("RGB", (300, 200))
        boxes = extractor.detect_boxes(img)

        assert len(boxes) == 2
        assert boxes[0] == {"x": 10.0, "y": 20.0, "width": 90.0, "height": 30.0}
        assert boxes[1] == {"x": 5.0, "y": 60.0, "width": 195.0, "height": 30.0}

    def test_detect_boxes_empty_result(self, extractor):
        extractor._detector.predict.return_value = _make_predict_result([])
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_detect_boxes_none_dt_polys(self, extractor):
        extractor._detector.predict.return_value = [{"dt_polys": None, "dt_scores": None}]
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_detect_boxes_empty_output(self, extractor):
        extractor._detector.predict.return_value = []
        img = Image.new("RGB", (100, 100))
        boxes = extractor.detect_boxes(img)
        assert boxes == []

    def test_load_initializes_text_detection(self):
        mock_text_det_cls = MagicMock()
        mock_paddleocr = MagicMock()
        mock_paddleocr.TextDetection = mock_text_det_cls

        import sys

        sys.modules["paddleocr"] = mock_paddleocr
        try:
            extractor = BBoxExtractor()
            extractor.load(device="cuda")
            mock_text_det_cls.assert_called_once_with(
                model_name="PP-OCRv5_server_det", device="gpu"
            )
            assert extractor._detector is not None
        finally:
            del sys.modules["paddleocr"]

    def test_load_cpu_device_mapping(self):
        mock_text_det_cls = MagicMock()
        mock_paddleocr = MagicMock()
        mock_paddleocr.TextDetection = mock_text_det_cls

        import sys

        sys.modules["paddleocr"] = mock_paddleocr
        try:
            extractor = BBoxExtractor()
            extractor.load(device="cpu")
            mock_text_det_cls.assert_called_once_with(
                model_name="PP-OCRv5_server_det", device="cpu"
            )
        finally:
            del sys.modules["paddleocr"]

    def test_load_unknown_device_passthrough(self):
        mock_text_det_cls = MagicMock()
        mock_paddleocr = MagicMock()
        mock_paddleocr.TextDetection = mock_text_det_cls

        import sys

        sys.modules["paddleocr"] = mock_paddleocr
        try:
            extractor = BBoxExtractor()
            extractor.load(device="npu")
            mock_text_det_cls.assert_called_once_with(
                model_name="PP-OCRv5_server_det", device="npu"
            )
        finally:
            del sys.modules["paddleocr"]
