package fenlan

import "testing"

func TestCheckedTileCountRejectsOverflowAndAllowsRealShape(t *testing.T) {
	if got, ok := checkedTileCount(13, 12); !ok || got != 156 {
		t.Fatalf("real FENLAN shape: got %d, %v", got, ok)
	}
	if _, ok := checkedTileCount(^uint32(0), ^uint32(0)); ok {
		t.Fatal("expected oversized tile count to be rejected")
	}
}

func TestCheckedByteCountRejectsNegativeValues(t *testing.T) {
	if _, ok := checkedByteCount(-1, 22); ok {
		t.Fatal("negative count accepted")
	}
}
