package ml.northwestwind;

import org.apache.commons.io.FileUtils;
import org.fusesource.jansi.Ansi;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;

public class Profile {
    private static final JSONParser parser = new JSONParser();

    public static void run(String[] args) {
        String cmd = args[1];
        if (args.length < 3) {
            if (cmd.equalsIgnoreCase("create")) create();
            else Utils.invalid();
            return;
        }
        args = Arrays.stream(args).skip(2).toArray(String[]::new);
        if (cmd.equalsIgnoreCase("delete")) delete(args);
        else if (cmd.equalsIgnoreCase("add")) add(args);
        else if (cmd.equalsIgnoreCase("remove")) remove(args);
        else if (cmd.equalsIgnoreCase("export")) export(args);
        else Utils.invalid();
    }

    private static void create() {
        Scanner scanner = new Scanner(System.in);
        System.out.println(Ansi.ansi().fg(Ansi.Color.CYAN).a("Creating modpack profile... You will need to answer a few questions.").reset());
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Please enter the name of the profile.").reset());
        String name = scanner.nextLine();
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Please enter the Minecraft version of the profile.").reset());
        String mcVer = null;
        while (mcVer == null) {
            mcVer = scanner.nextLine();
            if (!Utils.isMCVersionValid(mcVer)) {
                mcVer = null;
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("The version is invalid. Please enter the Minecraft version of the profile again.").reset());
            }
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Please enter the mod launcher of the profile. [forge/fabric]").reset());
        String launcher = null;
        while (launcher == null) {
            launcher = scanner.nextLine().toLowerCase();
            if (!launcher.equalsIgnoreCase("forge") && !launcher.equalsIgnoreCase("fabric")) {
                launcher = null;
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("The launcher is invalid. Please enter the mod launcher of the profile again.").reset());
            }
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Please enter the version of the mod launcher.").reset());
        String modVer = scanner.nextLine();
        File profile = new File(Config.profileDir.getPath() + File.separator + name);
        if (profile.exists()) {
            System.out.println(name + " already exists.");
            return;
        }
        profile.mkdir();
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("mcVer", mcVer);
        json.put("modVer", modVer);
        json.put("launcher", launcher);
        try {
            PrintWriter pw = new PrintWriter(profile.getPath() + File.separator + "profile.json");
            pw.write(json.toJSONString());

            pw.flush();
            pw.close();
            System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Created profile " + name));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void delete(String[] names) {
        List<String> profiles = Config.loadProfiles();
        List<String> folders = new ArrayList<>();
        for (String name : names) {
            try {
                if (!profiles.contains(name)) throw new Exception("Cannot find profile with name " + name);
                folders.add(name);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (folders.size() < 1) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Cannot find profile to delete."));
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profiles we are going to delete:").reset());
        folders.forEach((s) -> System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(s).reset()));
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Are you sure you want to delete these profiles? [y/n]").reset());
        Scanner scanner = new Scanner(System.in);
        String res = scanner.nextLine();
        if (!res.equalsIgnoreCase("y")) {
            System.out.println("Cancelled profile deletion.");
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Deleting " + folders.size() + " profiles...").reset());
        AtomicInteger failed = new AtomicInteger();
        folders.forEach((s) -> {
            try {
                FileUtils.deleteDirectory(new File(Config.profileDir.getPath() + File.separator + s));
            } catch (Exception e) {
                e.printStackTrace();
                failed.getAndIncrement();
            }
        });
        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a(String.format("%d success, %d failed", folders.size() - failed.get(), failed.get())).reset());
    }

    private static void add(String[] ids) {
        String profile = ids[0];
        List<String> profiles = Config.loadProfiles();
        if (!profiles.contains(profile)) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profile " + profile + " does not exist."));
            return;
        }
        File profileConfig = new File(Config.profileDir.getPath() + File.separator + profile + File.separator + "profile.json");
        if (!profileConfig.exists() || !profileConfig.isFile()) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profile config is missing!"));
            return;
        }
        JSONObject config;
        try {
            config = (JSONObject) parser.parse(new FileReader(profileConfig));
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            return;
        }
        File modsFolder = new File(Config.profileDir.getPath() + File.separator + profile + File.separator + "mods");
        if (!modsFolder.exists() || !modsFolder.isDirectory()) modsFolder.mkdir();
        Map<Integer, Map.Entry<Integer, String>> mods = Config.loadMods(profile);
        for (String id : Arrays.stream(ids).skip(1).toArray(String[]::new)) {
            try {
                if (!Utils.isInteger(id)) throw new Exception("Mod ID is invalid: "+id);
                JSONObject json = (JSONObject) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + id);
                if (json == null || ((long) ((JSONObject) json.get("categorySection")).get("gameCategoryId")) != 6) throw new Exception("The ID "+id+" does not represent a mod.");
                JSONArray files = (JSONArray) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + id + "/files");
                files.sort((a, b) -> (int) (((long) ((JSONObject) a).get("id")) - ((long) ((JSONObject) b).get("id"))));
                List f = (List) files.stream().filter(o -> {
                    JSONArray versions = (JSONArray) ((JSONObject) o).get("gameVersion");
                    return versions.get(0).equals(config.get("mcVer")) && ((String) versions.get(1)).equalsIgnoreCase((String) config.get("launcher"));
                }).collect(Collectors.toList());
                if (f.size() < 1) throw new Exception("No available file of " + id + " found for this profile.");
                JSONObject bestFile = (JSONObject) Utils.getLast(f);
                String downloadUrl = ((String) bestFile.get("downloadUrl")).replaceFirst("edge", "media");
                if (mods.containsKey(Integer.parseInt(id))) new File(modsFolder.getPath() + File.separator + mods.get(Integer.parseInt(id)).getValue() + "_" + id + "_" + mods.get(Integer.parseInt(id)).getKey() + ".jar").delete();
                String loc = Utils.downloadFile(downloadUrl, modsFolder.getPath(), ((String) bestFile.get("fileName")).replace(".jar", "_" + id + "_" + bestFile.get("id") + ".jar"));
                System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Downloaded " + loc));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Finished all mod downloads."));
    }

    private static void remove(String[] ids) {
        String profile = ids[0];
        List<String> profiles = Config.loadProfiles();
        if (!profiles.contains(profile)) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profile " + profile + " does not exist."));
            return;
        }
        Map<Integer, Map.Entry<Integer, String>> mods = Config.loadMods(profile);
        Map<String, String> files = new HashMap<>();
        for (String id : Arrays.stream(ids).skip(1).toArray(String[]::new)) {
            String name = null, modName = null;
            try {
                if (Utils.isInteger(id)) {
                    Map.Entry<Integer, String> entry = mods.getOrDefault(Integer.parseInt(id), new Utils.NullEntry<>());
                    String file = entry.getValue();
                    if (file == null) throw new Exception("Cannot find mod with ID " + id);
                    name = file + "_" + id + "_" + entry.getKey();
                    modName = file;
                } else {
                    for (Map.Entry<Integer, Map.Entry<Integer, String>> entry : mods.entrySet()) if (entry.getValue().getValue().equalsIgnoreCase(id)) {
                        name = entry.getValue().getValue() + "_" + entry.getKey() + "_" + entry.getValue().getKey();
                        modName = entry.getValue().getValue();
                        break;
                    }
                    if (name == null) throw new Exception("Cannot find mod with name " + id);
                }
                files.put(name + ".jar", modName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (files.size() < 1) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Cannot find mods to remove."));
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Mods we are going to remove:").reset());
        files.forEach((key, value) -> System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(value).reset()));
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Are you sure you want to remove these mods? [y/n]").reset());
        Scanner scanner = new Scanner(System.in);
        String res = scanner.nextLine();
        if (!res.equalsIgnoreCase("y")) {
            System.out.println("Cancelled mod removal.");
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Removing " + files.size() + " mods...").reset());
        AtomicInteger failed = new AtomicInteger();
        files.forEach((key, value) -> {
            try {
                new File(Config.profileDir + File.separator + profile + File.separator + "mods" + File.separator + key).delete();
            } catch (Exception e) {
                e.printStackTrace();
                failed.getAndIncrement();
            }
        });
        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a(String.format("%d success, %d failed", files.size() - failed.get(), failed.get())).reset());
    }

    private static void export(String[] args) {
        String profile = args[0];
        if (!Config.loadProfiles().contains(profile)) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profile " + profile + " does not exist."));
            return;
        }
        File profileConfig = new File(Config.profileDir.getPath() + File.separator + profile + File.separator + "profile.json");
        if (!profileConfig.exists() || !profileConfig.isFile()) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Profile config is missing!"));
            return;
        }
        JSONObject config;
        try {
            config = (JSONObject) parser.parse(new FileReader(profileConfig));
        } catch (ParseException | IOException e) {
            e.printStackTrace();
            return;
        }
        Map<Integer, Map.Entry<Integer, String>> mods = Config.loadMods(profile);
        JSONArray files = new JSONArray();
        for (Map.Entry<Integer, Map.Entry<Integer, String>> mod : mods.entrySet()) {
            JSONObject jo = new JSONObject();
            jo.put("projectID", mod.getKey());
            jo.put("fileID", mod.getValue().getKey());
            jo.put("required", true);
            files.add(jo);
        }
        System.out.println("What is the version of this export?");
        Scanner scanner = new Scanner(System.in);
        String version = scanner.nextLine();
        JSONObject mjo = new JSONObject();
        mjo.put("files", files);
        mjo.put("author", "");
        mjo.put("version", version);
        mjo.put("name", config.get("name"));
        mjo.put("manifestVersion", 1);
        mjo.put("manifestType", "minecraftModpack");
        JSONObject minejo = new JSONObject();
        minejo.put("version", config.get("mcVer"));
        JSONArray loaders = new JSONArray();
        JSONObject loader = new JSONObject();
        loader.put("id", config.get("launcher") + "-" + config.get("modVer"));
        loader.put("primary", true);
        loaders.add(loader);
        minejo.put("modLoaders", loaders);
        mjo.put("minecraft", minejo);
        File manifest = new File(Config.profileDir.getPath() + File.separator + profile + File.separator + "manifest.json");
        try {
            PrintWriter pw = new PrintWriter(manifest.getPath());
            pw.write(mjo.toJSONString());

            pw.flush();
            pw.close();
        } catch (Exception e) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Failed to create manifest.json."));
            e.printStackTrace();
            return;
        }
        List<String> overridesDir = new ArrayList<>(), overridesFile = new ArrayList<>();
        for (int ii = 1; ii < args.length; ii++) {
            String path = Config.profileDir.getPath() + File.separator + profile + File.separator + args[ii];
            File f = new File(path);
            if (!f.exists()) continue;
            if (f.isDirectory()) overridesDir.add(path);
            else overridesFile.add(path);
        }
        File overridesFolder = new File(Config.profileDir.getPath() + File.separator + profile + File.separator + "overrides");
        if (overridesFolder.exists() && overridesFolder.isDirectory()) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Overrides folder already exists. There might be content in it, so it is best to take it away first."));
            return;
        }
        overridesFolder.mkdir();
        try {
            for (String dir : overridesDir) FileUtils.copyDirectoryToDirectory(new File(dir), overridesFolder);
            for (String file : overridesFile) FileUtils.copyFileToDirectory(new File(file), overridesFolder);
        } catch (Exception e) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Failed to copy files to overrides."));
            e.printStackTrace();
            return;
        }
        String zipName = config.get("name") + "-" + version + ".zip";
        try {
            FileOutputStream fos = new FileOutputStream(Config.exportDir.getPath() + File.separator + zipName);
            ZipOutputStream zipOut = new ZipOutputStream(fos);

            Utils.zip(overridesFolder, overridesFolder.getName(), zipOut);
            Utils.zip(manifest, manifest.getName(), zipOut);
            zipOut.close();
            fos.close();
        } catch (Exception e) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Failed to zip everything."));
            e.printStackTrace();
            return;
        }
        try {
            manifest.delete();
            FileUtils.deleteDirectory(overridesFolder);
        } catch (Exception e) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Failed to clean up, but we can keep running."));
            e.printStackTrace();
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Exported profile " + profile + " to " + zipName));
    }

    public static void printHelp(String prefix) {
        System.out.println(prefix + "profile: Commands for custom profiles.");
        System.out.println(prefix + "\tcreate: Create a profile.");
        System.out.println(prefix + "\tdelete: Delete a profile.");
        System.out.println(prefix + "\t\targ <name>: Name of the profile.");
        System.out.println(prefix + "\texport: Export a profile into zip.");
        System.out.println(prefix + "\t\targ <name>: Name of the profile.");
        System.out.println(prefix + "\t\targ <version>: Version of the exported profile.");
        System.out.println(prefix + "\t\targ <launcherVer>: The version of the mod launcher.");
        System.out.println(prefix + "\t\targ [folders|files]: Folders or files to put in overrides.");
        System.out.println(prefix + "\tadd: Add a mod to the profile.");
        System.out.println(prefix + "\t\targ <profile>: Name of the profile to target.");
        System.out.println(prefix + "\t\targ <ID>: The ID of the mod.");
        System.out.println(prefix + "\tremove: Remove a mod from the profile.");
        System.out.println(prefix + "\t\targ <profile>: Name of the profile to target.");
        System.out.println(prefix + "\t\targ <ID>: The ID of the mod.");
    }
}