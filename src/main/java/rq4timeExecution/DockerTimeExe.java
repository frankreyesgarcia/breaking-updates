package rq4timeExecution;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class Test {
    public static void main(String[] args) {
        DockerTimeExe dockerTimeExe = new DockerTimeExe();
        dockerTimeExe.dockerMetadata(Path.of("/Users/frank/Documents/Work/PHD/chains-project/fork/breaking-updates-fork/example"));
    }
}

public class DockerTimeExe {
    private static final Logger log = LoggerFactory.getLogger(DockerTimeExe.class);

    private static final String PRECEDING_COMMIT_CONTAINER_TAG = "-pre";

    private static final String BREAKING_UPDATE_CONTAINER_TAG = "-breaking";
    private static final String REGISTRY = "ghcr.io/chains-project/breaking-updates";
    private static DockerClient dockerClient;
    private static final Short EXIT_CODE_OK = 0;


    public void dockerMetadata(Path benchmarkDir) {

        File[] breakingUpdates = benchmarkDir.toFile().listFiles();
        createDockerClient();
        MapType buJsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        MapType dockerData = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, DockerImageData.class);

        Path reproducibilityResultsFilePath = Path.of("results-none-internet" +
                JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(reproducibilityResultsFilePath)) {
            try {
                Files.createFile(reproducibilityResultsFilePath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Map<String, Map<String, Long>> results = JsonUtils.readFromNullableFile(reproducibilityResultsFilePath,
                dockerData);
        if (results == null) {
            results = new HashMap<>();
        }


        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                if (!breakingUpdate.isFile() || !breakingUpdate.getName().endsWith(".json")) {
                    continue;
                }
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), buJsonType);
                Map<String, Long> imageMetadata = new HashMap<>();

                if (!results.containsKey((String) bu.get("breakingCommit"))) {

                    Path logDirectory;
                    try {
                        logDirectory = Files.createDirectories(Path.of("executionTimeLogs")
                                .resolve((String) bu.get("breakingCommit")));
                    } catch (IOException e) {
                        log.error("Could not create directories to copy the log file.");
                        throw new RuntimeException(e);
                    }

                    String prevImage = REGISTRY + ":" + bu.get("breakingCommit") + PRECEDING_COMMIT_CONTAINER_TAG;
                    String project = (String) bu.get("project");

                    Map.Entry<String, ImageData> prevContainer = startContainer(prevImage, true, project,logDirectory);
                    if (prevContainer == null || prevContainer.getValue().imageSize == -1 || prevContainer.getValue().imageTime() == -1) {
                        continue;
                    }

                    String breakingImage = REGISTRY + ":" + bu.get("breakingCommit") + BREAKING_UPDATE_CONTAINER_TAG;
                    Map.Entry<String, ImageData> breakingContainer = startContainer(breakingImage, false, project,logDirectory);
                    if (breakingContainer == null || breakingContainer.getValue().imageSize == -1 || breakingContainer.getValue().imageTime() == -1) {
                        continue;
                    }


                    imageMetadata.put("preImageTime", prevContainer.getValue().imageTime());
                    imageMetadata.put("preImageSize", prevContainer.getValue().imageSize());

                    imageMetadata.put("breakingImageTime", breakingContainer.getValue().imageTime());
                    imageMetadata.put("breakingImageSize", breakingContainer.getValue().imageSize());

                    results.put((String) bu.get("breakingCommit"), imageMetadata);


                    JsonUtils.writeToFile(reproducibilityResultsFilePath, results);
                    JsonUtils.writeToFile(reproducibilityResultsFilePath, results);
                    System.out.println("written to file");
                }
            }
        }
    }

    private Map.Entry<String, ImageData> startContainer(String image, boolean isPrevImage, String project,Path logDirectory) {
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            try {
                System.out.println("pulling image " + image);
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
            } catch (Exception ex) {
                log.error("Image not found for {}", image, ex);
                return null;
            }
        }
        ImageData imageData = null;

        CreateContainerCmd containerCmd = dockerClient.createContainerCmd(image)
                .withWorkingDir("/" + project)
                .withCmd("sh", "-c", "--network none", "set -o pipefail && (mvn clean test -B 2>&1 | tee -ai output.log)");
        CreateContainerResponse container = containerCmd.exec();
        String containerId = container.getId();

        //start container
        long startTime = System.currentTimeMillis();
        dockerClient.startContainerCmd(containerId).exec();
        System.out.println("created container for " + image + " container id " + containerId);
        WaitContainerResultCallback result = dockerClient.waitContainerCmd(containerId).exec(new WaitContainerResultCallback());
        long exitCode = result.awaitStatusCode();
        //stop container
        long endTime = System.currentTimeMillis();


        long elapsedTime = endTime - startTime;
        long size = dockerClient.inspectImageCmd(image).exec().getSize();

        imageData = new ImageData(elapsedTime, size);

        System.out.println("Docker reproduce " + elapsedTime + " milisegundos. y size  " + size);

        String name = isPrevImage ? "prev" : "breaking";
        storeLogFile(project, containerId, logDirectory,name);


        //        removeImage(image, containerId);

        return Map.entry(containerId, imageData);
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

    public record DockerImageData(long preImageTime, long preImageSize, long breakingImageTime,
                                  long breakingImageSize) {
    }

    public record ImageData(long imageTime, long imageSize) {
    }

    private void removeImage(String image, String containerId) {
        System.out.println("removing image " + image);
        try {
            dockerClient.stopContainerCmd(containerId).exec();
        } catch (Exception e) {
            System.out.println("container already stopped");
        }
        dockerClient.removeContainerCmd(containerId).exec();
        dockerClient.removeImageCmd(image).withForce(true).exec();
    }

    private Path storeLogFile(String project, String containerId, Path outputDir,String logName) {
        Path logOutputLocation = outputDir.resolve(logName+"CommitOutput.log");
        String logLocation = "/%s/output.log".formatted(project);
        try (InputStream logStream = dockerClient.copyArchiveFromContainerCmd(containerId, logLocation).exec()) {
            byte[] fileContent = logStream.readAllBytes();
            Files.write(logOutputLocation, fileContent);
            return logOutputLocation;
        } catch (IOException e) {
            log.error("Could not store the log file for the project {}", project);
            return null;
        }
    }
}
