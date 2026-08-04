package cn.mythicland.worldmanager;

import cn.mythicland.lib.path.FileTreeOperations;
import cn.mythicland.lib.path.SafePathResolver;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

final class WorldSnapshotService {

    private static final Set<String> SNAPSHOT_DIMENSIONS = Set.of("", "DIM-1", "DIM1");

    private final SafePathResolver snapshotResolver;
    private final SafePathResolver workResolver;

    WorldSnapshotService(Path snapshotsRoot, Path workRoot) {
        this.snapshotResolver = new SafePathResolver(snapshotsRoot);
        this.workResolver = new SafePathResolver(workRoot);
    }

    void ensureRootDirectory() throws IOException {
        snapshotResolver.ensureRootDirectory();
        workResolver.ensureRootDirectory();
    }

    SnapshotSaveResult save(String logicalName, Path worldDirectory) throws IOException {
        WorldDirectoryValidator.requireWorldDirectory(worldDirectory);

        Path snapshotDirectory = snapshotResolver.resolveSingleSegment(logicalName);
        Path temporaryDirectory = workResolver.root().resolve(".snapshot-" + UUID.randomUUID());
        Files.createDirectory(temporaryDirectory);
        try {
            int copiedEntries = copyMapFiles(worldDirectory, temporaryDirectory);
            FileTreeOperations.removeEmptyDirectories(temporaryDirectory);
            replaceSnapshot(temporaryDirectory, snapshotDirectory);
            return new SnapshotSaveResult(copiedEntries);
        } finally {
            deleteRecursivelyIfPresent(temporaryDirectory);
        }
    }

    SnapshotRestoreResult restore(String logicalName, Path worldDirectory) throws IOException {
        Path snapshotDirectory = snapshotResolver.resolveSingleSegment(logicalName);
        WorldDirectoryValidator.requireWorldDirectory(snapshotDirectory);
        prepareTargetDirectory(worldDirectory);

        AtomicInteger deletedEntries = new AtomicInteger();
        try (Stream<Path> children = Files.list(worldDirectory)) {
            for (Path child : children.toList()) {
                deletedEntries.addAndGet(FileTreeOperations.deleteRecursively(child));
            }
        }

        int copiedEntries = copyMapFiles(snapshotDirectory, worldDirectory);
        return new SnapshotRestoreResult(deletedEntries.get(), copiedEntries);
    }

    private int copyMapFiles(Path sourceDirectory, Path targetDirectory) throws IOException {
        int copiedEntries = 0;
        copyRequiredFile(sourceDirectory.resolve("level.dat"), targetDirectory.resolve("level.dat"));
        copiedEntries++;

        Path sourceUid = sourceDirectory.resolve("uid.dat");
        if (Files.exists(sourceUid, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceUid)) {
            copyRequiredFile(sourceUid, targetDirectory.resolve("uid.dat"));
            copiedEntries++;
        }

        for (String dimension : SNAPSHOT_DIMENSIONS) {
            Path sourceDimension = dimension.isEmpty() ? sourceDirectory : sourceDirectory.resolve(dimension);
            Path targetDimension = dimension.isEmpty() ? targetDirectory : targetDirectory.resolve(dimension);
            requireDirectoryOrAbsent(sourceDimension);
            requireDirectoryOrAbsent(targetDimension);
            Path sourceRegion = sourceDimension.resolve("region");
            Path targetRegion = targetDimension.resolve("region");
            copiedEntries += copyRegionFiles(sourceRegion, targetRegion);
        }
        return copiedEntries;
    }

    private void copyRequiredFile(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source)) {
            throw new IOException("World resource cannot be a symbolic link: " + source);
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World resource is not a regular file: " + source);
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private int copyRegionFiles(Path sourceRegion, Path targetRegion) throws IOException {
        if (!Files.exists(sourceRegion, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (Files.isSymbolicLink(sourceRegion)) {
            throw new IOException("World region directory cannot be a symbolic link: " + sourceRegion);
        }
        if (!Files.isDirectory(sourceRegion, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World region path is not a directory: " + sourceRegion);
        }

        AtomicInteger copiedEntries = new AtomicInteger();
        Files.walkFileTree(sourceRegion, new SimpleFileVisitor<>() {
            @Override
            public @Nonnull FileVisitResult preVisitDirectory(
                    @Nonnull Path directory,
                    @Nonnull BasicFileAttributes attributes
            )
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("World region directory cannot be a symbolic link: " + directory);
                }
                Path targetDirectory = targetRegion.resolve(sourceRegion.relativize(directory));
                Files.createDirectories(targetDirectory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @Nonnull FileVisitResult visitFile(
                    @Nonnull Path file,
                    @Nonnull BasicFileAttributes attributes
            ) throws IOException {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("World region file cannot be a symbolic link: " + file);
                }
                if (!attributes.isRegularFile() || !file.getFileName().toString().endsWith(".mca")) {
                    return FileVisitResult.CONTINUE;
                }

                Path target = targetRegion.resolve(sourceRegion.relativize(file)).normalize();
                if (!target.startsWith(targetRegion)) {
                    throw new IOException("World region file escapes the snapshot directory: " + file);
                }
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                copiedEntries.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
        return copiedEntries.get();
    }

    private void requireDirectoryOrAbsent(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("World dimension cannot be a symbolic link: " + directory);
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World dimension is not a directory: " + directory);
        }
    }

    private void replaceSnapshot(Path temporaryDirectory, Path snapshotDirectory) throws IOException {
        Path previousDirectory = workResolver.root().resolve(".previous-" + UUID.randomUUID());
        boolean movedPrevious = false;
        try {
            if (Files.exists(snapshotDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(snapshotDirectory)) {
                Files.move(snapshotDirectory, previousDirectory);
                movedPrevious = true;
            }
            Files.move(temporaryDirectory, snapshotDirectory);
            if (movedPrevious) deleteRecursivelyIfPresent(previousDirectory);
        } catch (IOException exception) {
            if (movedPrevious && !Files.exists(snapshotDirectory, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(previousDirectory, snapshotDirectory);
            }
            throw exception;
        } finally {
            deleteRecursivelyIfPresent(previousDirectory);
        }
    }

    private void prepareTargetDirectory(Path worldDirectory) throws IOException {
        if (Files.isSymbolicLink(worldDirectory)) {
            throw new IOException("World directory cannot be a symbolic link: " + worldDirectory);
        }
        if (Files.exists(worldDirectory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(worldDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + worldDirectory);
        }
        Files.createDirectories(worldDirectory);
    }

    private void deleteRecursivelyIfPresent(Path path) throws IOException {
        FileTreeOperations.deleteRecursively(path);
    }

    record SnapshotSaveResult(int copiedEntries) {
    }

    record SnapshotRestoreResult(int deletedEntries, int copiedEntries) {
    }

}
