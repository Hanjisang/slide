package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"imageparser/internal/verification"
)

func main() {
	// Several migrated vendor parsers log source paths through the standard
	// logger. Real-sample verification must never emit patient filenames.
	log.SetOutput(io.Discard)

	var filePath, directory, expectedFormat, alias, outputPath, concurrency string
	options := verification.DefaultOptions()
	all := false
	flag.StringVar(&filePath, "file", "", "single real slide file")
	flag.StringVar(&directory, "dir", "", "directory recursively containing real slides")
	flag.StringVar(&expectedFormat, "format", "", "expected format for --file")
	flag.StringVar(&alias, "alias", "", "anonymous sample alias for --file")
	flag.BoolVar(&all, "all", false, "verify all target-format files below --dir")
	flag.IntVar(&options.RandomTiles, "random", options.RandomTiles, "random tile count per sample")
	flag.IntVar(&options.Performance, "performance", options.Performance, "timed tile count per sample")
	flag.IntVar(&options.Stability, "stability", options.Stability, "continuous tile count per sample")
	flag.StringVar(&concurrency, "concurrency", "5,10", "comma-separated concurrent worker counts")
	flag.Int64Var(&options.Seed, "seed", options.Seed, "deterministic random seed")
	flag.StringVar(&outputPath, "json-out", "", "optional JSON report path")
	flag.Parse()

	items, err := inputs(filePath, directory, expectedFormat, alias, all)
	if err != nil {
		fatal(err)
	}
	options.Concurrency, err = parseConcurrency(concurrency)
	if err != nil {
		fatal(err)
	}
	report := verification.Verify(items, options)
	if outputPath != "" {
		output, createErr := os.Create(outputPath)
		if createErr != nil {
			fatal(createErr)
		}
		if encodeErr := verification.Encode(report, output); encodeErr != nil {
			_ = output.Close()
			fatal(encodeErr)
		}
		if closeErr := output.Close(); closeErr != nil {
			fatal(closeErr)
		}
	}
	if err := verification.Encode(report, os.Stdout); err != nil {
		fatal(err)
	}
}

func inputs(filePath, directory, expectedFormat, alias string, all bool) ([]verification.InventoryItem, error) {
	if (filePath == "") == (directory == "") {
		return nil, errors.New("exactly one of --file or --dir is required")
	}
	if directory != "" {
		if !all {
			return nil, errors.New("--dir requires --all")
		}
		return verification.Inventory(directory)
	}
	absolute, err := filepath.Abs(filePath)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(absolute)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() <= 0 {
		return nil, errors.New("slide must be a non-empty regular file")
	}
	format := strings.ToUpper(strings.TrimSpace(expectedFormat))
	if format == "" {
		format = strings.ToUpper(strings.TrimPrefix(filepath.Ext(absolute), "."))
	}
	if alias == "" {
		alias = format + "_SAMPLE_01"
	}
	return []verification.InventoryItem{{Alias: alias, Format: format, Size: info.Size(), Extension: strings.ToLower(filepath.Ext(absolute)), Path: absolute}}, nil
}

func parseConcurrency(value string) ([]int, error) {
	if strings.TrimSpace(value) == "" {
		return nil, nil
	}
	var result []int
	for _, field := range strings.Split(value, ",") {
		workers, err := strconv.Atoi(strings.TrimSpace(field))
		if err != nil || workers <= 0 || workers > 100 {
			return nil, fmt.Errorf("invalid concurrency value %q", field)
		}
		result = append(result, workers)
	}
	return result, nil
}

func fatal(err error) {
	_, _ = fmt.Fprintln(os.Stderr, "verify-slide:", err)
	os.Exit(2)
}
