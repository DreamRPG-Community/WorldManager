package cn.mythicland.worldmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void restoresPersistentSnapshotIntoRuntimeWithoutRestoringRuntimeFiles() throws Exception {
        Path sourceWorld = createWorld();
        Path snapshots = temporaryDirectory.resolve("worlds");
        Path runtimeRoot = temporaryDirectory.resolve(".runtime");
        WorldSnapshotService service = new WorldSnapshotService(snapshots, runtimeRoot);
        service.ensureRootDirectory();

        WorldSnapshotService.SnapshotSaveResult saved = service.save("main", sourceWorld);
        Path runtimeWorld = runtimeRoot.resolve("main");
        service.restore("main", runtimeWorld);

        Files.createDirectories(runtimeWorld.resolve("playerdata"));
        Files.createDirectories(runtimeWorld.resolve("data").resolve("functions"));
        Files.writeString(runtimeWorld.resolve("level.dat"), "changed-level");
        Files.writeString(runtimeWorld.resolve("uid.dat"), "changed-uid");
        Files.writeString(runtimeWorld.resolve("region").resolve("r.0.0.mca"), "changed-terrain");
        Files.writeString(runtimeWorld.resolve("playerdata").resolve("player.dat"), "player");
        Files.writeString(runtimeWorld.resolve("data").resolve("functions").resolve("runtime.mcfunction"), "runtime");
        Files.writeString(runtimeWorld.resolve("session.lock"), "runtime");

        WorldSnapshotService.SnapshotRestoreResult restored = service.restore("main", runtimeWorld);

        assertTrue(saved.copiedEntries() >= 4);
        assertTrue(restored.deletedEntries() >= 4);
        assertTrue(restored.copiedEntries() >= 4);
        assertEquals("level", Files.readString(runtimeWorld.resolve("level.dat")));
        assertEquals("uid-current", Files.readString(runtimeWorld.resolve("uid.dat")));
        assertEquals("terrain", Files.readString(runtimeWorld.resolve("region").resolve("r.0.0.mca")));
        assertTrue(Files.exists(runtimeWorld.resolve("DIM-1").resolve("region").resolve("r.0.0.mca")));
        assertTrue(Files.exists(runtimeWorld.resolve("DIM1").resolve("region").resolve("r.0.0.mca")));
        assertFalse(Files.exists(runtimeWorld.resolve("playerdata")));
        assertFalse(Files.exists(runtimeWorld.resolve("data")));
        assertFalse(Files.exists(runtimeWorld.resolve("session.lock")));
    }

    @Test
    void savingAgainReplacesThePreviousStartupSnapshot() throws Exception {
        Path world = createWorld();
        Path runtimeRoot = temporaryDirectory.resolve(".runtime");
        WorldSnapshotService service = new WorldSnapshotService(
                temporaryDirectory.resolve("worlds"),
                runtimeRoot
        );
        service.ensureRootDirectory();
        service.save("main", world);

        Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "new-terrain");
        service.save("main", world);
        Path runtimeWorld = runtimeRoot.resolve("main");
        service.restore("main", runtimeWorld);

        assertEquals("new-terrain", Files.readString(runtimeWorld.resolve("region").resolve("r.0.0.mca")));
    }

    @Test
    void rejectsSymbolicLinkWorld() throws Exception {
        Path world = createWorld();
        Path linkedWorld = temporaryDirectory.resolve("linked-world");
        try {
            Files.createSymbolicLink(linkedWorld, world);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            return;
        }

        WorldSnapshotService service = new WorldSnapshotService(
                temporaryDirectory.resolve("worlds"),
                temporaryDirectory.resolve(".runtime")
        );
        service.ensureRootDirectory();

        assertThrows(IOException.class, () -> service.save("linked", linkedWorld));
    }

    private Path createWorld() throws IOException {
        Path world = temporaryDirectory.resolve("world-" + System.nanoTime());
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("DIM-1").resolve("region"));
        Files.createDirectories(world.resolve("DIM1").resolve("region"));
        Files.createDirectories(world.resolve("data").resolve("functions"));
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("uid.dat"), "uid-current");
        Files.writeString(world.resolve("region").resolve("r.0.0.mca"), "terrain");
        Files.writeString(world.resolve("region").resolve("ignored.txt"), "ignored");
        Files.writeString(world.resolve("DIM-1").resolve("region").resolve("r.0.0.mca"), "nether");
        Files.writeString(world.resolve("DIM1").resolve("region").resolve("r.0.0.mca"), "end");
        return world;
    }
}
