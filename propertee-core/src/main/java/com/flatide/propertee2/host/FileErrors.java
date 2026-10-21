package com.flatide.propertee2.host;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;

/** Script-facing text for a failed file operation: the reason, then the path. */
public final class FileErrors {

    private FileErrors() { }

    /**
     * {@code "No such file or directory: ./x"}. NIO's NoSuchFile/AccessDenied/NotDirectory
     * exceptions carry only the path as their message, which left a script with a bare
     * {@code './x'} and no reason; anything else keeps its own message.
     */
    public static String message(IOException e) {
        if (e instanceof FileSystemException f && f.getFile() != null) {
            String reason = f.getReason() != null ? f.getReason() : reason(f);
            if (reason != null) {
                return reason + ": " + f.getFile() + (f.getOtherFile() != null ? " -> " + f.getOtherFile() : "");
            }
        }
        return e.getMessage();
    }

    private static String reason(FileSystemException e) {
        if (e instanceof NoSuchFileException) return "No such file or directory";
        if (e instanceof AccessDeniedException) return "Permission denied";
        if (e instanceof NotDirectoryException) return "Not a directory";
        if (e instanceof FileAlreadyExistsException) return "File exists";
        if (e instanceof DirectoryNotEmptyException) return "Directory not empty";
        return null;
    }
}
