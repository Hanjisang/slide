from __future__ import annotations

import io
import os
from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from minio import Minio
from pydantic import BaseModel

from .adapters import ADAPTERS, GO_PARSER, VENDOR_GO_PARSER, detect_format, find_adapter

app = FastAPI(title="Medical Slide Worker", version="0.1.0")

cache_dir = Path(os.getenv("SLIDE_CACHE_DIR", "/data/slides"))
cache_dir.mkdir(parents=True, exist_ok=True)
minio_endpoint = os.getenv("MINIO_ENDPOINT", "minio:9000").replace("http://", "").replace("https://", "")
minio_client = Minio(
    minio_endpoint,
    access_key=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
    secret_key=os.getenv("MINIO_SECRET_KEY", "minioadmin123"),
    secure=os.getenv("MINIO_SECURE", "false").lower() == "true",
)


class AnalyzeRequest(BaseModel):
    bucket: str
    objectKey: str
    fileName: str


def local_path(slide_id: int, file_name: str) -> Path:
    slide_dir = cache_dir / str(slide_id)
    slide_dir.mkdir(parents=True, exist_ok=True)
    safe_name = Path(file_name).name
    return slide_dir / safe_name


def require_local(slide_id: int) -> Path:
    slide_dir = cache_dir / str(slide_id)
    files = list(slide_dir.glob("*")) if slide_dir.exists() else []
    if not files:
        raise HTTPException(status_code=404, detail="SLIDE_NOT_CACHED")
    return files[0]


@app.get("/health")
def health():
    return {
        "status": "UP",
        "adapterCount": len(ADAPTERS),
        "goParser": GO_PARSER.health(),
        "vendorGoParser": VENDOR_GO_PARSER.health(),
    }


@app.get("/api/adapters")
def adapters():
    return [adapter.capability() for adapter in ADAPTERS]


@app.post("/api/slides/{slide_id}/analyze")
def analyze(slide_id: int, request: AnalyzeRequest):
    path = local_path(slide_id, request.fileName)
    minio_client.fget_object(request.bucket, request.objectKey, str(path))
    adapter = find_adapter(str(path))
    if adapter is None:
        return {"status": "UNSUPPORTED_FORMAT", "format": detect_format(str(path)), "sdkStatus": "SDK_REQUIRED"}
    if not adapter.is_operational():
        return {"status": "SDK_NOT_AVAILABLE", "format": detect_format(str(path)), "adapterType": adapter.name, "sdkStatus": adapter.sdk_status}
    try:
        metadata = adapter.get_metadata(str(path))
        if metadata.get("status") != "READY":
            return metadata
        return {"status": "READY", "format": detect_format(str(path)), **metadata}
    except Exception as exc:
        return {"status": "FAILED", "format": detect_format(str(path)), "adapterType": adapter.name, "sdkStatus": adapter.sdk_status, "error": str(exc)}


@app.get("/api/slides/{slide_id}/tiles/{level}/{x}/{y}")
def tile(slide_id: int, level: int, x: int, y: int, tile_size: int = 256):
    path = require_local(slide_id)
    adapter = find_adapter(str(path))
    if adapter is None or not adapter.is_operational():
        raise HTTPException(status_code=422, detail="SDK_NOT_AVAILABLE")
    try:
        image = adapter.get_tile(str(path), level, x, y, tile_size)
        output = io.BytesIO()
        image.save(output, format="JPEG", quality=88)
        return Response(output.getvalue(), media_type="image/jpeg", headers={"Cache-Control": "public, max-age=86400"})
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/slides/{slide_id}/thumbnail")
def thumbnail(slide_id: int):
    path = require_local(slide_id)
    adapter = find_adapter(str(path))
    if adapter is None or not adapter.is_operational():
        raise HTTPException(status_code=422, detail="SDK_NOT_AVAILABLE")
    image = adapter.get_thumbnail(str(path))
    output = io.BytesIO()
    image.save(output, format="JPEG", quality=88)
    return Response(output.getvalue(), media_type="image/jpeg", headers={"Cache-Control": "public, max-age=86400"})
