package rqs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import miner.GitPatchCache;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SuccessfulUpdate class represents a dependency update that breaks a GitHub Action workflow.
 *
 * @author <a href="mailto:gabsko@kth.se">Gabriel Skoglund</a>
 */
public class SuccessfulUpdate {

    public final String url;
    public final String project;
    public final String breakingCommit;
    public final String passingCommit;
    public final Integer fixDuration;
    public final Integer changedFilesCount;
    public final String passingPRAuthor;
    public final String passingCommitAuthor;
    public final UpdatedDependency updatedDependency;
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * Create a new PassingUpdate object that stores information about a
     * passing dependency update.
     *
     * @param pr a pull request that corresponds to a passing dependency update.
     */
    public SuccessfulUpdate(GHPullRequest pr, String brCommit, String buDependencyGroupID, String buDependencyArtifactID,
                            String buPreviousVersion, String buDependencyNewVersion) {
        url = pr.getHtmlUrl().toString();
        project = pr.getRepository().getName();
        breakingCommit = brCommit;
        passingCommit = pr.getHead().getSha();
        fixDuration = getFixDuration(pr, breakingCommit);
        changedFilesCount = getChangedFilesCount(pr);
        passingPRAuthor = parsePRAuthorType(pr);
        passingCommitAuthor = parsePassingCommitAuthorType(pr.getRepository(), passingCommit);
        updatedDependency = new UpdatedDependency(pr, buDependencyGroupID, buDependencyArtifactID, buPreviousVersion,
                buDependencyNewVersion);
    }

    /**
     * Constructor for loading a PassingUpdate from a JSON file
     */
    @JsonCreator
    SuccessfulUpdate(@JsonProperty("url") String url,
                     @JsonProperty("project") String project,
                     @JsonProperty("breakingCommit") String breakingCommit,
                     @JsonProperty("passingCommit") String passingCommit,
                     @JsonProperty("fixDuration") Integer fixDuration,
                     @JsonProperty("changedFilesCount") Integer changedFilesCount,
                     @JsonProperty("passingPRAuthor") String passingPRAuthor,
                     @JsonProperty("passingCommitAuthor") String passingCommitAuthor,
                     @JsonProperty("updatedDependency") UpdatedDependency updatedDependency) {
        this.url = url;
        this.project = project;
        this.breakingCommit = breakingCommit;
        this.passingCommit = passingCommit;
        this.fixDuration = fixDuration;
        this.changedFilesCount = changedFilesCount;
        this.passingPRAuthor = passingPRAuthor;
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
            log.error("passingPRAuthorType could not be parsed", e);
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

    // Get the duration to fix the update in days.
    private Integer getFixDuration(GHPullRequest pr, String breakingCommit) {
        try {
            Date brCommitDate = pr.getRepository().getCommit(breakingCommit).getCommitDate();
            Date passingCommitDate = pr.getHead().getCommit().getCommitDate();
            return (int) ((passingCommitDate.getTime() - brCommitDate.getTime()) / (1000 * 60 * 60 * 24));
        } catch (IOException e) {
            log.error("Fix Duration could not be parsed", e);
            return null;
        }
    }

    // Get the number of changed files in the successful pull request.
    public Integer getChangedFilesCount(GHPullRequest pr) {
        try {
            return pr.getChangedFiles();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return ("PassingUpdate{url = %s, project = %s, breakingCommit = %s, passingCommit = %s, passingPRAuthor = %s, " +
                "passingCommitAuthor = %s}").formatted(url, project, breakingCommit, passingCommit, passingPRAuthor,
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
        public final String versionUpdateSimilarity;
        public String higherUpdatePR;

        /**
         * Create updated dependency for the passing update.
         *
         * @param pr the pull request that corresponds to the passing dependency update.
         */
        public UpdatedDependency(GHPullRequest pr, String buDependencyGroupID, String buDependencyArtifactID,
                                 String buPreviousVersion, String buDependencyNewVersion) {
            dependencyGroupID = buDependencyGroupID;
            dependencyArtifactID = buDependencyArtifactID;
            previousVersion = buPreviousVersion;
            newVersion = parsePatch(pr);
            versionUpdateSimilarity = getVersionSimilarity(newVersion, buDependencyNewVersion);
            higherUpdatePR = null;
        }

        /**
         * Constructor for loading an UpdatedDependency of a PassingUpdate from a JSON file
         */
        @JsonCreator
        UpdatedDependency(@JsonProperty("dependencyGroupID") String dependencyGroupID,
                          @JsonProperty("dependencyArtifactID") String dependencyArtifactID,
                          @JsonProperty("previousVersion") String previousVersion,
                          @JsonProperty("newVersion") String newVersion,
                          @JsonProperty("versionUpdateSimilarity") String versionUpdateSimilarity,
                          @JsonProperty("higherUpdatePR") String higherUpdatePR) {
            this.dependencyGroupID = dependencyGroupID;
            this.dependencyArtifactID = dependencyArtifactID;
            this.previousVersion = previousVersion;
            this.newVersion = newVersion;
            this.versionUpdateSimilarity = versionUpdateSimilarity;
            this.higherUpdatePR = higherUpdatePR;
        }

        public void setHigherUpdatePR(String higherUpdatePR) {
            this.higherUpdatePR = higherUpdatePR;
        }

        public String getHigherUpdatePR() {
            return higherUpdatePR;
        }

        /**
         * Attempt to parse the new version from the patch associated with a PR.
         *
         * @param pr the pull request for which to parse the patch.
         * @return The result of the first regex capturing group on the first line where the regex matches, if any.
         * If no match was found, the returned result will be unknown.
         */
        private String parsePatch(GHPullRequest pr) {
            String patch = GitPatchCache.get(pr).orElse("");

            boolean foundGroupId = false;
            boolean foundArtifactId = false;
            boolean insideDependency = false;
            String formattedGrpID = dependencyGroupID.replace(".", "\\.");
            Pattern groupIdPattern = Pattern.compile(String.format("^\\s*<groupId>%s</groupId>\\s*$"
                    , formattedGrpID));
            Pattern artifactIdPattern = Pattern.compile(String.format("^\\s*<artifactId>%s</artifactId>\\s*$"
                    , dependencyArtifactID));
            Pattern versionPattern = Pattern.compile("^\\+\\s*<version>(.*?)</version>(?:\\s*<!--(.*?)-->)?\\s*$");

            for (String line : patch.split("\n")) {

                if (line.contains("<dependency>")) {
                    insideDependency = true;
                }

                if (insideDependency) {
                    if (!foundGroupId) {
                        Matcher groupIdMatcher = groupIdPattern.matcher(line);
                        if (groupIdMatcher.find()) {
                            foundGroupId = true;
                        }
                    }

                    if (!foundArtifactId && foundGroupId) {
                        Matcher artifactIdMatcher = artifactIdPattern.matcher(line);
                        if (artifactIdMatcher.find()) {
                            foundArtifactId = true;
                        }
                    }

                    if (foundGroupId && foundArtifactId) {
                        Matcher versionMatcher = versionPattern.matcher(line);
                        if (versionMatcher.find()) {
                            return versionMatcher.group(1);
                        }
                    }
                }

                if (line.contains("</dependency>")) {
                    insideDependency = false;
                    foundGroupId = false;
                    foundArtifactId = false;
                }
            }
            return "unknown";
        }

        private String getVersionSimilarity(String version1, String version2) {
            ComparableVersion v1 = new ComparableVersion(version1);
            ComparableVersion v2 = new ComparableVersion(version2);
            if (v1.compareTo(v2) < 0) {
                return "lower";
            } else if (v1.compareTo(v2) > 0) {
                return "higher";
            } else {
                return "same";
            }
        }
    }
}
