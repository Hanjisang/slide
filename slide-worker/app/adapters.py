from __future__ import annotations

from abc import ABC, abstractmethod
import io
import os
from pathlib import Path
import time
from typing import Any

import httpx
import openslide
from PIL import Image


class SlideAdapter(ABC):
    name = "BASE"
    sdk_status = "SDK_REQUIRED"

    @abstractmethod
    def supports(self, file_path: str) -> bool:
        raise NotImplementedError

    def is_operational(self) -> bool:
        return self.sdk_status == "AVAILABLE"

    def capability(self) -> dict[str, Any]:
        return {
            "adapterType": self.name,
            "format": self.name,
            "engine": self.name,
            "sdkStatus": self.sdk_status,
            "extensions": sorted(self.extensions),
            "tested": self.sdk_status == "AVAILABLE",
            "missingDependency": "" if self.sdk_status == "AVAILABLE" else "vendor SDK",
        }

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

    def capability(self) -> dict[str, Any]:
        result = super().capability()
        result.update({"format": "SVS", "engine": "OPENSLIDE", "tested": True})
        return result


class GoParserClient:
    def __init__(self) -> None:
        self.base_url = os.getenv("GO_PARSER_URL", "http://go-parser:8100").rstrip("/")
        self._formats: dict[str, dict[str, Any]] = {}
        self._loaded_at = 0.0

    def formats(self, refresh: bool = False) -> dict[str, dict[str, Any]]:
        if not refresh and self._loaded_at > 0 and time.monotonic() - self._loaded_at < 30:
            return self._formats
        try:
            response = httpx.get(f"{self.base_url}/api/formats", timeout=5.0)
            response.raise_for_status()
            self._formats = {item["format"]: item for item in response.json()}
            self._loaded_at = time.monotonic()
        except (httpx.HTTPError, ValueError, KeyError):
            self._formats = {}
            self._loaded_at = time.monotonic()
        return self._formats

    def health(self) -> dict[str, Any]:
        try:
            response = httpx.get(f"{self.base_url}/health", timeout=5.0)
            response.raise_for_status()
            return response.json()
        except (httpx.HTTPError, ValueError):
            return {"status": "DOWN", "parserCount": 0, "cgo": False}

    def analyze(self, slide_id: int) -> dict[str, Any]:
        response = httpx.post(f"{self.base_url}/api/slides/{slide_id}/analyze", timeout=30.0)
        response.raise_for_status()
        return response.json()

    def image(self, slide_id: int, path: str, timeout: float) -> Image.Image:
        response = httpx.get(f"{self.base_url}/api/slides/{slide_id}/{path}", timeout=timeout)
        response.raise_for_status()
        return Image.open(io.BytesIO(response.content)).convert("RGB")


GO_PARSER = GoParserClient()


class GoParserAdapter(SlideAdapter):
    def __init__(self, name: str, extension: str):
        self.name = name
        self.extensions = {extension}

    @property
    def sdk_status(self) -> str:
        capability = GO_PARSER.formats().get(self.name)
        return capability.get("status", "PARSER_UNAVAILABLE") if capability else "PARSER_UNAVAILABLE"

    def capability(self) -> dict[str, Any]:
        capability = GO_PARSER.formats().get(self.name)
        if not capability:
            return {
                "adapterType": self.name, "format": self.name, "engine": "GO_PARSER",
                "sdkStatus": "PARSER_UNAVAILABLE", "extensions": sorted(self.extensions),
                "tested": False, "missingDependency": "Go Parser service",
            }
        return {
            "adapterType": self.name, "format": self.name, "engine": capability.get("engine", "GO_PARSER"),
            "sdkStatus": capability["status"], "extensions": capability.get("extensions", sorted(self.extensions)),
            "tested": capability.get("tested", False), "missingDependency": capability.get("missingDependency", ""),
        }

    def supports(self, file_path: str) -> bool:
        return Path(file_path).suffix.lower() in self.extensions

    def is_operational(self) -> bool:
        capability = GO_PARSER.formats().get(self.name)
        return bool(capability and capability.get("build"))

    @staticmethod
    def slide_id(file_path: str) -> int:
        try:
            slide_id = int(Path(file_path).resolve().parent.name)
        except ValueError as exc:
            raise RuntimeError("INVALID_SLIDE_CACHE_PATH") from exc
        if slide_id <= 0:
            raise RuntimeError("INVALID_SLIDE_CACHE_PATH")
        return slide_id

    def get_metadata(self, file_path: str) -> dict[str, Any]:
        return GO_PARSER.analyze(self.slide_id(file_path))

    def get_thumbnail(self, file_path: str, max_size: int = 512) -> Image.Image:
        return GO_PARSER.image(self.slide_id(file_path), "thumbnail", 30.0)

    def get_tile(self, file_path: str, level: int, x: int, y: int, tile_size: int = 256) -> Image.Image:
        if tile_size != 256:
            raise ValueError("GO_PARSER_TILE_SIZE_MUST_BE_256")
        return GO_PARSER.image(self.slide_id(file_path), f"tiles/{level}/{x}/{y}", 15.0)


class VendorAdapter(SlideAdapter):
    def __init__(self, name: str, extensions: set[str]):
        self.name = name
        self.extensions = extensions

    def supports(self, file_path: str) -> bool:
        return Path(file_path).suffix.lower() in self.extensions


ADAPTERS: list[SlideAdapter] = [
    OpenSlideAdapter(),
    GoParserAdapter("KFB", ".kfb"),
    GoParserAdapter("SDPC", ".sdpc"),
    GoParserAdapter("TRON", ".tron"),
    GoParserAdapter("MDSX", ".mdsx"),
    GoParserAdapter("TMAP", ".tmap"),
    GoParserAdapter("DMETRIX", ".dmetrix"),
    GoParserAdapter("FENLAN", ".fenlan"),
    GoParserAdapter("ZYP", ".zyp"),
    GoParserAdapter("HWP", ".hwp"),
    GoParserAdapter("CSP", ".csp"),
]


def find_adapter(file_path: str) -> SlideAdapter | None:
    return next((adapter for adapter in ADAPTERS if adapter.supports(file_path)), None)


def detect_format(file_path: str) -> str:
    adapter = find_adapter(file_path)
    if isinstance(adapter, OpenSlideAdapter):
        return "SVS"
    return adapter.name if adapter else Path(file_path).suffix.lstrip(".").upper() or "UNKNOWN"
