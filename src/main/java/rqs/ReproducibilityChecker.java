package rqs;

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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Test {
    public static void main(String[] args) {
        ReproducibilityChecker reproducibilityChecker = new ReproducibilityChecker();
        reproducibilityChecker.runReproducibilityCheckerAndCounter(Path.of("/Users/frank/Documents/Work/PHD/chains-project/fork/breaking-updates-fork/data/benchmark"));
    }
}

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
        FAILURE_PATTERNS.put(Pattern.compile(
                        "(?i)(COMPILATION ERROR|COMPILATION_ERROR|Failed to execute goal io\\.takari\\.maven\\.plugins:takari-lifecycle-plugin.*?:compile)"),
                ReproducibleBreakingUpdate.FailureCategory.COMPILATION_FAILURE);
        FAILURE_PATTERNS.put(
                Pattern.compile("(?i)(\\[ERROR] Tests run:|There are test failures|There were test failures|" +
                        "Failed to execute goal org\\.apache\\.maven\\.plugins:maven-surefire-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.TEST_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile("(?i)(Failed to execute goal org\\.jenkins-ci\\.tools:maven-hpi-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JENKINS_PLUGIN_FAILURE);
        FAILURE_PATTERNS.put(
                Pattern.compile("(?i)(Failed to execute goal org\\.jvnet\\.jaxb2\\.maven2:maven-jaxb2-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.JAXB_FAILURE);
        FAILURE_PATTERNS.put(
                Pattern.compile(
                        "(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-scm-plugin:.*?:checkout)"),
                ReproducibleBreakingUpdate.FailureCategory.SCM_CHECKOUT_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile(
                        "(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-checkstyle-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.CHECKSTYLE_FAILURE);
        FAILURE_PATTERNS.put(
                Pattern.compile("(?i)(Failed to execute goal org\\.apache\\.maven\\.plugins:maven-enforcer-plugin)"),
                ReproducibleBreakingUpdate.FailureCategory.MAVEN_ENFORCER_FAILURE);
        FAILURE_PATTERNS.put(Pattern.compile(
                        "(?i)(Could not resolve dependencies|\\[ERROR] Some problems were encountered while processing the POMs|"
                                +
                                "\\[ERROR] .*?The following artifacts could not be resolved)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_RESOLUTION_FAILURE);
        FAILURE_PATTERNS.put(
                Pattern.compile("(?i)(Failed to execute goal se\\.vandmo:dependency-lock-maven-plugin:.*?:check)"),
                ReproducibleBreakingUpdate.FailureCategory.DEPENDENCY_LOCK_FAILURE);
    }

    public Map<String, Object> getLastPullrequest(Path listFilesPath) {

        File[] breakingUpdates = listFilesPath.toFile().listFiles();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        Map<String, Object> uniqueCombinations = new HashMap<>();

        assert breakingUpdates != null;
        for (File file : breakingUpdates) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                Map<String, Object> bu = JsonUtils.readFromFile(file.toPath(), buJsonType);


                String project = bu.get("project").toString();
                String projectOrg = bu.get("projectOrganisation").toString();

                String combinationKey = project + "(*_*)" + projectOrg;

                if (!uniqueCombinations.containsKey(combinationKey) ||
                        getPullRequestNumber(bu.get("url").toString()) >
                                getPullRequestNumber(((Map<?, ?>) uniqueCombinations.get(combinationKey)).get("url").toString())) {
                    uniqueCombinations.put(combinationKey, bu);
                }
            }
        }
        Map<String, Object> bu = new HashMap<>();
        uniqueCombinations.forEach((key, value) -> {
            bu.put(((Map<?, ?>) value).get("breakingCommit").toString(), value);

        });

        return bu;

    }

    private static int getPullRequestNumber(String url) {
        String[] parts = url.split("/");
        return Integer.parseInt(parts[parts.length - 1]);
    }


    public void runReproducibilityCheckerAndCounter(Path benchmarkDir) {

        createDockerClient();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);

        // Read reproducibility results
        MapType reproducibilityJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class,
                Boolean.class);
        Path reproducibilityResultsFilePath = Path.of("src/main/java/rqs/reproducibility-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(reproducibilityResultsFilePath)) {
            try {
                Files.createFile(reproducibilityResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Boolean> reproducibilityResults = JsonUtils.readFromNullableFile(reproducibilityResultsFilePath,
                reproducibilityJsonType);
        if (reproducibilityResults == null) {
            reproducibilityResults = new HashMap<>();
        }

        // Read dependency count results
        MapType depCountJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class,
                Map.class);
        Path depCountResultsFilePath = Path.of("src/main/java/rqs/dep-count-results" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(depCountResultsFilePath)) {
            try {
                Files.createFile(depCountResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        Map<String, Map<String, Object>> depCountResults = JsonUtils.readFromNullableFile(depCountResultsFilePath,
                depCountJsonType);
        if (depCountResults == null) {
            depCountResults = new HashMap<>();
        }

        Map<String, Object> breakingUpdates = getLastPullrequest(benchmarkDir);

        // Read breaking updates

        if (breakingUpdates != null) {
            for (Map.Entry<String, Object> entry : breakingUpdates.entrySet()) {
                String key = entry.getKey();
                Object values = entry.getValue();
                Map<String, Object> bu = (Map<String, Object>) values;
                Map<String, Object> depCount = new HashMap<>();

                String prevImage = REGISTRY + ":" + bu.get("breakingCommit") + PRECEDING_COMMIT_CONTAINER_TAG;


                // Create directories to copy the project.
                Path projectPath;
                try {
                    projectPath = Files.createDirectories(Path.of("tempProject")
                            .resolve((String) bu.get("breakingCommit")));
                } catch (IOException e) {
                    log.error("Could not create directories to copy the project.");
                    throw new RuntimeException(e);
                }


                // Run dependency counter.
                if (!depCountResults.containsKey(bu.get("projectOrganisation") + "/" + bu.get("project"))) {
                    isReproducible(bu.get("project").toString(), projectPath, prevImage, bu.get("breakingCommit").toString());
                    File treeFile = new File("src/main/java/rqs/dep-trees/" + bu.get("breakingCommit") + ".txt");
                    downloadDepTree(projectPath + File.separator + bu.get("project"), treeFile);
                    DependencyCounts allDepCounts = countDependencies(treeFile);
                    depCount.put("directCount", allDepCounts.directCount);
                    depCount.put("transitiveCount", allDepCounts.transitiveCount);
                    depCount.put("buCommit", bu.get("breakingCommit"));
                    depCountResults.put(bu.get("projectOrganisation") + "/" + bu.get("project"), depCount);

                    JsonUtils.writeToFile(depCountResultsFilePath, depCountResults);

                    removeProject(prevImage, projectPath);
                }


            }
        }
    }

    private void isReproducible(String project,
                                Path copyDir, String prevImage, String buCommit) {
        Map.Entry<String, Boolean> prevContainer = startContainer(prevImage, true, project);

        if (prevContainer == null)
            return;
        Path copiedProjectPath = copyProject(prevContainer.getKey(), project, copyDir);
        Path logFolder = Path.of("src//main//java//rqs//logs" + File.separator + buCommit);
        if (Files.notExists(logFolder)) {
            try {
                Files.createDirectory(logFolder);
            } catch (IOException e) {
                log.error("Could not create project directory to save log file.", e);
            }
        }
        storeLogFile(project, prevContainer.getKey(), logFolder, "prevCommitOutput.log");

    }

    /*
     * Returns false as the value of the map if the build failed for the previous
     * commit or build did not fail for the
     * breaking commit. The key of the map is the started containerID.
     */
    private Map.Entry<String, Boolean> startContainer(String image, boolean isPrevImage, String project) {
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
                .withWorkingDir("/" + project)
                .withCmd("sh", "-c", "--network none",
                        "set -o pipefail && (mvn clean test -B 2>&1 | tee -ai output.log)");
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Created container for " + image);
        WaitContainerResultCallback result = dockerClient.waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback());
        if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
            if (isPrevImage) {
                log.error("Previous commit failed for {}", image);
                return Map.entry(containerId, false);
            }
        } else {
            if (!isPrevImage) {
                log.error("Breaking commit did not fail for {}", image);
                return Map.entry(containerId, false);
            }
        }
        return Map.entry(containerId, true);
    }

    private Path storeLogFile(String project, String containerId, Path outputDir, String filename) {
        Path logOutputLocation = outputDir.resolve(filename);
        String logLocation = "/%s/output.log".formatted(project);
        try (InputStream logStream = dockerClient.copyArchiveFromContainerCmd(containerId, logLocation).exec()) {
            byte[] fileContent = logStream.readAllBytes();
            Files.write(logOutputLocation, fileContent);
            return logOutputLocation;
        } catch (Exception e) {
            log.error("Could not store the log file for the project {}", project);
            return null;
        }
    }

    private Path copyProject(String containerId, String project, Path dir) {
        dockerClient.startContainerCmd(containerId).exec();
        containers.add(containerId);

        try (InputStream dependencyStream = dockerClient.copyArchiveFromContainerCmd(containerId, "/" + project)
                .exec()) {
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
            log.info("Successfully copied the project {}.", project);
            return dir;
        } catch (Exception e) {
            log.error("Could not copy the project {}", project, e);
            return null;
        }
    }

    private void downloadDepTree(String projectPath, File treeFile) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("sh", "-c", "mvn dependency:tree -f %s".formatted(projectPath));

        try {
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedWriter writer = new BufferedWriter(new FileWriter(treeFile, false));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.replaceAll("\u001B\\[[;\\d]*m", ""); 
                writer.write(line);
                writer.newLine();
            }

            int exitCode = process.waitFor();
            if (exitCode != EXIT_CODE_OK) {
                log.error("Process for creating the dependency tree exited with error code {} for the tree in {}",
                        exitCode,
                        treeFile.getName());
            }
            writer.flush();
            writer.close();
        } catch (Exception e) {
            log.error("Could not create the dependency tree for the tree in {} due to an error", treeFile.getName(), e);
        }
    }

    private static DependencyCounts countDependencies(File treeFile) {
        Set<String> uniqueDirDependencies = new HashSet<>();
        Set<String> uniqueTranDependencies = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(treeFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.matches("\\[INFO] \\+- .*") || line.matches("\\[INFO] \\\\- .*")) {
                    String[] parts = line.split("- ")[1].split(":");
                    if (parts.length >= 3) {
                        String dependency = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
                        uniqueDirDependencies.add(dependency);
                    }
                } else if (line.matches("\\[INFO] \\| .*") || line.matches("\\[INFO]    \\+- .*") ||
                        line.matches("\\[INFO]    \\| .*") || line.matches("\\[INFO]    \\\\- .*")) {
                    String[] parts = line.split("- ")[1].split(":");
                    if (parts.length >= 3) {
                        String dependency = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3];
                        uniqueTranDependencies.add(dependency);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Could not count dependencies for {}", treeFile.getName());
            throw new RuntimeException(e);
        }
        return new DependencyCounts(uniqueDirDependencies.size(), uniqueTranDependencies.size());
    }

    private ReproducibleBreakingUpdate.FailureCategory getFailureCategory(Path path) {
        try {
            String logContent = Files.readString(path, StandardCharsets.ISO_8859_1);
            for (Map.Entry<Pattern, ReproducibleBreakingUpdate.FailureCategory> entry : FAILURE_PATTERNS.entrySet()) {
                Pattern pattern = entry.getKey();
                Matcher matcher = pattern.matcher(logContent);
                if (matcher.find()) {
                    log.info("Failure category found: {}", entry.getValue());
                    return entry.getValue();
                }
            }
            log.error("Did not find the failure category");
            return ReproducibleBreakingUpdate.FailureCategory.UNKNOWN_FAILURE;
        } catch (IOException e) {
            log.error("Failure category could not be parsed for {}", path);
            throw new RuntimeException(e);
        }
    }

    private void removeProject(String prevImage, Path projectPath) {
        try {
            dockerClient.removeImageCmd(prevImage).withForce(true).exec();

            FileUtils.forceDelete(new File(projectPath.toUri()));
        } catch (Exception e) {
            log.error("Project {} could not be deleted.", projectPath, e);
        }
        log.info("removed the images and the project.");
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

    public static class DependencyCounts {
        int directCount;
        int transitiveCount;

        DependencyCounts(int directCount, int transitiveCount) {
            this.directCount = directCount;
            this.transitiveCount = transitiveCount;
        }
    }
}
