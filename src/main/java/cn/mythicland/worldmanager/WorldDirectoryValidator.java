package cn.mythicland.worldmanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class WorldDirectoryValidator {

    private WorldDirectoryValidator() {
    }

    static void requireWorldDirectory(Path worldDirectory) throws IOException {
        if (Files.isSymbolicLink(worldDirectory)) {
            throw new IOException("World directory cannot be a symbolic link: " + worldDirectory);
        }
        if (!Files.isDirectory(worldDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World directory does not exist: " + worldDirectory);
        }

        Path levelDat = worldDirectory.resolve("level.dat");
        if (!Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World is missing a real level.dat: " + worldDirectory);
        }
    }
}
