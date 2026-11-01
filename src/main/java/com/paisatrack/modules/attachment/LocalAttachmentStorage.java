package com.paisatrack.modules.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Files in a private directory outside the web root ({@code paisatrack.attachments.dir}). Writes go
 * to a temp file first and are moved into place, so a crash never leaves half a file. Every key is
 * resolved and checked to stay inside the root.
 */
@Component
class LocalAttachmentStorage implements AttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalAttachmentStorage.class);

    private final Path root;

    LocalAttachmentStorage(@Value("${paisatrack.attachments.dir}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Attachment storage dir={}", root);
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store attachment", e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read attachment", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Attachment file not deleted key={} error={}", key, e.getClass().getSimpleName());
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }
}
