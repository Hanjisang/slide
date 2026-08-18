package com.medreport.slide;

import com.medreport.slide.file.SlideFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Compatibility facade for v0.1 callers. New code uses the focused services directly. */
@Service
@Deprecated
public class SlideStorageService {
    private final SlideService slides;
    private final SlideFileService files;

    public SlideStorageService(SlideService slides, SlideFileService files) {
        this.slides = slides;
        this.files = files;
    }

    public long upload(long caseId, String slideNo, MultipartFile file) { return slides.upload(caseId, slideNo, file); }
    public Map<String, Object> analyze(long id) { return slides.analyze(id); }
    public byte[] tile(long id, int level, int x, int y) { return slides.tile(id, level, x, y); }
    public byte[] thumbnail(long id) { return slides.thumbnail(id); }
    public Map<String, Object> find(long id) { return files.find(id); }
}
