package main

import (
	"log/slog"
	"net/http"
	"os"
	"time"

	"imageparser/internal/service"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	root := os.Getenv("SLIDE_CACHE_DIR")
	if root == "" {
		root = "/data/slides"
	}
	server, err := service.NewServer(root, logger)
	if err != nil {
		logger.Error("initialize parser service", "error", err)
		os.Exit(1)
	}
	httpServer := &http.Server{
		Addr:              ":8100",
		Handler:           server.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	logger.Info("go parser listening", "address", httpServer.Addr, "cacheRoot", root)
	if err := httpServer.ListenAndServe(); err != nil {
		logger.Error("go parser stopped", "error", err)
		os.Exit(1)
	}
}
