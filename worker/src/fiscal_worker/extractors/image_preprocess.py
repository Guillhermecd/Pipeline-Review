"""OpenCV clean-up before OCR: straighten a tilted photo and even out its lighting."""

import cv2
import numpy as np

from fiscal_worker.extractors.rasterizer import Image

# Tilts smaller than this are noise in the estimate; larger than MAX are not a tilted receipt.
MIN_SKEW_DEGREES = 0.3
MAX_SKEW_DEGREES = 20.0
WHITE = (255, 255, 255)


def preprocess(image: Image) -> Image:
    return _equalize(_deskew(image))


def estimate_skew(image: Image) -> float:
    """Tilt of the text block in degrees, from the minimum-area box around the ink."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    _, ink = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)
    points = cv2.findNonZero(ink)
    if points is None:
        return 0.0
    angle = float(cv2.minAreaRect(points)[-1])
    # minAreaRect reports (0, 90]; fold it to the nearest of the two axes.
    return angle - 90.0 if angle > 45.0 else angle


def _deskew(image: Image) -> Image:
    angle = estimate_skew(image)
    if not MIN_SKEW_DEGREES <= abs(angle) <= MAX_SKEW_DEGREES:
        return image
    height, width = image.shape[:2]
    rotation = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)
    rotated = cv2.warpAffine(
        image, rotation, (width, height), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT, borderValue=WHITE
    )
    return np.asarray(rotated, dtype=np.uint8)


def _equalize(image: Image) -> Image:
    """Local contrast on the lightness channel. Hard binarisation is left out on purpose: it
    erases thin strokes of thermal receipts and lowers the recognition score."""
    lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB)
    lightness, a_channel, b_channel = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    merged = cv2.merge((clahe.apply(lightness), a_channel, b_channel))
    return np.asarray(cv2.cvtColor(merged, cv2.COLOR_LAB2BGR), dtype=np.uint8)
