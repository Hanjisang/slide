package mdsx

import "testing"

func TestMDSXTilePositionUsesColsForXAndRowsForY(t *testing.T) {
	level := rc{StartKey: 10, RowsNum: 3, ColsNum: 4}
	position, err := mdsxTilePosition(level, 3, 2)
	if err != nil {
		t.Fatal(err)
	}
	if position != 21 {
		t.Fatalf("expected last tile position 21, got %d", position)
	}
	if _, err := mdsxTilePosition(level, 4, 2); err == nil {
		t.Fatal("expected x bound failure")
	}
	if _, err := mdsxTilePosition(level, 3, 3); err == nil {
		t.Fatal("expected y bound failure")
	}
}
