package ml.northwestwind;

import org.fusesource.jansi.Ansi;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

public class Config {
    public static File directory, modpackDir, profileDir, exportDir;

    public static void run(String[] args) {
        if (args.length < 3) {
            Utils.invalid();
            return;
        }
        if (args[1].equalsIgnoreCase("directory")) setDirectory(Arrays.stream(args).skip(2).collect(Collectors.joining(" ")));
        else Utils.invalid();
    }

    public static void setDirectory(String path) {
        directory = new File(path);
        if (!directory.exists()) directory.mkdir();
        save();
        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Changed directory to " + path));
    }

    public static void printHelp(String prefix) {
        System.out.println(prefix + "config: Configure the CLI program.");
        System.out.println(prefix + "\tdirectory: Set the directory for modpack installation.");
        System.out.println(prefix + "\t\targ <path>: Path to the directory");
    }

    private static void createDefaultConfig() {
        directory = new File("./curseforge-cli");
        if (!directory.exists()) directory.mkdir();
        save();
    }

    public static void load() {
        File config = new File("./cf.json");
        if (!config.exists()) {
            createDefaultConfig();
            return;
        }
        JSONParser parser = new JSONParser();
        try {
            JSONObject json = (JSONObject) parser.parse(new FileReader(config));
            directory = new File((String) json.getOrDefault("directory", "./curseforge-cli"));
            if (!directory.exists() || !directory.isDirectory()) directory.mkdir();
            modpackDir = new File(directory.getPath() + File.separator + "modpack");
            if (!modpackDir.exists() || !modpackDir.isDirectory()) modpackDir.mkdir();
            profileDir = new File(directory.getPath() + File.separator + "profile");
            if (!profileDir.exists() || !profileDir.isDirectory()) profileDir.mkdir();
            exportDir = new File(directory.getPath() + File.separator + "exported");
            if (!exportDir.exists() || !exportDir.isDirectory()) exportDir.mkdir();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public static Map<Integer, String> loadModpacks() {
        File[] files = modpackDir.listFiles();
        Map<Integer, String> map = new HashMap<>();
        for (File file : files) {
            if (file.isFile()) continue;
            String[] splitted = file.getName().split("_");
            String id = Utils.getLast(Arrays.asList(splitted));
            if (!Utils.isInteger(id)) continue;
            String slug = Arrays.stream(Arrays.copyOf(splitted, splitted.length - 1)).collect(Collectors.joining("_"));
            map.put(Integer.parseInt(id), slug);
        }
        return map;
    }

    public static List<String> loadProfiles() {
        File[] files = profileDir.listFiles();
        List<String> list = new ArrayList<>();
        for (File file : files) {
            if (file.isFile()) continue;
            list.add(file.getName());
        }
        return list;
    }

    public static Map<Integer, Map.Entry<Integer, String>> loadMods(String profile) {
        File[] files = new File(profileDir.getPath() + File.separator + profile + File.separator + "mods").listFiles();
        Map<Integer, Map.Entry<Integer, String>> map = new HashMap<>();
        for (File file : files) {
            if (file.isDirectory()) continue;
            String[] splitted = file.getName().replace(".jar", "").split("_");
            String fileId = Utils.getLast(Arrays.asList(splitted));
            if (!Utils.isInteger(fileId)) continue;
            String[] splitted1 = Arrays.stream(Arrays.copyOf(splitted, splitted.length - 1)).toArray(String[]::new);
            String id = Utils.getLast(Arrays.asList(splitted1));
            if (!Utils.isInteger(id)) continue;
            String name = Arrays.stream(Arrays.copyOf(splitted, splitted.length - 1)).collect(Collectors.joining());
            map.put(Integer.parseInt(id), new Map.Entry<Integer, String>() {
                @Override
                public Integer getKey() {
                    return Integer.parseInt(fileId);
                }

                @Override
                public String getValue() {
                    return name;
                }

                @Override
                public String setValue(String value) {
                    return null;
                }
            });
        }
        return map;
    }

    public static void save() {
        try {
            JSONObject jo = new JSONObject();
            jo.put("directory", directory.getPath());
            PrintWriter pw = new PrintWriter("cf.json");
            pw.write(jo.toJSONString());

            pw.flush();
            pw.close();

            if (!directory.exists()) directory.mkdir();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}