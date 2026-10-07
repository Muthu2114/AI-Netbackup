package com.netbackup.service;

import com.netbackup.model.ConfigBackup;
import com.netbackup.model.Device;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spring wrapper for local configuration analysis; caches results by backup checksums. */
@Service
public class AiAssistantService {
    private final AiConfigAnalyzer analyzer;
    private final Map<String, AiConfigAnalyzer.Outcome> cache = new ConcurrentHashMap<>();

    public AiAssistantService() {
        this.analyzer = new AiConfigAnalyzer(new WekaRandomForestModel());
    }

    public boolean isEnabled() { return true; }
    public String getModel() { return analyzer.getModel(); }

    /** Returns the outcome; {@code cachedOut[0]} tells whether it came from the cache (same two checksums). */
    public AiConfigAnalyzer.Outcome analyze(Device d, ConfigBackup from, ConfigBackup to, boolean[] cachedOut) {
        String key = from.getChecksum() + ":" + to.getChecksum() + ":" + getModel();
        AiConfigAnalyzer.Outcome hit = cache.get(key);
        if (hit != null) { cachedOut[0] = true; return hit; }
        AiConfigAnalyzer.Outcome o = analyzer.analyze(d.getName(), from.getVersion(), to.getVersion(), from.getContent(), to.getContent());
        if (cache.size() > 200) cache.clear();
        cache.put(key, o);
        cachedOut[0] = false;
        return o;
    }
}
