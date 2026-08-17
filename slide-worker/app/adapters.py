from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

import openslide
from PIL import Image


class SlideAdapter(ABC):
    name = "BASE"
    sdk_status = "SDK_REQUIRED"

    @abstractmethod
    def supports(self, file_path: str) -> bool:
        raise NotImplementedError

    def get_metadata(self, file_path: str) -> dict[str, Any]:
        raise RuntimeError("SDK_NOT_AVAILABLE")

    def get_levels(self, file_path: str) -> list[dict[str, Any]]:
        raise RuntimeError("SDK_NOT_AVAILABLE")

    def get_thumbnail(self, file_path: str, max_size: int = 512) -> Image.Image:
        raise RuntimeError("SDK_NOT_AVAILABLE")

    def get_tile(self, file_path: str, level: int, x: int, y: int, tile_size: int = 256) -> Image.Image:
        raise RuntimeError("SDK_NOT_AVAILABLE")


class OpenSlideAdapter(SlideAdapter):
    name = "OPENSLIDE"
    sdk_status = "AVAILABLE"
    extensions = {".svs"}

    def supports(self, file_path: str) -> bool:
        return Path(file_path).suffix.lower() in self.extensions

    def get_metadata(self, file_path: str) -> dict[str, Any]:
        with openslide.OpenSlide(file_path) as slide:
            return {
                "adapterType": self.name,
                "sdkStatus": self.sdk_status,
                "width": slide.dimensions[0],
                "height": slide.dimensions[1],
                "levelCount": slide.level_count,
                "levels": [
                    {"level": index, "width": size[0], "height": size[1], "downsample": slide.level_downsamples[index]}
                    for index, size in enumerate(slide.level_dimensions)
                ],
                "properties": {key: value for key, value in slide.properties.items() if len(str(value)) < 500},
            }

    def get_levels(self, file_path: str) -> list[dict[str, Any]]:
        return self.get_metadata(file_path)["levels"]

    def get_thumbnail(self, file_path: str, max_size: int = 512) -> Image.Image:
        with openslide.OpenSlide(file_path) as slide:
            return slide.get_thumbnail((max_size, max_size)).convert("RGB")

    def get_tile(self, file_path: str, level: int, x: int, y: int, tile_size: int = 256) -> Image.Image:
        with openslide.OpenSlide(file_path) as slide:
            if level < 0 or level >= slide.level_count:
                raise ValueError("INVALID_LEVEL")
            # The browser uses low-to-high levels; OpenSlide indexes full resolution first.
            openslide_level = slide.level_count - 1 - level
            downsample = slide.level_downsamples[openslide_level]
            location = (int(x * tile_size * downsample), int(y * tile_size * downsample))
            return slide.read_region(location, openslide_level, (tile_size, tile_size)).convert("RGB")


class VendorAdapter(SlideAdapter):
    def __init__(self, name: str, extensions: set[str]):
        self.name = name
        self.extensions = extensions

    def supports(self, file_path: str) -> bool:
        return Path(file_path).suffix.lower() in self.extensions


ADAPTERS: list[SlideAdapter] = [
    OpenSlideAdapter(),
    VendorAdapter("KFB", {".kfb"}),
    VendorAdapter("SDPC", {".sdpc"}),
    VendorAdapter("TRON", {".tron"}),
    VendorAdapter("MDSX", {".mdsx"}),
    VendorAdapter("TMAP", {".tmap"}),
    VendorAdapter("DMETRIX", {".dmetrix"}),
    VendorAdapter("FENLAN", {".fenlan"}),
    VendorAdapter("ZYP", {".zyp"}),
    VendorAdapter("HWP", {".hwp"}),
    VendorAdapter("CSP", {".csp"}),
]


def find_adapter(file_path: str) -> SlideAdapter | None:
    return next((adapter for adapter in ADAPTERS if adapter.supports(file_path)), None)


def detect_format(file_path: str) -> str:
    adapter = find_adapter(file_path)
    if isinstance(adapter, OpenSlideAdapter):
        return "SVS"
    return adapter.name if adapter else Path(file_path).suffix.lstrip(".").upper() or "UNKNOWN"

