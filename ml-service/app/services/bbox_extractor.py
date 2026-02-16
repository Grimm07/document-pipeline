"""Bounding box extraction for text regions using PaddleOCR detection."""

import logging

from PIL import Image

from app.metrics import BBOX_DETECTION_DURATION, MODEL_LOAD_DURATION

logger = logging.getLogger(__name__)


class BBoxExtractor:
    """Extracts text region bounding boxes using PaddleOCR detection model.

    Uses PaddleOCR 3.x TextDetection for fast spatial layout extraction
    without recognition. The detection model downloads on first use.
    """

    def __init__(self) -> None:
        """Initialize with no loaded model."""
        self._detector = None

    def load(self, device: str = "cpu") -> None:
        """Load the PaddleOCR detection model (lazy import for compatibility).

        Args:
            device: Device string ("cuda" or "cpu"). Mapped to PaddleOCR's
                convention ("gpu"/"cpu") internally.
        """
        with MODEL_LOAD_DURATION.labels(model_name="paddleocr-det").time():
            from paddleocr import TextDetection

            paddle_device = {"cuda": "gpu", "cpu": "cpu"}.get(device, device)
            logger.info("Loading PaddleOCR detection model on %s...", paddle_device)
            self._detector = TextDetection(
                model_name="PP-OCRv5_server_det",
                device=paddle_device,
            )
            logger.info("PaddleOCR detection model loaded.")

    def detect_boxes(self, image: Image.Image) -> list[dict]:
        """Detect text region bounding boxes in an image.

        Returns axis-aligned rectangles derived from PaddleOCR's 4-point polygons.
        Coordinates are in pixel space relative to the input image dimensions.

        Args:
            image: RGB PIL image to analyze.

        Returns:
            List of dicts with ``x``, ``y``, ``width``, ``height`` keys.
        """
        with BBOX_DETECTION_DURATION.time():
            import numpy as np

            img_array = np.array(image)
            output = self._detector.predict(img_array)

            boxes = []
            for res in output:
                dt_polys = res.get("dt_polys")
                if dt_polys is None or len(dt_polys) == 0:
                    continue
                for polygon in dt_polys:
                    xs = polygon[:, 0].astype(float)
                    ys = polygon[:, 1].astype(float)
                    boxes.append(
                        {
                            "x": float(xs.min()),
                            "y": float(ys.min()),
                            "width": float(xs.max() - xs.min()),
                            "height": float(ys.max() - ys.min()),
                        }
                    )
            return boxes
