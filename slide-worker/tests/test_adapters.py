from pathlib import Path

import httpx

from app.adapters import GoParserAdapter, GoParserClient, OpenSlideAdapter


def test_svs_remains_on_openslide():
    adapter = OpenSlideAdapter()
    assert adapter.supports("sample.svs")
    assert adapter.name == "OPENSLIDE"
    assert adapter.sdk_status == "AVAILABLE"


def test_svs_metadata_uses_ready_worker_protocol(monkeypatch):
    class FakeSlide:
        dimensions = (4096, 2048)
        level_count = 2
        level_dimensions = ((4096, 2048), (1024, 512))
        level_downsamples = (1.0, 4.0)
        properties = {}

        def __enter__(self):
            return self

        def __exit__(self, *_args):
            return None

    monkeypatch.setattr("app.adapters.openslide.OpenSlide", lambda _path: FakeSlide())
    metadata = OpenSlideAdapter().get_metadata("SVS_SAMPLE_01.svs")
    assert metadata["status"] == "READY"
    assert metadata["format"] == "SVS"
    assert metadata["width"] == 4096
    assert metadata["levelCount"] == 2


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


def test_go_parser_client_uses_selected_environment(monkeypatch):
    monkeypatch.setenv("TEST_VENDOR_PARSER_URL", "http://isolated-parser:9100/")
    client = GoParserClient("TEST_VENDOR_PARSER_URL", "http://fallback:8100")
    assert client.base_url == "http://isolated-parser:9100"


def test_go_parser_adapter_uses_injected_client(tmp_path: Path):
    class FakeClient:
        def formats(self):
            return {"HWP": {"format": "HWP", "status": "TEST_DATA_REQUIRED", "build": True}}

        def analyze(self, slide_id: int):
            return {"status": "READY", "slideId": slide_id}

    slide = tmp_path / "17" / "sample.hwp"
    slide.parent.mkdir()
    slide.write_bytes(b"HW_MEDIC")
    adapter = GoParserAdapter("HWP", ".hwp", FakeClient())
    assert adapter.get_metadata(str(slide)) == {"status": "READY", "slideId": 17}


def test_go_parser_analyze_preserves_structured_parser_error(monkeypatch):
    response = httpx.Response(
        422,
        json={"status": "FAILED", "error": "HWP_UNSUPPORTED_MAGIC"},
        request=httpx.Request("POST", "http://parser/api/slides/16/analyze"),
    )
    monkeypatch.setattr(httpx, "post", lambda *_args, **_kwargs: response)
    result = GoParserClient(default_url="http://parser").analyze(16)
    assert result == {"status": "FAILED", "error": "HWP_UNSUPPORTED_MAGIC"}
