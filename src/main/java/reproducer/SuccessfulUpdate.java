package reproducer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import miner.GitPatchCache;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SuccessfulUpdate class represents a dependency update that breaks a GitHub Action workflow.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class SuccessfulUpdate {

    private static final Pattern DEPENDENCY_ARTIFACT_ID =
            Pattern.compile("^\\s*<artifactId>(.*)</artifactId>\\s*$");
    private static final Pattern DEPENDENCY_GROUP_ID =
            Pattern.compile("^\\s*<groupId>(.*)</groupId>\\s*$");
    private static final Pattern PREVIOUS_VERSION =
            Pattern.compile("^-\\s*<version>(.*?)</version>(?:\\s*<!--(.*?)-->)?\\s*$");
    private static final Pattern NEW_VERSION = Pattern.compile("^\\+\\s*<version>(.*?)</version>(?:\\s*<!--(.*?)-->)?\\s*$");
    public final String url;
    public final String project;
    public final String breakingCommit;
    public final String passingCommit;
    public final String prAuthor;
    public final String passingCommitAuthor;
    public final UpdatedDependency updatedDependency;
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * Create a new PassingUpdate object that stores information about a
     * passing dependency update.
     *
     * @param pr a pull request that corresponds to a passing dependency update.
     */
    public SuccessfulUpdate(GHPullRequest pr, String brCommit) {
        url = pr.getHtmlUrl().toString();
        project = pr.getRepository().getName();
        breakingCommit = brCommit;
        passingCommit = pr.getHead().getSha();
        prAuthor = parsePRAuthorType(pr);
        passingCommitAuthor = parsePassingCommitAuthorType(pr.getRepository(), passingCommit);
        updatedDependency = new UpdatedDependency(pr);
    }

    /**
     * Constructor for loading a PassingUpdate from a JSON file
     */
    @JsonCreator
    SuccessfulUpdate(@JsonProperty("url") String url,
                     @JsonProperty("project") String project,
                     @JsonProperty("breakingCommit") String breakingCommit,
                     @JsonProperty("passingCommit") String passingCommit,
                     @JsonProperty("prAuthor") String prAuthor,
                     @JsonProperty("passingCommitAuthor") String passingCommitAuthor,
                     @JsonProperty("updatedDependency") UpdatedDependency updatedDependency) {
        this.url = url;
        this.project = project;
        this.breakingCommit = breakingCommit;
        this.passingCommit = passingCommit;
        this.prAuthor = prAuthor;
        this.passingCommitAuthor = passingCommitAuthor;
        this.updatedDependency = updatedDependency;
    }

    /**
     * Parse the type of user that made the passing pull request
     *
     * @param pr The pull request to parse
     * @return "bot" if the user is a bot, otherwise return "human".
     */
    private String parsePRAuthorType(GHPullRequest pr) {
        try {
            GHUser user = pr.getUser();
            String userLogin = user.getLogin().toLowerCase();
            // Sometimes, the user type does not get equal to BOT even if the user is actually a bot. Therefore, we add
            // additional checks.
            return user.getType().equals("Bot") || userLogin.contains("dependabot") || userLogin.contains("renovate") ?
                    "bot" : "human";
        } catch (IOException e) {
            log.error("prAuthorType could not be parsed", e);
            return "unknown";
        }
    }

    /**
     * Parse the type of user that made the passing commit
     *
     * @param repository The GitHub repository
     * @param commitSHA  The passing commit to parse
     * @return "bot" if the user is a bot, otherwise return "human".
     */
    private String parsePassingCommitAuthorType(GHRepository repository, String commitSHA) {
        try {
            GHUser user = repository.getCommit(commitSHA).getAuthor();
            String userLogin = user.getLogin().toLowerCase();
            // Sometimes, the user type does not get equal to BOT even if the user is actually a bot. Therefore, we add
            // additional checks.
            return user.getType().equals("Bot") || userLogin.contains("dependabot") || userLogin.contains("renovate") ?
                    "bot" : "human";
        } catch (IOException e) {
            log.error("passingCommitAuthorType could not be parsed", e);
            return "unknown";
        }
    }

    @Override
    public String toString() {
        return ("PassingUpdate{url = %s, project = %s, breakingCommit = %s, passingCommit = %s, prAuthor = %s, " +
                "passingCommitAuthor = %s}").formatted(url, project, breakingCommit, passingCommit, prAuthor,
                passingCommitAuthor);
    }


    /**
     * UpdatedDependency represents information associated with the updated dependency.
     */
    public static class UpdatedDependency {

        public final String dependencyGroupID;
        public final String dependencyArtifactID;
        public final String previousVersion;
        public final String newVersion;

        /**
         * Create updated dependency for the passing update.
         *
         * @param pr the pull request that corresponds to the passing dependency update.
         */
        public UpdatedDependency(GHPullRequest pr) {
            dependencyGroupID = parsePatch(pr, DEPENDENCY_GROUP_ID);
            dependencyArtifactID = parsePatch(pr, DEPENDENCY_ARTIFACT_ID);
            previousVersion = parsePatch(pr, PREVIOUS_VERSION);
            newVersion = parsePatch(pr, NEW_VERSION);
        }

        /**
         * Constructor for loading an UpdatedDependency of a PassingUpdate from a JSON file
         */
        @JsonCreator
        UpdatedDependency(@JsonProperty("dependencyGroupID") String dependencyGroupID,
                          @JsonProperty("dependencyArtifactID") String dependencyArtifactID,
                          @JsonProperty("previousVersion") String previousVersion,
                          @JsonProperty("newVersion") String newVersion) {
            this.dependencyGroupID = dependencyGroupID;
            this.dependencyArtifactID = dependencyArtifactID;
            this.previousVersion = previousVersion;
            this.newVersion = newVersion;
        }

        /**
         * Attempt to parse information from the patch associated with a PR.
         *
         * @param pr         the pull request for which to parse the patch.
         * @param searchTerm a regex describing the data to extract.
         * @return The result of the first regex capturing group on the first line where the regex matches, if any.
         * If no match was found, the default result will be returned instead.
         */
        private String parsePatch(GHPullRequest pr, Pattern searchTerm) {
            String patch = GitPatchCache.get(pr).orElse("");
            for (String line : patch.split("\n")) {
                Matcher matcher = searchTerm.matcher(line);
                if (matcher.find())
                    return matcher.group(1);
            }
            return "unknown";
        }
    }
}
