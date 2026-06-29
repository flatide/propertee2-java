package com.flatide.platform;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * Unrestricted PlatformProvider with direct OS access.
 * Hosts can use this directly or extend it with restrictions
 * (allowed paths, read-only mode, etc.).
 */
public class DefaultPlatformProvider implements PlatformProvider {

    @Override
    public String getEnv(String name) {
        return System.getenv(name);
    }

    @Override
    public boolean fileExists(String path) {
        return new File(path).exists();
    }

    @Override
    public FileInfo fileInfo(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + path);
        }
        return new FileInfo(
            file.isDirectory() ? "dir" : "file",
            file.length(),
            file.lastModified()
        );
    }

    @Override
    public List<FileEntry> listDir(String path) {
        File dir = new File(path);
        if (!dir.isDirectory()) {
            throw new RuntimeException("Not a directory: " + path);
        }
        File[] files = dir.listFiles();
        List<FileEntry> entries = new ArrayList<FileEntry>();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
            for (File f : files) {
                entries.add(new FileEntry(
                    f.getName(),
                    f.isDirectory() ? "dir" : "file",
                    f.length()
                ));
            }
        }
        return entries;
    }

    @Override
    public List<String> readLines(String path, int start, int count) {
        File file = new File(path);
        if (!file.isFile()) {
            throw new RuntimeException("File not found: " + path);
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            List<String> lines = new ArrayList<String>();
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (lineNum < start) continue;
                if (lines.size() >= count) break;
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void writeFile(String path, String content) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + e.getMessage(), e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void writeLines(String path, List<String> lines) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + e.getMessage(), e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void appendFile(String path, String content) {
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(path, true), "UTF-8");
            writer.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to append to file: " + e.getMessage(), e);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public void mkdir(String path) {
        File dir = new File(path);
        if (dir.exists()) {
            if (dir.isDirectory()) return;
            throw new RuntimeException("Path exists but is not a directory: " + path);
        }
        if (!dir.mkdirs()) {
            throw new RuntimeException("Failed to create directory: " + path);
        }
    }

    @Override
    public void deleteFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + path);
        }
        if (file.isDirectory()) {
            throw new RuntimeException("Cannot delete directory: " + path);
        }
        if (!file.delete()) {
            throw new RuntimeException("Failed to delete file: " + path);
        }
    }

    @Override
    public HttpResponse httpRequest(String method, String url, Map<String, String> headers, String body, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
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
            if (body != null && body.length() > 0) {
                conn.setDoOutput(true);
                byte[] payload = body.getBytes("UTF-8");
                OutputStream os = conn.getOutputStream();
                try {
                    os.write(payload);
                } finally {
                    os.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = readStream(in);
            Map<String, String> respHeaders = new LinkedHashMap<String, String>();
            Map<String, List<String>> hf = conn.getHeaderFields();
            if (hf != null) {
                for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                        respHeaders.put(e.getKey(), e.getValue().get(0));
                    }
                }
            }
            return new HttpResponse(status, respBody, respHeaders);
        } catch (IOException e) {
            throw new RuntimeException("HTTP request failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        try {
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } finally {
            in.close();
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}
