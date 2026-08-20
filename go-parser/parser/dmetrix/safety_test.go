package dmetrix

import "testing"

func TestCheckedTileCountRejectsOverflowAndAllowsRealShape(t *testing.T) {
	if got, err := checkedTileCount(199, 223); err != nil || got != 44800 {
		t.Fatalf("real DMETRIX shape: got %d, %v", got, err)
	}
	if _, err := checkedTileCount(^uint32(0), ^uint32(0)); err == nil {
		t.Fatal("expected oversized tile count to be rejected")
	}
}

func TestCheckedByteCountRejectsNegativeValues(t *testing.T) {
	if _, ok := checkedByteCount(-1, 22); ok {
		t.Fatal("negative count accepted")
	}
}
