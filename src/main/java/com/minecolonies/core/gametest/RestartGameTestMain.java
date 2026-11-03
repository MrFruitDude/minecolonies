package com.minecolonies.core.gametest;

import net.minecraft.gametest.framework.GameTestMainUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Restore only after GameTestMainUtil has reset its disposable universe, before it opens the world. */
public final class RestartGameTestMain
{
    private RestartGameTestMain() { }

    public static void main(final String[] args) throws Exception
    {
        GameTestMainUtil.runGameTestServer(args, universe -> {
            final String input = System.getenv("MINECOLONIES_RESTART_SOURCE");
            if (input == null) { throw new IllegalStateException("Missing validated restart snapshot"); }
            final Path source = Path.of(input).toAbsolutePath().normalize();
            final Path target = Path.of(universe).resolve("gametestworld").toAbsolutePath().normalize();
            if (!Files.isRegularFile(source.resolve("level.dat")) || Files.exists(target.resolve("level.dat")))
            {
                throw new IllegalStateException("Restart source is missing or destination was not reset");
            }
            try (var paths = Files.walk(source))
            {
                int files = 0;
                for (final Path path : paths.toList())
                {
                    if (Files.isSymbolicLink(path)) { throw new IOException("Snapshot contains a symlink: " + path); }
                    final Path destination = target.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) { Files.createDirectories(destination); }
                    else
                    {
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                        files++;
                    }
                }
                System.out.println("RESTART restored saved-world files=" + files + " destination=" + target);
            }
            catch (final IOException error)
            {
                throw new UncheckedIOException(error);
            }
        });
    }
}
