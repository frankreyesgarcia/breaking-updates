package reproducer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.okhttp.OkDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import miner.BreakingUpdate;
import miner.JsonUtils;
import miner.ReproducibleBreakingUpdate;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * The BreakingUpdateReproducer class attempts to reproduce breaking updates in
 * a container.
 * In case of a successful reproduction, the resulting container is stored.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class BreakingUpdateReproducer {

    public String BASE_IMAGE = "ghcr.io/chains-project/breaking-updates:base-image";
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private static final Short EXIT_CODE_OK = 0;

    private final ResultManager resultManager;
    private final DockerClient client;
    private final ReproducibleBreakingUpdate.FailureCategory failureCategory;

    /**
     * Set up a new BreakingUpdateReproducer creating new Docker images based on
     * {@value BASE_IMAGE}
     *
     * @param resultManager the ResultManager that will store information about
     *                      reproduction results.
     */
    public BreakingUpdateReproducer(ResultManager resultManager,
            ReproducibleBreakingUpdate.FailureCategory failureCategory) {
        this.resultManager = resultManager;
        DockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withRegistryUrl("https://hub.docker.com")
                .build();
        DockerHttpClient httpClient = new OkDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .connectTimeout(30)
                .build();
        client = DockerClientImpl.getInstance(clientConfig, httpClient);
        log.info("Docker client created");

        // try {
        // ensureBaseMavenImageExists();
        // } catch (InterruptedException e) {
        // throw new RuntimeException(e);
        // }
        this.failureCategory = failureCategory;
    }

    /**
     * Iterate through a list of breaking updates and attempt to reproduce if not
     * already attempted.
     *
     * @param breakingUpdates the list of breaking updates to reproduce.
     */
    public void reproduceAll(File[] breakingUpdates) {
        for (File breakingUpdate : breakingUpdates) {
            try {
                BreakingUpdate bu = JsonUtils.readFromFile(breakingUpdate.toPath(), BreakingUpdate.class);
                reproduce(bu);
            } catch (RuntimeException | InterruptedException e) {
                log.error("An exception occurred while reproducing the breaking update in " +
                        breakingUpdate.getName(), e);
            }
        }
    }

    /**
     * Attempt to reproduce the given breaking update.
     *
     * @param bu the breaking update to reproduce.
     */
    public void reproduce(BreakingUpdate bu) throws InterruptedException {
        String javaVersion = identifyJavaVersionFromCI(bu, resultManager);
        System.out.println("Java version used in CI for breaking update " + bu.breakingCommit + ": " + javaVersion);

        createBaseImageForBreakingUpdate(bu, javaVersion);
        Map<String, String> startedContainers = new HashMap<>();
        boolean isPrevBuildSuccessful = false;
        int prevAttemptCount;
        // Try running tests 3 times for the previous commit to ensure that the build is
        // reproducible.
        for (prevAttemptCount = 1; prevAttemptCount < 4; prevAttemptCount++) {
            log.info("Attempting for the {} time to compile and test the previous commit of breaking update {}",
                    prevAttemptCount, bu.breakingCommit);
            startedContainers.put("prevContainer%s".formatted(prevAttemptCount), startContainer(bu, getPrevCmd(bu)));
            WaitContainerResultCallback result = client.waitContainerCmd(startedContainers.get("prevContainer%s"
                    .formatted(prevAttemptCount)))
                    .exec(new WaitContainerResultCallback());
            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                log.info("Build failed for the previous commit of {} in the {} attempt.", bu.breakingCommit,
                        prevAttemptCount);
                break;
            } else {
                if (prevAttemptCount > 2) {
                    isPrevBuildSuccessful = true;
                }
            }
        }
        if (!isPrevBuildSuccessful) {
            resultManager.saveUnsuccessfulReproductionResult(bu, javaVersion);
            resultManager.storeLogFile(bu, startedContainers.get("prevContainer%s".formatted(prevAttemptCount)), false);
            removeContainers(bu, startedContainers.values());
            removeImages(bu, List.of("base"));

            return;
        }

        boolean isBuildSuccessfullyFailed = false;
        int attemptCount;
        ReproducibleBreakingUpdate.FailureCategory prevFailure = null;
        ReproducibleBreakingUpdate.FailureCategory newFailure;
        // Try running tests 3 times to ensure that the breakage is reproducible.
        for (attemptCount = 1; attemptCount < 4; attemptCount++) {
            log.info("Attempting for the {} time to compile and test breaking update {}", attemptCount,
                    bu.breakingCommit);
            startedContainers.put("postContainer%s".formatted(attemptCount), startContainer(bu, getPostCmd(bu)));
            WaitContainerResultCallback result = client.waitContainerCmd(startedContainers.get("postContainer%s"
                    .formatted(attemptCount))).exec(new WaitContainerResultCallback());
            if (result.awaitStatusCode().intValue() != EXIT_CODE_OK) {
                newFailure = resultManager.getFailure(bu,
                        startedContainers.get("postContainer%s".formatted(attemptCount)), true);
                if (attemptCount == 1) {
                    prevFailure = resultManager.getFailure(bu,
                            startedContainers.get("postContainer%s".formatted(attemptCount)), true);
                } else if (!newFailure.equals(prevFailure)) {
                    log.info(
                            "Build has failed due to a different reason in the {} attempt than in the previous attempt.",
                            attemptCount);
                    if (attemptCount > 1)
                        resultManager.removeLogFile(bu, "successfulReproductionLogs");
                    break;
                } else if (attemptCount > 2) {
                    isBuildSuccessfullyFailed = true;
                }
            } else {
                log.info("Breaking commit did not fail in the {} attempt.", attemptCount);
                // Remove the log file saved in the successful directory in the previous
                // attempts.
                if (attemptCount > 1)
                    resultManager.removeLogFile(bu, "successfulReproductionLogs");
                break;
            }
        }
        if (isBuildSuccessfullyFailed) {
            startedContainers.put("postCommit",
                    createImageForCommit(bu, startedContainers.get("postContainer%s".formatted(attemptCount - 1)),
                            "post"));
            startedContainers.put("prevCommit",
                    createImageForCommit(bu, startedContainers.get("prevContainer%s".formatted(prevAttemptCount - 1)),
                            "pre"));
            resultManager.storeResult(bu, startedContainers.get("postCommit"), startedContainers.get("prevCommit"),
                    failureCategory, javaVersion);
            removeContainers(bu, startedContainers.values());
            removeImages(bu, List.of("base", "pre", "post"));
            return;
        }
        resultManager.saveUnsuccessfulReproductionResult(bu, javaVersion);
        resultManager.storeLogFile(bu, startedContainers.get("postContainer%s".formatted(attemptCount)), false);
        removeContainers(bu, startedContainers.values());
        removeImages(bu, List.of("base"));
    }

    /**
     * Identify the Java version used in the CI for the given breaking update.
     * This method is a placeholder and should be implemented to extract the Java
     * version
     * from the CI logs or configuration.
     *
     * @param bu the breaking update for which to identify the Java version.
     */
    private String identifyJavaVersionFromCI(BreakingUpdate bu, ResultManager resultManager) {
        log.info("Identifying Java version used in CI for breaking update {}", bu.breakingCommit);
        JavaVersionParser javaVersionParser = new JavaVersionParser(bu, resultManager);
        String javaVersions = javaVersionParser.parseJavaVersion();
        if (javaVersions.isEmpty()) {
            log.warn("No Java versions found in CI for breaking update {}", bu.breakingCommit);
        }
        // else {
        // log.info("Identified Java versions for breaking update {}: {}",
        // bu.breakingCommit, javaVersions);
        // }
        return javaVersions;
    }

    /**
     * Remove the containers created during the reproduction of the breaking update
     */
    private void removeContainers(BreakingUpdate bu, Collection<String> startedContainers) {
        log.info("Removing containers for breaking update {}", bu.breakingCommit);
        for (String containerId : startedContainers)
            client.removeContainerCmd(containerId).exec();
    }

    /**
     * Remove unwanted images created in intermediate steps when storing results for
     * the breaking update
     **/
    private void removeImages(BreakingUpdate bu, List<String> extraTags) {
        for (String tag : extraTags) {
            client.removeImageCmd(bu.breakingCommit + ":" + tag).exec();
        }
    }

    /**
     * Start a container for the given breaking update with a specific command
     */
    private String startContainer(BreakingUpdate bu, String cmd) {
        CreateContainerResponse container = client.createContainerCmd(bu.breakingCommit + ":base")
                .withWorkingDir("/" + bu.project)
                .withCmd("sh", "-c", cmd)
                .exec();
        client.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    /**
     * Command to compile and test the preceding commit of the breaking update
     */
    private static String getPrevCmd(BreakingUpdate bu) {
        return "set -o pipefail && git checkout %s && git checkout HEAD~1 && rm -rf .git && mvn clean test -B | tee %s.log"
                .formatted(bu.breakingCommit, bu.breakingCommit);
    }

    /**
     * Command to compile and test the breaking update
     */
    private static String getPostCmd(BreakingUpdate bu) {
        return "set -o pipefail && git checkout %s && rm -rf .git && mvn clean test -B | tee %s.log"
                .formatted(bu.breakingCommit, bu.breakingCommit);
    }

    /**
     * Command to compile and test the breaking update to be used in the final
     * debloated image
     */
    private static String getCmd() {
        return "mvn clean test -B";
    }

    /**
     * Ensure that the maven docker image we use as a base exists
     */
    public void ensureBaseMavenImageExists() throws InterruptedException {
        try {
            client.inspectImageCmd(BASE_IMAGE).exec();
        } catch (NotFoundException e) {
            log.info("Base image not present, pulling {}", BASE_IMAGE);
            client.pullImageCmd(BASE_IMAGE)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            log.info("Done pulling Maven image");
        }
    }

    /**
     * Create a new base docker image for the given breaking update
     **/
    private void createBaseImageForBreakingUpdate(BreakingUpdate bu, String javaVersion) throws InterruptedException {
        log.info("Creating docker image for breaking update {}", bu.breakingCommit);
        String projectUrl = bu.url.replaceAll("/pull/\\d+", "");

        // define base image based on the Java version if provided
        if (javaVersion != null && !javaVersion.isEmpty()) {
            BASE_IMAGE = JavaVersionParser.baseImage(javaVersion);
        }

        ensureBaseMavenImageExists();

        CreateContainerResponse container = client.createContainerCmd(BASE_IMAGE)
                .withCmd("/bin/sh", "-c", "git clone " + projectUrl +
                        " && cd " + bu.project + " && git fetch --depth 2 origin " + bu.breakingCommit)
                .exec();
        client.startContainerCmd(container.getId()).exec();
        WaitContainerResultCallback waitResult = client.waitContainerCmd(container.getId())
                .exec(new WaitContainerResultCallback());
        if (waitResult.awaitStatusCode().intValue() != EXIT_CODE_OK) {
            log.warn("Could not create docker image for breaking update {}", bu.breakingCommit);
            throw new RuntimeException(waitResult.toString());
        }
        client.commitCmd(container.getId())
                .withRepository(bu.breakingCommit)
                .withTag("base").exec();
        log.info("Created docker image for breaking update {}", bu.breakingCommit);

        client.removeContainerCmd(container.getId()).exec();
    }

    /**
     * Create new docker images for the previous and post commits of the given
     * breaking update
     **/
    private String createImageForCommit(BreakingUpdate bu, String containerId, String extraTag) {
        client.commitCmd(containerId).withRepository(bu.breakingCommit).withTag(extraTag).exec();
        CreateContainerResponse container = client.createContainerCmd(bu.breakingCommit + ":" + extraTag)
                .withWorkingDir("/" + bu.project)
                .withCmd("sh", "-c", getCmd())
                .exec();
        return container.getId();
    }
}
