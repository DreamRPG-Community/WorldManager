package cn.mythicland.worldmanager;

import cn.mythicland.lib.path.FileTreeOperations;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class WorldCleaner {

    private static final Set<String> RETAINED_ROOT_FILES = Set.of("level.dat", "uid.dat");

    CleanResult clean(Path worldDirectory) throws IOException {
        WorldDirectoryValidator.requireWorldDirectory(worldDirectory);

        AtomicInteger deletedEntries = new AtomicInteger();
        List<String> failures = new ArrayList<>();
        try (Stream<Path> children = Files.list(worldDirectory)) {
            children.forEach(child -> {
                try {
                    cleanRootChild(child, deletedEntries);
                } catch (IOException exception) {
                    failures.add(child + ": " + exception.getMessage());
                }
            });
        }

        if (!failures.isEmpty()) {
            throw new WorldCleanException(failures, deletedEntries.get());
        }
        deletedEntries.addAndGet(FileTreeOperations.removeEmptyDirectories(worldDirectory));
        return new CleanResult(deletedEntries.get());
    }

    private void cleanRootChild(Path child, AtomicInteger deletedEntries) throws IOException {
        String name = child.getFileName().toString();
        if (RETAINED_ROOT_FILES.contains(name)
                && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (name.equals("region") && isRealDirectory(child)) {
            cleanRegionDirectory(child, deletedEntries);
            return;
        }
        if ((name.equals("DIM-1") || name.equals("DIM1")) && isRealDirectory(child)) {
            cleanDimensionDirectory(child, deletedEntries);
            return;
        }
        deleteRecursively(child, deletedEntries);
    }

    private void cleanDimensionDirectory(Path dimensionDirectory, AtomicInteger deletedEntries) throws IOException {
        try (Stream<Path> children = Files.list(dimensionDirectory)) {
            children.forEach(child -> {
                try {
                    if (child.getFileName().toString().equals("region") && isRealDirectory(child)) {
                        cleanRegionDirectory(child, deletedEntries);
                        return;
                    }
                    deleteRecursively(child, deletedEntries);
                } catch (IOException exception) {
                    throw new CleanupRuntimeException(exception);
                }
            });
        } catch (CleanupRuntimeException exception) {
            throw exception.cause;
        }
    }

    private void cleanRegionDirectory(Path regionDirectory, AtomicInteger deletedEntries) throws IOException {
        try (Stream<Path> children = Files.list(regionDirectory)) {
            children.forEach(child -> {
                String name = child.getFileName().toString();
                boolean retainedRegionFile = name.endsWith(".mca")
                        && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS);
                if (retainedRegionFile) return;

                try {
                    deleteRecursively(child, deletedEntries);
                } catch (IOException exception) {
                    throw new CleanupRuntimeException(exception);
                }
            });
        } catch (CleanupRuntimeException exception) {
            throw exception.cause;
        }
    }

    private void deleteRecursively(Path path, AtomicInteger deletedEntries) throws IOException {
        deletedEntries.addAndGet(FileTreeOperations.deleteRecursively(path));
    }

    private boolean isRealDirectory(Path path) {
        return !Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    record CleanResult(int deletedEntries) {
    }

    private static final class CleanupRuntimeException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final IOException cause;

        private CleanupRuntimeException(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
