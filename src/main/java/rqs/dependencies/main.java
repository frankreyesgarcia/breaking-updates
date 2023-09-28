package rqs.dependencies;

import com.fasterxml.jackson.databind.type.MapType;
import miner.JsonUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class main {

    public static void main(String[] args) {
        main m = new main();
        final var lastPullrequest = m.getLastPullrequest(Path.of("/Users/frank/Documents/Work/PHD/chains-project/fork/breaking-updates-fork/data/benchmark"));

        System.out.println(lastPullrequest.size());
        System.out.println(lastPullrequest);

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
}
