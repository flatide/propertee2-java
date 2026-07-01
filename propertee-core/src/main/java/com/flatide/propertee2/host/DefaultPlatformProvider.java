package com.flatide.propertee2.host;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Default host integration backed by the real JVM environment, filesystem (NIO), and HTTP. */
public final class DefaultPlatformProvider implements PlatformProvider {

    @Override
    public String env(String name) {
        return System.getenv(name);
    }

    @Override
    public boolean fileExists(String path) {
        return Files.exists(Path.of(path));
    }

    @Override
    public void mkdir(String path) throws IOException {
        Files.createDirectories(Path.of(path));
    }

    @Override
    public void writeFile(String path, String content) throws IOException {
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public void appendFile(String path, String content) throws IOException {
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public List<String> readLines(String path, long start, long count) throws IOException {
        // Files.lines streams lazily; skip/limit keep memory at O(count), not O(file size).
        try (Stream<String> lines = Files.lines(Path.of(path), StandardCharsets.UTF_8)) {
            return lines.skip(start - 1).limit(count).collect(Collectors.toList());
        }
    }

    @Override
    public FileStat fileInfo(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) throw new NoSuchFileException(path);
        boolean dir = Files.isDirectory(p);
        long size = dir ? 0 : Files.size(p);
        long modified = Files.getLastModifiedTime(p).toMillis();
        return new FileStat(dir ? "dir" : "file", size, modified);
    }

    @Override
    public List<DirEntry> listDir(String path) throws IOException {
        List<DirEntry> entries = new ArrayList<>();
        try (Stream<Path> list = Files.list(Path.of(path))) {
            for (Path p : (Iterable<Path>) list::iterator) {
                boolean dir = Files.isDirectory(p);
                long size = dir ? 0 : Files.size(p);
                entries.add(new DirEntry(p.getFileName().toString(), dir ? "dir" : "file", size));
            }
        }
        entries.sort(Comparator.comparing(DirEntry::name));
        return entries;
    }

    @Override
    public void deleteFile(String path) throws IOException {
        Path p = Path.of(path);
        if (Files.isDirectory(p)) throw new IOException("is a directory: " + path);
        Files.delete(p);   // throws NoSuchFileException if absent
    }

    @Override
    public HttpResponse httpRequest(String method, String url, Map<String, String> headers, String body, int timeoutMs)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method != null ? method.toUpperCase() : "GET");
            int t = timeoutMs > 0 ? timeoutMs : 30000;
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null) conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = readStream(in);
            Map<String, String> respHeaders = new LinkedHashMap<>();
            Map<String, List<String>> headerFields = conn.getHeaderFields();
            if (headerFields != null) {
                for (Map.Entry<String, List<String>> e : headerFields.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                        respHeaders.put(e.getKey(), e.getValue().getFirst());
                    }
                }
            }
            return new HttpResponse(status, respBody, respHeaders);
        } catch (IllegalArgumentException e) {
            throw new IOException("HTTP request failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IOException("HTTP request failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try (InputStream input = in) {
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
