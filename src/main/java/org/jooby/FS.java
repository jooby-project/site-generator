package org.jooby;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.Stream;

public class FS {

  public static void rm(final Path path) throws IOException {
    System.out.println("rm -rf " + path);
    if (Files.isDirectory(path)) {
      try (Stream<Path> files = Files.walk(path).skip(1)) {
        Iterator<Path> it = files.iterator();
        while (it.hasNext()) {
          Path file = it.next();
          if (Files.isDirectory(file)) {
            rm(file);
          }
          Files.deleteIfExists(file);
        }
        Files.deleteIfExists(path);
      }
    } else {
      Files.deleteIfExists(path);
    }
  }

  public static void mkdirs(final Path path) throws IOException {
    System.out.println("mkdir " + path);
    Files.createDirectories(path);
  }

}
