package cn.mythicland.worldmanager;

import java.io.IOException;
import java.io.Serial;
import java.util.List;

final class WorldCleanException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    WorldCleanException(List<String> failures, int deletedEntries) {
        super("World cleanup failed after deleting " + deletedEntries + " entries: " + String.join(", ", failures));
    }

}
