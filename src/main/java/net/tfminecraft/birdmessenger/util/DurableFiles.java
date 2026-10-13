package net.tfminecraft.birdmessenger.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Whole files only: a temporary file is flushed, then moved over the target, then the folder is flushed. */
public final class DurableFiles {

	private DurableFiles() {
	}

	public static void write(Path target, String text) throws IOException {
		Path folder = target.toAbsolutePath().getParent();
		Files.createDirectories(folder);
		Path temp = Files.createTempFile(folder, ".pending-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
				channel.force(true);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
