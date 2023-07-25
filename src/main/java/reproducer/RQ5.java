package reproducer;

import com.fasterxml.jackson.databind.type.MapType;
import miner.GitHubAPITokenQueue;
import miner.JsonUtils;
import okhttp3.OkHttpClient;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RQ5 {

    private final GitHubAPITokenQueue tokenQueue;
    private final OkHttpClient httpConnector;
    private final Logger log = LoggerFactory.getLogger(this.getClass());

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
                prState.put("url", prUrl);
                prState.put("status", getPRStatus(pr));
                if (prState.get("status").equals("closed")) {
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

    public static void main(String[] args) throws IOException {
        List<String> apiTokens = Files.readAllLines(Path.of("token.txt"));
        RQ5 rq5 = new RQ5(apiTokens);
        rq5.getPRStates();
    }
}
