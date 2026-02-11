"""Bounding box extraction for text regions using PaddleOCR detection."""

import logging

from PIL import Image

logger = logging.getLogger(__name__)


class BBoxExtractor:
    """Extracts text region bounding boxes using PaddleOCR detection model.

    Uses detection-only mode (no recognition) for fast spatial layout extraction.
    The detection model is ~3MB and downloads on first use.
    """

    def __init__(self) -> None:
        """Initialize with no loaded model."""
        self._ocr = None

    def load(self) -> None:
        """Load the PaddleOCR detection model (lazy import for compatibility)."""
        from paddleocr import PaddleOCR

        logger.info("Loading PaddleOCR detection model...")
        self._ocr = PaddleOCR(
            use_angle_cls=False,
            lang="en",
            det=True,
            rec=False,
            show_log=False,
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
        import numpy as np

        img_array = np.array(image)
        result = self._ocr.ocr(img_array, cls=False, rec=False)

        boxes = []
        if not result or not result[0]:
            return boxes

        for polygon in result[0]:
            xs = [p[0] for p in polygon]
            ys = [p[1] for p in polygon]
            boxes.append(
                {
                    "x": float(min(xs)),
                    "y": float(min(ys)),
                    "width": float(max(xs) - min(xs)),
                    "height": float(max(ys) - min(ys)),
                }
            )
        return boxes
