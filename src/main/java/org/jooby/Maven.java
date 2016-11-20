package org.jooby;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.zeroturnaround.exec.ProcessExecutor;

public class Maven {

  private Path project;
  private String exec;

  public Maven(final Path project) {
    this.project = project;
  }

  public void run(final String... args) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(exec);
    cmd.addAll(Arrays.asList(args));
    execute(cmd);
  }

  private void execute(final List<String> args) throws Exception {
    System.out.println(args.stream().collect(Collectors.joining(" ")));
    int exit = new ProcessExecutor()
        .command(args.toArray(new String[args.size()]))
        .redirectOutput(System.out)
        .directory(project.toFile())
        .execute()
        .getExitValue();
    if (exit != 0) {
      throw new IllegalStateException("Execution of " + args + " resulted in exit code: " + exit);
    }
  }

  public Maven executable(final String exec) {
    this.exec = exec;
    return this;
  }

}
