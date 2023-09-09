package rqs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.type.MapType;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import miner.JsonUtils;
import miner.ReproducibleBreakingUpdate;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReproducibilityChecker {

    private static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static final Logger log = LoggerFactory.getLogger(ReproducibilityChecker.class);
    private static DockerClient dockerClient;
    private static final Short EXIT_CODE_OK = 0;
    private static final List<String> containers = new ArrayList<>();

    public static final Map<Pattern, ReproducibleBreakingUpdate.FailureCategory> FAILURE_PATTERNS = new HashMap<>();

    static {
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(COMPILATION ERROR | Failed to execute goal io\\.takari\\.maven\\.plugins:takari-lifecycle-plugin.*?:compile)"),
                ReproducibleBreakingUpdate.FailureCategory.COMPILATION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(\\[ERROR] Tests run: | There are test failures | There were test failures |" +
                        "Failed to execute goal org\\.apache\\.maven\\.plugins:maven-surefire-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.TEST_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.jenkins-ci\\.tools:maven-hpi-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JENKINS_PLUGIN_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.jvnet\\.jaxb2\\.maven2:maven-jaxb2-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JAXB_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-scm-plugin:.*?:checkout)"),
                ReproducibleBreakingUpdate.FailureCategory.SCM_CHECKOUT_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-checkstyle-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.CHECKSTYLE_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-enforcer-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.MAVEN_ENFORCER_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Could not resolve dependencies | \\[ERROR] Some problems were encountered while processing the POMs | " +
                        "\\[ERROR] .*?The following artifacts could not be resolved)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_RESOLUTION_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal se\\.vandmo:dependency-lock-maven-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_LOCK_FAILURE);
    }

    public void runReproducibilityChecker(Path benchmarkDir) throws IOException {

        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        createDockerClient();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        // Read reproducibility results
        MapType reproducibilityJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class);
        Path reproducibilityResultsFilePath = Path.of("src/main/java/rqs/reproducibility-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(reproducibilityResultsFilePath)) {
            try {
                Files.createFile(reproducibilityResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Object> reproducibilityResults = JsonUtils.readFromNullableFile(reproducibilityResultsFilePath,
                reproducibilityJsonType);
        if (reproducibilityResults == null) {
            reproducibilityResults = new HashMap<>();
        }

        // Read dependency count results
        MapType depCountJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class,
                DependencyCounts.class);
        Path depCountResultsFilePath = Path.of("src/main/java/rqs/dep-count-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(depCountResultsFilePath)) {
            try {
                Files.createFile(depCountResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, DependencyCounts> depCountResults = JsonUtils.readFromNullableFile(depCountResultsFilePath,
                depCountJsonType);
        if (depCountResults == null) {
            depCountResults = new HashMap<>();
        }

        int temp = 0;
        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, Object> depCount = new HashMap<>();
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), buJsonType);
                if (!depCountResults.containsKey((String) bu.get("breakingCommit"))) {
                    String image = REGISTRY + ":" + bu.get("breakingCommit") + BREAKING_UPDATE_CONTAINER_TAG;
                    Path projectPath = Files.createDirectories(Path.of("tempProject")
                            .resolve(image.split(":")[1]));
                    ReproducibleBreakingUpdate.FailureCategory failureCategory =
                            ReproducibleBreakingUpdate.FailureCategory.valueOf((String) bu.get("failureCategory"));
                    boolean isReproducible = isReproducible(failureCategory, (String) bu.get("breakingCommit"),
                            (String) bu.get("project"), projectPath);
                    String treeFileName = "src/main/java/rqs/dep-trees/" + bu.get("breakingCommit") + ".txt";
                    downloadDepTree(String.valueOf(projectPath), treeFileName);
                    depCountResults.put((String) bu.get("breakingCommit"), countDependencies(treeFileName));
                    reproducibilityResults.put((String) bu.get("breakingCommit"), isReproducible);
                    removeProject(image, projectPath);
                    temp += 1;
                    if (temp > 5) {
                        JsonUtils.writeToFile(depCountResultsFilePath, depCountResults);
                        JsonUtils.writeToFile(reproducibilityResultsFilePath, reproducibilityResults);
                        System.out.println("written to file");
                        temp = 0;
                    }
                }
            }
        }
        JsonUtils.writeToFile(depCountResultsFilePath, depCountResults);
        JsonUtils.writeToFile(reproducibilityResultsFilePath, reproducibilityResults);
    }

    private boolean isReproducible(ReproducibleBreakingUpdate.FailureCategory failureCategory, String buCommit,
                                   String project, Path copyDir) {
        String prevImage = REGISTRY + ":" + buCommit + PRECEDING_COMMIT_CONTAINER_TAG;
        String breakingImage = REGISTRY + ":" + buCommit + BREAKING_UPDATE_CONTAINER_TAG;
        String prevContainerId = startContainer(prevImage, true);
        String breakingContainerId = startContainer(breakingImage, false);
        if (prevContainerId == null || breakingContainerId == null)
            return false;
        copyProject(breakingContainerId, project, copyDir);
        Path logFilePath = Path.of(copyDir + "output.log");
        return getFailureCategory(logFilePath).equals(failureCategory);
    }

    private String startContainer(String image, boolean isPrevImage) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (Exception ex) {
                log.error("Image not found for {}", image, ex);
                return null;
            }
        }
        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image)
                .withCmd("sh", "-c", "mvn clean test -B | tee output.log");
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();
        WaitContainerResultCallback result = dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
        if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
            if (isPrevImage) {
                log.error("Previous commit failed for {}", image);
                return null;
            }
        } else {
            if (!isPrevImage) {
                log.error("Breaking commit did not fail for {}", image);
            }
        }

        return containerId;
    }

    private Path copyProject(String containerId, String project, Path dir) {
        if (containerId == null)
                return null;
        dockerClient.startContainerCmd(containerId).exec();
        containers.add(containerId);

        try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd
                (containerId, "/" + project).exec()) {
            try (TarArchiveInputStream tarStream = new TarArchiveInputStream(dependencyStream)) {
                TarArchiveEntry entry;
                while ((entry = tarStream.getNextTarEntry()) != null) {
                    if (!entry.isDirectory()) {
                        Path filePath = dir.resolve(entry.getName());

                        if (!Files.exists(filePath)) {
                            Files.createDirectories(filePath.getParent());
                            Files.createFile(filePath);

                            byte[] fileContent = tarStream.readAllBytes();
                            Files.write(filePath, fileContent, StandardOpenOption.WRITE);
                        }
                    }
                }
            }
            return dir;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void downloadDepTree(String projectPath, String treeFileName) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("cmd.exe", "/c", "mvn -f %s".formatted(projectPath), "dependency:tree");

        try {
            Process process = processBuilder.start();
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new FileWriter(treeFileName));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                writer.write(line);
            }

            int exitCode = process.waitFor();
            System.out.println("\nExited with error code : " + exitCode);

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static DependencyCounts countDependencies(String filePath) throws IOException {
        int directCount = 0;
        int transitiveCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("\\[INFO\\] \\+- .*")) {
                    directCount++;
                } else if (line.matches("\\[INFO\\] \\| .*")) {
                    transitiveCount++;
                }
            }
        }
        return new DependencyCounts(directCount, transitiveCount);
    }

    private ReproducibleBreakingUpdate.FailureCategory getFailureCategory(Path path) {
        try {
            String logContent = Files.readString(path, StandardCharsets.ISO_8859_1);
            for (Map.Entry<Pattern, ReproducibleBreakingUpdate.FailureCategory> entry : FAILURE_PATTERNS.entrySet()) {
                Pattern pattern = entry.getKey();
                Matcher matcher = pattern.matcher(logContent);
                if (matcher.find()) {
                    return entry.getValue();
                }
            }
            return ReproducibleBreakingUpdate.FailureCategory.UNKNOWN_FAILURE;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void removeProject(String image, Path projectPath) {
        try {
            for (String container : containers) {
                dockerClient.stopContainerCmd(container).exec();
                dockerClient.removeContainerCmd(container).exec();
            }
            containers.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
        dockerClient.removeImageCmd(image).withForce(true).exec();
        try {
            FileUtils.forceDelete(new File(projectPath.toUri()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createDockerClient() {
        DockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://hub.docker.com")
                .build();
        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .connectTimeout(30)
                .build();
        dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    private static class DependencyCounts {
        int directCount;
        int transitiveCount;
        @JsonCreator
        DependencyCounts(@JsonProperty("directCount") int directCount,
                         @JsonProperty("transitiveCount") int transitiveCount) {
            this.directCount = directCount;
            this.transitiveCount = transitiveCount;
        }
    }

}
