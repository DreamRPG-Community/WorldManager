package cn.mythicland.worldmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldCleanerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsMetadataAndTerrainAcrossAllDimensions() throws Exception {
        Path world = createWorld();
        Path overworldRegion = world.resolve("region");
        Path netherRegion = world.resolve("DIM-1").resolve("region");
        Path endRegion = world.resolve("DIM1").resolve("region");
        Files.createDirectories(overworldRegion);
        Files.createDirectories(netherRegion);
        Files.createDirectories(endRegion);
        Files.writeString(overworldRegion.resolve("r.0.0.mca"), "terrain");
        Files.writeString(netherRegion.resolve("r.0.0.mca"), "terrain");
        Files.writeString(endRegion.resolve("r.0.0.mca"), "terrain");

        Files.writeString(world.resolve("session.lock"), "runtime");
        Files.writeString(overworldRegion.resolve("not-terrain.txt"), "temporary");
        Files.createDirectories(world.resolve("playerdata"));
        Files.writeString(world.resolve("playerdata").resolve("player.dat"), "player");
        Files.createDirectories(world.resolve("data"));
        Files.writeString(world.resolve("data").resolve("map.dat"), "map");

        WorldCleaner.CleanResult result = new WorldCleaner().clean(world);

        assertTrue(result.deletedEntries() > 0);
        assertTrue(Files.exists(world.resolve("level.dat")));
        assertTrue(Files.exists(world.resolve("uid.dat")));
        assertTrue(Files.exists(overworldRegion.resolve("r.0.0.mca")));
        assertTrue(Files.exists(netherRegion.resolve("r.0.0.mca")));
        assertTrue(Files.exists(endRegion.resolve("r.0.0.mca")));
        assertFalse(Files.exists(world.resolve("session.lock")));
        assertFalse(Files.exists(overworldRegion.resolve("not-terrain.txt")));
        assertFalse(Files.exists(world.resolve("playerdata")));
        assertFalse(Files.exists(world.resolve("data")));
    }

    @Test
    void refusesWorldWithoutLevelDat() throws Exception {
        Path world = temporaryDirectory.resolve("missing-level");
        Files.createDirectory(world);

        assertThrows(IOException.class, () -> new WorldCleaner().clean(world));
    }

    @Test
    void removesEmptyDirectoriesAfterResourceCleanup() throws Exception {
        Path world = createWorld();
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("DIM-1").resolve("region"));
        Files.createDirectories(world.resolve("DIM1").resolve("region"));
        Files.createDirectories(world.resolve("playerdata").resolve("nested"));
        Files.writeString(world.resolve("playerdata").resolve("nested").resolve("player.dat"), "player");

        WorldCleaner.CleanResult result = new WorldCleaner().clean(world);

        assertTrue(result.deletedEntries() >= 5);
        assertTrue(Files.exists(world));
        assertTrue(Files.exists(world.resolve("level.dat")));
        assertFalse(Files.exists(world.resolve("region")));
        assertFalse(Files.exists(world.resolve("DIM-1")));
        assertFalse(Files.exists(world.resolve("DIM1")));
        assertFalse(Files.exists(world.resolve("playerdata")));
    }

    @Test
    void doesNotFollowSymbolicLinkWorldDirectory() throws Exception {
        Path realWorld = createWorld();
        Path linkedWorld = temporaryDirectory.resolve("linked-world");
        try {
            Files.createSymbolicLink(linkedWorld, realWorld);
        } catch (UnsupportedOperationException | SecurityException | IOException exception) {
            return;
        }

        assertThrows(IOException.class, () -> new WorldCleaner().clean(linkedWorld));
    }

    private Path createWorld() throws IOException {
        Path world = temporaryDirectory.resolve("world-" + System.nanoTime());
        Files.createDirectory(world);
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("uid.dat"), "uid");
        return world;
    }
}
