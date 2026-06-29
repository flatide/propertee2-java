package com.flatide.interpreter;

import com.flatide.propertee2.host.PlatformProvider.DirEntry;
import com.flatide.propertee2.host.PlatformProvider.FileStat;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges a v1 {@link com.flatide.platform.PlatformProvider} (what TeeBox supplies) to the engine's
 * {@code com.flatide.propertee2.host.PlatformProvider} so the ENV / file-I/O builtins use the host's
 * platform. Method names line up 1:1 except v1's getEnv/readLines(int,int) and its FileInfo/FileEntry
 * record shapes, mapped here.
 */
final class PlatformAdapter implements com.flatide.propertee2.host.PlatformProvider {

    private final com.flatide.platform.PlatformProvider v1;

    PlatformAdapter(com.flatide.platform.PlatformProvider v1) { this.v1 = v1; }

    @Override public String env(String name) { return v1.getEnv(name); }
    @Override public boolean fileExists(String path) { return v1.fileExists(path); }
    @Override public void mkdir(String path) { v1.mkdir(path); }
    @Override public void writeFile(String path, String content) { v1.writeFile(path, content); }
    @Override public void appendFile(String path, String content) { v1.appendFile(path, content); }

    @Override public List<String> readLines(String path, long start, long count) {
        return v1.readLines(path, (int) start, (int) count);
    }

    @Override public FileStat fileInfo(String path) {
        com.flatide.platform.PlatformProvider.FileInfo fi = v1.fileInfo(path);
        return new FileStat(fi.type, fi.size, fi.modified);
    }

    @Override public List<DirEntry> listDir(String path) {
        List<DirEntry> out = new ArrayList<>();
        for (com.flatide.platform.PlatformProvider.FileEntry e : v1.listDir(path)) {
            out.add(new DirEntry(e.name, e.type, e.size));
        }
        return out;
    }

    @Override public void deleteFile(String path) { v1.deleteFile(path); }
}
