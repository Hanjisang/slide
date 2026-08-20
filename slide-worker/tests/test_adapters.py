from pathlib import Path

import httpx

from app.adapters import GoParserAdapter, GoParserClient, OpenSlideAdapter


def test_svs_remains_on_openslide():
    adapter = OpenSlideAdapter()
    assert adapter.supports("sample.svs")
    assert adapter.name == "OPENSLIDE"
    assert adapter.sdk_status == "AVAILABLE"


def test_go_parser_slide_id_is_derived_from_fixed_cache_layout(tmp_path: Path):
    slide = tmp_path / "42" / "sample.kfb"
    slide.parent.mkdir()
    slide.write_bytes(b"test")
    assert GoParserAdapter.slide_id(str(slide)) == 42


def test_go_parser_rejects_non_numeric_cache_directory(tmp_path: Path):
    slide = tmp_path / "outside" / "sample.kfb"
    slide.parent.mkdir()
    slide.write_bytes(b"test")
    try:
        GoParserAdapter.slide_id(str(slide))
    except RuntimeError as exc:
        assert str(exc) == "INVALID_SLIDE_CACHE_PATH"
    else:
        raise AssertionError("invalid cache path was accepted")


def test_go_parser_caches_unavailable_format_result(monkeypatch):
    client = GoParserClient()
    calls = 0

    def unavailable(*_args, **_kwargs):
        nonlocal calls
        calls += 1
        raise httpx.ConnectError("down")

    monkeypatch.setattr(httpx, "get", unavailable)
    assert client.formats() == {}
    assert client.formats() == {}
    assert calls == 1
