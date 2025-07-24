package reproducer;

import miner.BreakingUpdate;
import org.kohsuke.github.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JavaVersionParser class is responsible for parsing the Java versions used
 * in the failed jobs of a GitHub workflow.
 * It retrieves the workflow runs for a specific commit and extracts the Java
 * versions from the job logs or workflow files.
 */

public class JavaVersionParser {

    private final BreakingUpdate breakingUpdate;
    private final String projectOrganisation;
    private final String project;
    private final String breakingCommit;
    private final GitHub github;

    public JavaVersionParser(BreakingUpdate breakingUpdate, ResultManager resultManager) {
        this.breakingUpdate = breakingUpdate;
        this.projectOrganisation = breakingUpdate.projectOrganisation;
        this.project = breakingUpdate.project;
        this.breakingCommit = breakingUpdate.breakingCommit;
        this.github = resultManager.getGitHub();
    }

    public String parseJavaVersion() {

        GHRepository repo = null;
        try {
            // Get the commit SHA from the breaking update
            repo = github.getRepository(projectOrganisation + "/" + project);
            // Find all the workflows in the .github/workflows directory
            List<GHContent> workflows = repo.getDirectoryContent(".github/workflows", breakingCommit);
            // List workflow runs for the commit
            PagedIterable<GHWorkflowRun> runs = repo.queryWorkflowRuns()
                    .branch(null)
                    .headSha(breakingCommit)
                    .list();
            FailedJobDetails failedJobDetails = new FailedJobDetails();

            if (runs.toList().isEmpty()) {
                System.out.println("No workflow runs found for commit: " + breakingCommit);
                List<String> versions = parseJavaVersionsFromJobLogsOrFile(null, workflows);
                if (!versions.isEmpty()) {
                    failedJobDetails.getJavaVersions().addAll(versions);
                }
            } else {
                for (GHWorkflowRun run : runs) {
                    // Check if the run is a failure
                    if ("failure".equalsIgnoreCase(String.valueOf(run.getConclusion()))) {
                        FailedJobDetails jobDetails = processFailedJobs(run, workflows);
                        if (!jobDetails.getJavaVersions().isEmpty()) {
                            failedJobDetails.getJavaVersions().addAll(jobDetails.getJavaVersions());
                            failedJobDetails.getFailedJobNames().addAll(jobDetails.getFailedJobNames());
                        }
                    }
                }
            }

            if (!failedJobDetails.getJavaVersions().isEmpty()) {
                String candidates = identifyJavaVersionCandidates(failedJobDetails);
                return candidates;
            } else {
                System.out.println("No Java versions found in any failed jobs.");
                return "11"; // Default to Java 11 if no versions found
            }

        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public String identifyJavaVersionCandidates(FailedJobDetails failedJobDetails) {
        // Check if any Java version appears in the job name
        if (failedJobDetails.getJavaVersions().isEmpty()) {
            System.out.println("No Java versions found in failed jobs.");
            return "";
        }

        System.out.println("Identifying Java version candidates...");
        List<String> candidates = new ArrayList<>();

        for (String jobName : failedJobDetails.getFailedJobNames()) {
            for (String javaVersion : failedJobDetails.getJavaVersions()) {
                if (jobName.contains(javaVersion)) {
                    candidates.add(javaVersion);
                }
            }
        }

        if (!candidates.isEmpty()) {
            return candidates.get(0);
        } else {
            return failedJobDetails.getJavaVersions().get(0);
        }
    }

    /**
     * Parses the Java versions from the job logs or workflow files.
     *
     * @param workflows The list of workflow files.
     * @return A list of Java versions found in the job logs or workflow files.
     */
    private static FailedJobDetails processFailedJobs(GHWorkflowRun run, List<GHContent> workflows) {
        System.out.println("Failed workflow: " + run.getName() + " (ID: " + run.getId() + ")");
        FailedJobDetails details = new FailedJobDetails();

        for (GHWorkflowJob job : run.listJobs()) {
            if ("failure".equalsIgnoreCase(String.valueOf(job.getConclusion()))) {
                System.out.println("  Failed job details:");
                System.out.println("    Name: " + job.getName());
                details.getFailedJobNames().add(job.getName()); // Store failed job name

                List<String> version = parseJavaVersionsFromJobLogsOrFile(job, workflows);
                if (!version.isEmpty()) {
                    details.getJavaVersions().addAll(version); // Store Java versions
                } else {
                    System.out.println("    No Java versions found in logs.");
                }
            }
        }

        if (!details.getJavaVersions().isEmpty()) {
            System.out
                    .println("Java versions found in all failed jobs: " + String.join(", ", details.getJavaVersions()));
        } else {
            System.out.println("No Java versions found in any failed jobs.");
        }

        return details;
    }

    private static List<String> parseJavaVersionsFromJobLogsOrFile(GHWorkflowJob job, List<GHContent> workflows) {
        List<String> javaVersions = new ArrayList<>();
        parseJavaVersionsFromWorkflowFiles(job, workflows, javaVersions);

        return javaVersions;
    }

    private static void parseJavaVersionsFromWorkflowFiles(GHWorkflowJob job, List<GHContent> workflows,
            List<String> javaVersions) {
        for (GHContent workflow : workflows) {
            if (workflow.isFile()) {
                try (InputStream inputStream = workflow.read()) {
                    String fileContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    // if (fileContent.contains(job.getName())) {
                    parseJavaVersions(new ByteArrayInputStream(fileContent.getBytes(StandardCharsets.UTF_8)),
                            javaVersions);
                    // break;
                    // }
                } catch (IOException e) {
                    logError("Error reading workflow file: " + e.getMessage());
                }
            }
        }
    }

    private static void logError(String message) {
        System.err.println(message);
    }

    private static void parseJavaVersions(InputStream inputStream, List<String> javaVersions) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String line;

        // Patterns to match arrays of Java versions
        Pattern[] arrayPatterns = new Pattern[] {
                Pattern.compile("java\\s*:\\s*\\[\\s*([^\\]]+)\\s*\\]", Pattern.CASE_INSENSITIVE),
                Pattern.compile("java-version\\s*:\\s*\\[\\s*([^\\]]+)\\s*\\]", Pattern.CASE_INSENSITIVE)
        };

        // java_version: 21
        Pattern javaVersionKeyPattern = Pattern.compile("java_version\\s*[:=]\\s*['\"]?([^\"'\\s,\\]]+)['\"]?",
                Pattern.CASE_INSENSITIVE);

        // different patterns to match single Java version strings
        Pattern singleVersionPattern = Pattern.compile(
                "(?:java|openjdk) version\\s+\"([^\"]+)\"" +
                        "|java-version\\s*[:=]\\s*['\"]?([^\"'\\s,\\]]+)['\"]?" +
                        "|matrix\\.java\\s*[:=]\\s*['\"]?([^\"'\\s,\\]]+)['\"]?" +
                        "|JAVA_VERSION\\s*[:=]\\s*['\"]?([^\"'\\s,\\]]+)['\"]?",
                Pattern.CASE_INSENSITIVE);

        while ((line = reader.readLine()) != null) {
            // search for array patterns
            // System.out.println("Processing line: " + line);
            for (Pattern pattern : arrayPatterns) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    addVersionsFromArrayString(matcher.group(1), javaVersions);
                }
            }

            // simple class key-value pairs (java_version: "21")
            Matcher keyMatcher = javaVersionKeyPattern.matcher(line);
            if (keyMatcher.find()) {
                String version = keyMatcher.group(1);
                addVersionIfNotPresent(version, javaVersions);
            }

            // This pattern matches various formats of Java version strings
            Matcher singleMatcher = singleVersionPattern.matcher(line);
            while (singleMatcher.find()) {
                for (int i = 1; i <= singleMatcher.groupCount(); i++) {
                    String version = singleMatcher.group(i);
                    if (version != null) {
                        addVersionIfNotPresent(version, javaVersions);
                    }
                }
            }
        }
    }

    private static void addVersionsFromArrayString(String arrayStr, List<String> javaVersions) {
        String[] parts = arrayStr.split(",");
        for (String part : parts) {
            String version = part.replaceAll("['\"\\s]", "");
            addVersionIfNotPresent(version, javaVersions);
        }
    }

    private static void addVersionIfNotPresent(String version, List<String> javaVersions) {
        // Only allow numeric versions (e.g., 8, 11, 17, 21)
        if (version != null && !version.isEmpty() && version.matches("\\d+")) {
            if (!javaVersions.contains(version)) {
                javaVersions.add(version);
            }
        }
    }

    private static class FailedJobDetails {
        private final List<String> javaVersions;
        private final List<String> failedJobNames;

        public FailedJobDetails() {
            this.javaVersions = new ArrayList<>();
            this.failedJobNames = new ArrayList<>();
        }

        public List<String> getJavaVersions() {
            return javaVersions;
        }

        public List<String> getFailedJobNames() {
            return failedJobNames;
        }
    }

    public static String baseImage(String javaVersion) {
        String baseImage = "ghcr.io/chains-project/breaking-updates";
        switch (javaVersion) {
            case "8":
            case "11":
            case "17":
            case "21":
            case "23":
                baseImage += ":base-image-java-" + javaVersion;
                break;
            default:
                System.out.println("Unsupported Java version: " + javaVersion + ". Using default base image");
                baseImage = "ghcr.io/chains-project/breaking-updates:base-image";
        }
        return baseImage;
    }

}
