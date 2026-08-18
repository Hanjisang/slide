package service

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"imageparser/internal/registry"
	"imageparser/types"
)

type cacheEntry struct {
	key        string
	parser     types.ImageParser
	capability registry.Capability
	header     types.HeaderInfo
	lastUsed   time.Time
	mu         sync.Mutex
}

type ParserCache struct {
	root     string
	ttl      time.Duration
	maxItems int
	registry *registry.Registry
	mu       sync.Mutex
	entries  map[int64]*cacheEntry
}

func NewParserCache(root string, ttl time.Duration, maxItems int, parserRegistry *registry.Registry) (*ParserCache, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	if maxItems <= 0 {
		maxItems = 128
	}
	return &ParserCache{root: filepath.Clean(abs), ttl: ttl, maxItems: maxItems, registry: parserRegistry, entries: make(map[int64]*cacheEntry)}, nil
}

func (c *ParserCache) Get(slideID int64) (*cacheEntry, error) {
	path, info, err := c.resolve(slideID)
	if err != nil {
		return nil, err
	}
	key := strings.Join([]string{path, strconv.FormatInt(info.Size(), 10), strconv.FormatInt(info.ModTime().UnixNano(), 10)}, ":")
	now := time.Now()

	c.mu.Lock()
	if existing := c.entries[slideID]; existing != nil && existing.key == key && now.Sub(existing.lastUsed) <= c.ttl {
		existing.lastUsed = now
		c.mu.Unlock()
		return existing, nil
	}
	c.mu.Unlock()

	parser, capability, err := c.registry.Open(path)
	if err != nil {
		return nil, err
	}
	header, err := parser.GetHeaderInfoFunc()
	if err != nil {
		return nil, err
	}
	created := &cacheEntry{key: key, parser: parser, capability: capability, header: header, lastUsed: now}

	c.mu.Lock()
	c.entries[slideID] = created
	c.evict(now)
	c.mu.Unlock()
	return created, nil
}

func (c *ParserCache) evict(now time.Time) {
	for id, item := range c.entries {
		if now.Sub(item.lastUsed) > c.ttl {
			delete(c.entries, id)
		}
	}
	for len(c.entries) > c.maxItems {
		var oldestID int64
		var oldest time.Time
		for id, item := range c.entries {
			if oldest.IsZero() || item.lastUsed.Before(oldest) {
				oldestID, oldest = id, item.lastUsed
			}
		}
		delete(c.entries, oldestID)
	}
}

func (c *ParserCache) resolve(slideID int64) (string, os.FileInfo, error) {
	if slideID <= 0 {
		return "", nil, errors.New("invalid slide id")
	}
	dir := filepath.Join(c.root, strconv.FormatInt(slideID, 10))
	rel, err := filepath.Rel(c.root, dir)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", nil, errors.New("slide path escapes cache root")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil, errors.New("SLIDE_NOT_CACHED")
		}
		return "", nil, err
	}
	type candidate struct {
		path string
		info os.FileInfo
	}
	files := make([]candidate, 0, len(entries))
	for _, entry := range entries {
		if entry.Type()&os.ModeSymlink != 0 || !entry.Type().IsRegular() {
			continue
		}
		info, infoErr := entry.Info()
		if infoErr != nil || info.Size() <= 0 {
			continue
		}
		files = append(files, candidate{path: filepath.Join(dir, entry.Name()), info: info})
	}
	if len(files) == 0 {
		return "", nil, errors.New("SLIDE_NOT_CACHED")
	}
	sort.Slice(files, func(i, j int) bool { return files[i].info.ModTime().After(files[j].info.ModTime()) })
	resolved, err := filepath.EvalSymlinks(files[0].path)
	if err != nil {
		return "", nil, err
	}
	rel, err = filepath.Rel(c.root, resolved)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", nil, fmt.Errorf("resolved path escapes cache root")
	}
	return resolved, files[0].info, nil
}
