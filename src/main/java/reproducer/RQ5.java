package reproducer;

import com.fasterxml.jackson.databind.type.MapType;
import miner.GitHubAPITokenQueue;
import miner.GitPatchCache;
import miner.JsonUtils;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RQ5 {

    private final GitHubAPITokenQueue tokenQueue;
    private final OkHttpClient httpConnector;
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final String successfulUpdatesPath = "rq5_results/successful_updates";

    private static final Pattern POM_XML_CHANGE = Pattern.compile("^[+]{3}.*pom.xml$", Pattern.MULTILINE);

    public RQ5(Collection<String> apiTokens) throws IOException {
        // We use OkHttp with a 10 MB cache for HTTP requests
        httpConnector = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        tokenQueue = new GitHubAPITokenQueue(apiTokens);
    }

    public void getPRStates() throws IOException {
        File[] breakingUpdates = Path.of("data/benchmark").toFile().listFiles();
        MapType jsonType = JsonUtils.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
        Path prStatesFilePath = Path.of("pr_states" + JsonUtils.JSON_FILE_ENDING);
        if (Files.notExists(prStatesFilePath)) {
            Files.createFile(prStatesFilePath);
        }
        Map<String, Map<String, String>> prStates = JsonUtils.readFromNullableFile(prStatesFilePath, jsonType);
        if (prStates == null) {
            prStates = new HashMap<>();
        }
        if (breakingUpdates != null) {
            for (File breakingUpdate : breakingUpdates) {
                Map<String, String> prState = new HashMap<>();
                Map<String, Object> bu = JsonUtils.readFromFile(breakingUpdate.toPath(), jsonType);
                String prUrl = (String) bu.get("url");
                String[] urlParts = prUrl.split("/");
                String repoOwner = urlParts[3];
                String repoName = urlParts[4];
                int prNumber = Integer.parseInt(urlParts[6]);
                GitHub github = tokenQueue.getGitHub(httpConnector);
                GHRepository repository = github.getRepository(repoOwner + "/" + repoName);
                GHPullRequest pr = repository.getPullRequest(prNumber);
                GHIssue prAsIssue = repository.getIssue(prNumber);
                Date prDate = pr.getCreatedAt();
                prState.put("url", prUrl);
                prState.put("status", getPRStatus(pr));
                prState.put("failureCategory", (String) bu.get("failureCategory"));
                if (prState.get("status").equals("closed")) {
                    if (Files.notExists(Path.of(successfulUpdatesPath, (String) bu.get("breakingCommit")))) {
                        Map updatedDependency = (Map) bu.get("updatedDependency");
                        retrieveFixCommit(repository, prDate, (String) bu.get("breakingCommit"),
                                (String) updatedDependency.get("dependencyGroupID"),
                                (String) updatedDependency.get("dependencyArtifactID"),
                                (String) updatedDependency.get("previousVersion"));
                    }
                    prState.put("closedBy", parseAuthorType(prAsIssue.getClosedBy()));
                }
                if (prState.get("status").equals("merged")) {
                    prState.put("mergedBy", parseAuthorType(pr.getMergedBy()));
                }
                prStates.put((String) bu.get("breakingCommit"), prState);
                log.info("pr state {}", prState);
            }
        }
        JsonUtils.writeToFile(prStatesFilePath, prStates);
    }

    private String getPRStatus(GHPullRequest pr) throws IOException {
        if (pr.isMerged()) {
            return ("merged");
        }
        if (pr.getState().equals(GHIssueState.CLOSED)) {
            return ("closed");
        }
        if (pr.getState().equals(GHIssueState.OPEN)) {
            return ("open");
        }
        return ("unknown");
    }

    private String parseAuthorType(GHUser user) {
        if (user != null) {
            try {
                String userLogin = user.getLogin().toLowerCase();
                // Sometimes, the user type does not get equal to BOT even if the user is actually a bot. Therefore, we add
                // additional checks.
                return user.getType().equals("Bot") || userLogin.contains("dependabot") || userLogin.contains("renovate") ?
                        "bot" : "human";
            } catch (IOException e) {
                log.error("prAuthorType could not be parsed", e);
            }
        }
        return "unknown";
    }

    private void retrieveFixCommit(GHRepository repository, Date cutoffDate, String breakingCommit, String groupID,
                                   String artifactID, String previousVersion) {
        List<SuccessfulUpdate> sus = new ArrayList<>();
        PagedIterator<GHPullRequest> pullRequests = repository.queryPullRequests()
                .state(GHIssueState.ALL)
                .sort(GHPullRequestQueryBuilder.Sort.CREATED)
                .direction(GHDirection.DESC)
                .list().iterator();
        while (pullRequests.hasNext()) {
            List<GHPullRequest> nextPage = pullRequests.nextPage();
            if (createdBefore(cutoffDate).test(nextPage.get(0))) {
                log.info("Checked all PRs created after " + cutoffDate);
                break;
            }
            nextPage.stream()
                    .takeWhile(createdBefore(cutoffDate).negate())
                    .filter(pr -> changesDependencyVersionInPomXML(pr, groupID, artifactID, previousVersion))
                    .filter(passesBuild)
                    .map(pr -> new SuccessfulUpdate(pr, breakingCommit))
                    .forEach(successfulUpdate -> {
                        log.info("    Found " + successfulUpdate.url);
                        sus.add(successfulUpdate);
                        writeSuccessfulUpdate(successfulUpdate);
                    });
        }
        System.out.println(sus);
    }

    private static final Predicate<GHPullRequest> passesBuild = pr -> {
        GHWorkflowRunQueryBuilder query = pr.getRepository().queryWorkflowRuns()
                .branch(pr.getHead().getRef())
                .event(GHEvent.PULL_REQUEST)
                .status(GHWorkflowRun.Status.COMPLETED);
        List<GHWorkflowRun> successfulRuns;
        List<GHWorkflowRun> failedRuns;
        try {
            successfulRuns = new ArrayList<>(query.conclusion(GHWorkflowRun.Conclusion.SUCCESS).list().toList());
            failedRuns = new ArrayList<>(query.conclusion(GHWorkflowRun.Conclusion.FAILURE).list().toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // The GitHub API somehow returns both Successful and Failure conclusions for some workflow. The possible reason
        // could be that they have both successful and failed checks. Therefore, to retrieve the true successful ones
        // we have to filter again.
        List<GHWorkflowRun> filteredRuns = successfulRuns.stream()
                .filter(run -> failedRuns.stream().noneMatch(failedRun -> failedRun.getHeadSha().equals(run.getHeadSha())))
                .toList();
        // The GitHub REST API allows us to query for the head sha, but this is not currently supported
        // by org.kohsuke.github query builder. To ensure that the run actually failed for this specific
        // PR head, we have to verify it after the search.
        return filteredRuns.stream()
                .anyMatch(run -> run.getHeadSha().equals(pr.getHead().getSha()));
    };

    private static Boolean changesDependencyVersionInPomXML(GHPullRequest pr, String groupID, String artifactID,
                                                            String previousVersion) {
        String patch = GitPatchCache.get(pr).orElse("");
        String formattedGrpID = groupID.replace(".", "\\.");
        String formattedPrevVersion = previousVersion.replace(".", "\\.");
        Pattern dependency_version_change =
                Pattern.compile(String.format("<dependency>(?=.*<groupId>%s</groupId>)(?=.*<artifactId>%s</artifactId>)" +
                                        "(.*^-\\s*<version>%s</version>.*)(.*^[+]\\s*<version>.+</version>.*)</dependency>",
                                formattedGrpID, artifactID, formattedPrevVersion),
                        Pattern.DOTALL | Pattern.MULTILINE);
        if (POM_XML_CHANGE.matcher(patch).find() && dependency_version_change.matcher(patch).find()) {
            return true;
        } else {
            GitPatchCache.remove(pr);
            return false;
        }
    }

    private static Predicate<GHPullRequest> createdBefore(Date cutoffDate) {
        return pr -> {
            Date mergedAt = pr.getMergedAt();
            Date closedAt = pr.getClosedAt();
            try {
                if (mergedAt != null) {
                    return (mergedAt.before(cutoffDate));
                }
                if (closedAt != null) {
                    return (closedAt.before(cutoffDate));
                }
                return pr.getCreatedAt().before(cutoffDate);
            } catch (IOException | NullPointerException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private void writeSuccessfulUpdate(SuccessfulUpdate successfulUpdate) {
        Path path = Path.of(successfulUpdatesPath, successfulUpdate.breakingCommit, successfulUpdate.passingCommit
                + JsonUtils.JSON_FILE_ENDING);
        try {
            Files.createDirectories(path.getParent());
            JsonUtils.writeToFile(path, successfulUpdate);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        List<String> apiTokens = Files.readAllLines(Path.of("token.txt"));
        RQ5 rq5 = new RQ5(apiTokens);
        rq5.getPRStates();
    }
}
