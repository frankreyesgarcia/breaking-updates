package rq4timeExecution;

import picocli.CommandLine;

import java.nio.file.Path;

/**
 * This class represents the main entry point to the breaking update reproducer.
 *
 * @author <a href="mailto:frankrg@kth.se">Frank Reyes</a>
 * <p>
 * // TODO: Add option to select a whole directory of files to reproduce
 * // TODO: Add option to redo reproduction (default should be to ignore the breaking update if already reproduced)
 */
public class Main {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Reproduce()).execute(args);
        System.exit(exitCode);
    }

    @CommandLine.Command(name = "docker-metadata", mixinStandardHelpOptions = true, version = "0.1")
    private static class Reproduce implements Runnable {

        @CommandLine.Option(
                names = {"-b", "--benchmark-dir"},
                paramLabel = "BENCHMARK-DIR",
                description = "The directory where successful breaking update reproduction information are written.",
                required = true
        )
        Path benchmarkDir;

        @Override
        public void run() {
            DockerTimeExe dockerTimeExe = new DockerTimeExe();
            dockerTimeExe.dockerMetadata(benchmarkDir);
        }
    }
}