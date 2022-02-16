package ml.northwestwind;

import org.apache.commons.io.FileUtils;
import org.fusesource.jansi.Ansi;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Modpack {
    private static final JSONParser parser = new JSONParser();
    public static void run(String[] args) {
        String cmd = args[1];
        if (args.length < 3) {
            if (cmd.equalsIgnoreCase("list")) list();
            else Utils.invalid();
            return;
        }
        args = Arrays.stream(args).skip(2).toArray(String[]::new);
        if (cmd.equalsIgnoreCase("install")) install(args);
        else if (cmd.equalsIgnoreCase("update")) update(args);
        else if (cmd.equalsIgnoreCase("repair")) repair(args);
        else if (cmd.equalsIgnoreCase("delete")) delete(args);
        else if (cmd.equalsIgnoreCase("convert")) convert(args);
        else Utils.invalid();
    }

    private static void install(String[] ids) {
        for (String id : ids) {
            if (!Utils.isInteger(id)) {
                System.err.println(id + " is not a valid modpack ID!");
                continue;
            }
            try {
                JSONObject json = (JSONObject) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + id);
                if (json == null || ((long) ((JSONObject) json.get("categorySection")).get("gameCategoryId")) != 4471) throw new Exception("The ID "+id+" does not represent a modpack.");
                String slug = (String) json.get("slug");
                String name = (String) json.get("name");
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Installing ").a(name).a("...").reset());
                File packFolder = new File(Config.modpackDir.getPath() + File.separator +slug+"_"+id);
                if (packFolder.exists()) throw new Exception("Found old folder of "+name+". Installation of "+name+" cancelled.");
                packFolder.mkdir();
                JSONObject latest = (JSONObject) Utils.getLast((JSONArray) json.get("latestFiles"));
                String downloadUrl = ((String) latest.get("downloadUrl")).replaceFirst("edge", "media");
                String loc = Utils.downloadFile(downloadUrl, packFolder.getPath());
                if (loc == null) throw new Exception("Failed to download modpack " + name);
                boolean success = Utils.unzip(loc);
                if (!success) throw new Exception("Failed to extract modpack content of "+name);
                File manifest = new File(packFolder + File.separator + getManifestFile(latest));
                if (!manifest.exists()) throw new Exception("Cannot find modpack manifest of "+name);
                JSONObject manifestJson = (JSONObject) parser.parse(new FileReader(manifest));
                copyFromOverride(packFolder.getPath(), (String) manifestJson.get("overrides"));
                downloadMods(packFolder.getPath());
                String thumb = downloadThumb(json);
                genProfile(name, packFolder.getAbsolutePath(), thumb, getModVersion(manifestJson));
                System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Finished download of " + name).reset());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void copyFromOverride(String folder, String overridesName) throws IOException {
        File overrides = new File(folder + File.separator + overridesName);
        if (!overrides.exists()) return;
        FileUtils.copyDirectory(overrides, new File(folder));
        FileUtils.deleteDirectory(overrides);
    }

    public static void downloadMods(String folder) {
        downloadMods(folder, false);
    }

    public static void downloadMods(String folder, boolean force) {
        System.out.println(Ansi.ansi().fg(Ansi.Color.CYAN).a("Starting mod download").reset());
        try {
            File modsFolder = new File(folder + File.separator + "mods");
            if (!modsFolder.exists() || !modsFolder.isDirectory()) modsFolder.mkdir();
            Map<String, String> mods = getAllMods(modsFolder.getAbsolutePath());
            File manifest = new File(folder + File.separator + "manifest.json");
            JSONObject json = (JSONObject) parser.parse(new FileReader(manifest));
            JSONArray array = (JSONArray) json.get("files");
            int i = 0, suc = 0, fai = 0, ski = 0;
            for (Object o : array) {
                String name = "";
                try {
                    JSONObject obj = (JSONObject) o;
                    String project = Long.toString((long) obj.get("projectID"));
                    String file = Long.toString((long) obj.get("fileID"));
                    if (!force && mods.containsKey(project) && mods.get(project).equalsIgnoreCase(file)) ski++;
                    else {
                        JSONArray files = (JSONArray) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + project + "/files");
                        Optional f = files.stream().filter(o1 -> ((JSONObject) o1).get("id").equals(file)).findFirst();
                        if (!f.isPresent()) throw new Exception("Cannot find required file of project " + project);
                        JSONObject j = (JSONObject) f.get();
                        name = (String) j.get("displayName");
                        String downloaded = Utils.downloadFile((String) j.get("downloadUrl"), folder + File.separator + "mods", ((String) j.get("fileName")).replace(".jar", "_" + project + "_" + file + ".jar"));
                        if (downloaded == null) throw new Exception("Failed to download mod " + name);
                        suc++;
                    }
                } catch (Exception e) {
                    fai++;
                    e.printStackTrace();
                } finally {
                    System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a(String.format("[%d/%d] [S/F/S: %d/%d/%d] Downloaded %s", ++i, array.size(), suc, fai, ski, name)));
                }
            }
            System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(String.format("Iterated through %d projects. %d success, %d failed, %d skipped", i, suc, fai, ski)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> getAllMods(String modFolder) {
        Map<String, String> map = new HashMap<>();
        File modsFolder = new File(modFolder);
        if (!modsFolder.exists() || !modsFolder.isDirectory()) return map;
        for (String filename : modsFolder.list()) {
            if (!filename.endsWith(".jar")) continue;
            String[] splitted = filename.replace(".jar", "").split("_");
            String fileId = Utils.getLast(Arrays.asList(splitted));
            if (!Utils.isInteger(fileId)) continue;
            String[] splitted1 = Arrays.stream(Arrays.copyOf(splitted, splitted.length - 1)).toArray(String[]::new);
            String id = Utils.getLast(Arrays.asList(splitted1));
            if (!Utils.isInteger(id)) continue;
            map.put(id, fileId);
        }
        return map;
    }

    // Pass in latestFiles' last element
    private static String getManifestFile(JSONObject latest) {
        if (latest.containsKey("modules")) {
            JSONArray modules = (JSONArray) latest.get("modules");
            Optional optional = modules.stream().filter(obj -> ((long) ((JSONObject) obj).getOrDefault("type", 0)) == 3).findFirst();
            if (optional.isPresent()) return (String) ((JSONObject) optional.get()).get("foldername");
        }
        return "manifest.json";
    }

    // Get mod loader version from manifest
    private static String getModVersion(JSONObject manifest) {
        if (manifest.containsKey("minecraft")) {
            JSONObject minecraft = (JSONObject) manifest.get("minecraft");
            if (minecraft.containsKey("version") && minecraft.containsKey("modLoaders")) {
                JSONArray modLoaders = (JSONArray) minecraft.get("modLoaders");
                Optional optional = modLoaders.stream().filter(obj -> (boolean) ((JSONObject) obj).getOrDefault("primary", false)).findFirst();
                if (optional.isPresent()) {
                    String version = (String) minecraft.get("version");
                    String id = (String) ((JSONObject) optional.get()).get("id");
                    String[] splitted = id.split("-");
                    String loader = splitted[0];
                    String modVer = Arrays.stream(splitted).skip(1).collect(Collectors.joining("-"));
                    if (loader.equalsIgnoreCase("forge")) id = version + "-forge-" + modVer;
                    else if (loader.equalsIgnoreCase("fabric")) id = "fabric-loader-" + modVer + "-" + version;
                    else id = null;
                    return id;
                }
            }
        }
        return null;
    }

    private static String downloadThumb(JSONObject json) {
        if (!(json.get("attachments") instanceof JSONArray) || !(((JSONArray) json.get("attachments")).get(0) instanceof JSONObject)) return null;
        JSONObject attachment = (JSONObject) ((JSONArray) json.get("attachments")).get(0);
        if (!attachment.containsKey("url")) return null;
        try {
            if (!Config.tempDir.exists()) Config.tempDir.mkdir();
            return Utils.downloadFile((String) attachment.get("url"), Config.tempDir.getAbsolutePath(), "thumbnail.png");
        } catch (Exception ignored) { }
        return null;
    }

    private static void genProfile(String name, String path, String icon, String loader) {
        String base64 = null;
        if (icon != null) {
            try {
                byte[] fileContent = FileUtils.readFileToByteArray(new File(icon));
                base64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(fileContent);
            } catch (Exception ignored) { }
            try { FileUtils.deleteDirectory(Config.tempDir); } catch (Exception ignored) { }
        }
        try {
            File profileFile = new File(Utils.getMinecraftPath() + File.separator + "launcher_profiles.json");
            if (!profileFile.exists() || !profileFile.isFile()) return;
            JSONObject json = (JSONObject) parser.parse(new FileReader(profileFile));
            JSONObject profiles = (JSONObject) json.get("profiles");
            JSONObject profile = new JSONObject();
            profile.put("created", LocalDateTime.now(ZoneId.of("UTC")).toString());
            profile.put("gameDir", path);
            if (base64 == null) profile.put("icon", "Furnace_On");
            else profile.put("icon", base64);
            int memory = (int) Math.ceil(Runtime.getRuntime().maxMemory() / 1024.0 / 1024.0 / 1024.0);
            profile.put("javaArgs", String.format("-Xmx%dG -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M", memory));
            profile.put("lastUsed", LocalDateTime.of(LocalDate.MIN, LocalTime.MIN).atZone(ZoneId.of("UTC")).toString());
            profile.put("lastVersionId", loader == null ? "latest-release" : loader);
            profile.put("name", name);
            profile.put("type", "custom");
            profiles.put(UUID.randomUUID(), profile);
            json.put("profiles", profiles);

            PrintWriter pw = new PrintWriter(profileFile);
            pw.write(json.toJSONString());

            pw.flush();
            pw.close();
            System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Created profile for " + name + ".").reset());
            if (loader == null) {
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(" However, the mod loader is not configured correctly. Please open/restart your Minecraft Launcher to edit it. Installation of mod loader might be needed, and can be downloaded in the following:").reset());
                System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Forge: ").fg(Ansi.Color.CYAN).a("https://files.minecraftforge.net/").reset());
                System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Fabric: ").fg(Ansi.Color.CYAN).a("https://fabricmc.net/use/installer/").reset());
            }
        } catch (Exception ignored) { }
    }

    private static void delete(String[] ids) {
        Map<Integer, String> modpacks = Config.loadModpacks();
        Map<String, String> folders = new HashMap<>();
        for (String id : ids) {
            String name = null, packName = null;
            try {
                if (Utils.isInteger(id)) {
                    String slug = modpacks.getOrDefault(Integer.parseInt(id), null);
                    if (slug == null) throw new Exception("Cannot find modpack with ID " + id);
                    name = slug + "_" + id;
                    packName = slug;
                } else {
                    for (Map.Entry<Integer, String> entry : modpacks.entrySet()) if (entry.getValue().equalsIgnoreCase(id)) {
                        name = entry.getValue() + "_" + entry.getKey();
                        packName = entry.getValue();
                        break;
                    }
                    if (name == null) throw new Exception("Cannot find modpack with name " + id);
                }
                folders.put(name, packName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (folders.size() < 1) {
            System.err.println(Ansi.ansi().fg(Ansi.Color.RED).a("Cannot find modpacks to delete."));
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Modpacks we are going to delete:").reset());
        folders.forEach((key, value) -> System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(value).reset()));
        System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("Are you sure you want to delete these modpacks? [y/n]").reset());
        Scanner scanner = new Scanner(System.in);
        String res = scanner.nextLine();
        if (!res.equalsIgnoreCase("y")) {
            System.out.println("Cancelled modpack deletion.");
            return;
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Deleting " + folders.size() + " modpacks...").reset());
        AtomicInteger failed = new AtomicInteger();
        folders.forEach((key, value) -> {
            try {
                FileUtils.deleteDirectory(new File(Config.modpackDir.getPath() + File.separator + key));
            } catch (Exception e) {
                e.printStackTrace();
                failed.getAndIncrement();
            }
        });
        System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a(String.format("%d success, %d failed", folders.size() - failed.get(), failed.get())).reset());
    }

    private static void list() {
        Map<Integer, String> modpacks = Config.loadModpacks();
        if (modpacks.isEmpty()) {
            System.out.println(Ansi.ansi().fg(Ansi.Color.RED).a("No modpack installed.").reset());
            return;
        }
        List<String> modpack = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : modpacks.entrySet()) {
            String folderName = entry.getValue() + "_" + entry.getKey();
            File manifest = new File(Config.modpackDir.getPath() + File.separator + folderName + File.separator + "manifest.json");
            if (!manifest.exists() || !manifest.isFile()) continue;
            try {
                JSONObject json = (JSONObject) parser.parse(new FileReader(manifest));
                String name = (String) json.get("name");
                String version = (String) json.get("version");
                String mcVer = (String) ((JSONObject) json.get("minecraft")).get("version");
                modpack.add(String.format("%s | %s | %s", name, version, mcVer));
            } catch (Exception ignored) { }
        }
        System.out.println(Ansi.ansi().fg(Ansi.Color.CYAN).a("Installed modpacks:"));
        modpack.forEach(s -> System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a(s)));
        System.out.println(modpack.size() + " modpacks in total.");
    }

    private static void update(String[] ids) {
        Map<Integer, String> modpacks = Config.loadModpacks();
        for (String id : ids) {
            try {
                String slug = null, key = null;
                if (Utils.isInteger(id)) {
                    String ss = modpacks.getOrDefault(Integer.parseInt(id), null);
                    if (ss == null) throw new Exception("Cannot find modpack with ID " + id);
                    slug = ss;
                    key = id;
                } else {
                    for (Map.Entry<Integer, String> entry : modpacks.entrySet()) if (entry.getValue().equalsIgnoreCase(id)) {
                        slug = entry.getValue();
                        key = entry.getKey().toString();
                        break;
                    }
                    if (slug == null) throw new Exception("Cannot find modpack with name " + id);
                }
                JSONObject json = (JSONObject) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + key);
                if (json == null || ((long) ((JSONObject) json.get("categorySection")).get("gameCategoryId")) != 4471) throw new Exception("The ID "+id+" does not represent a modpack.");
                String name = (String) json.get("name");
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Updating ").a(name).a("...").reset());
                File packFolder = new File(Config.modpackDir.getPath() + File.separator +slug+"_"+key);
                JSONObject latest = (JSONObject) Utils.getLast((JSONArray) json.get("latestFiles"));
                String downloadUrl = ((String) latest.get("downloadUrl")).replaceFirst("edge", "media");
                String loc = Utils.downloadFile(downloadUrl, packFolder.getPath());
                if (loc == null) throw new Exception("Failed to download modpack " + name);
                boolean success = Utils.unzip(loc);
                if (!success) throw new Exception("Failed to extract modpack content of "+name);
                File manifest = new File(packFolder + File.separator + "manifest.json");
                if (!manifest.exists()) throw new Exception("Cannot find modpack manifest of "+name);
                FileUtils.cleanDirectory(new File(packFolder.getPath() + File.separator + "mods"));
                copyFromOverride(packFolder.getPath(), (String) ((JSONObject) parser.parse(new FileReader(manifest))).get("overrides"));
                downloadMods(packFolder.getPath());
                System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Finished download of " + name).reset());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void repair(String[] ids) {
        Map<Integer, String> modpacks = Config.loadModpacks();
        for (String id : ids) {
            try {
                String slug = null, key = null;
                if (Utils.isInteger(id)) {
                    String ss = modpacks.getOrDefault(Integer.parseInt(id), null);
                    if (ss == null) throw new Exception("Cannot find modpack with ID " + id);
                    slug = ss;
                    key = id;
                } else {
                    for (Map.Entry<Integer, String> entry : modpacks.entrySet()) if (entry.getValue().equalsIgnoreCase(id)) {
                        slug = entry.getValue();
                        key = entry.getKey().toString();
                        break;
                    }
                    if (slug == null) throw new Exception("Cannot find modpack with name " + id);
                }
                JSONObject json = (JSONObject) Utils.readJsonFromUrl(Constants.CURSEFORGE_API + key);
                if (json == null || ((long) ((JSONObject) json.get("categorySection")).get("gameCategoryId")) != 4471) throw new Exception("The ID "+id+" does not represent a modpack.");
                String name = (String) json.get("name");
                System.out.println(Ansi.ansi().fg(Ansi.Color.YELLOW).a("Repairing ").a(name).a("...").reset());
                File packFolder = new File(Config.modpackDir.getPath() + File.separator +slug+"_"+key);
                JSONObject latest = (JSONObject) Utils.getLast((JSONArray) json.get("latestFiles"));
                String downloadUrl = ((String) latest.get("downloadUrl")).replaceFirst("edge", "media");
                String loc = Utils.downloadFile(downloadUrl, packFolder.getPath());
                if (loc == null) throw new Exception("Failed to download modpack " + name);
                boolean success = Utils.unzip(loc);
                if (!success) throw new Exception("Failed to extract modpack content of "+name);
                File manifest = new File(packFolder + File.separator + "manifest.json");
                if (!manifest.exists()) throw new Exception("Cannot find modpack manifest of "+name);
                FileUtils.cleanDirectory(new File(packFolder.getPath() + File.separator + "mods"));
                copyFromOverride(packFolder.getPath(), (String) ((JSONObject) parser.parse(new FileReader(manifest))).get("overrides"));
                downloadMods(packFolder.getPath(), true);
                System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Finished download of " + name).reset());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void convert(String[] ids) {
        Map<Integer, String> modpacks = Config.loadModpacks();
        for (String id : ids) {
            try {
                String slug = null, key = null;
                if (Utils.isInteger(id)) {
                    String ss = modpacks.getOrDefault(Integer.parseInt(id), null);
                    if (ss == null) throw new Exception("Cannot find modpack with ID " + id);
                    slug = ss;
                    key = id;
                } else {
                    for (Map.Entry<Integer, String> entry : modpacks.entrySet())
                        if (entry.getValue().equalsIgnoreCase(id)) {
                            slug = entry.getValue();
                            key = entry.getKey().toString();
                            break;
                        }
                    if (slug == null) throw new Exception("Cannot find modpack with name " + id);
                }
                File packFolder = new File(Config.modpackDir.getPath() + File.separator +slug+"_"+key);
                File manifest = new File(packFolder.getPath() + File.separator + "manifest.json");
                if (!manifest.exists() || !manifest.isFile()) throw new Exception("Modpack is missing manifest. Cannot convert to profile.");
                FileUtils.moveDirectoryToDirectory(packFolder, Config.profileDir, true);
                packFolder = new File(Config.profileDir.getPath() + File.separator +slug+"_"+key);
                manifest = new File(packFolder.getPath() + File.separator + "manifest.json");
                JSONObject json = (JSONObject) parser.parse(new FileReader(manifest));
                JSONObject profile = new JSONObject();
                JSONObject minecraft = (JSONObject) json.get("minecraft");
                String[] launcher = ((String) ((JSONObject) ((JSONArray) minecraft.get("modLoaders")).get(0)).get("id")).split("-", 2);
                profile.put("name", json.get("name"));
                profile.put("mcVer", minecraft.get("version"));
                profile.put("launcher", launcher[0]);
                profile.put("modVer", launcher[1]);
                PrintWriter pw = new PrintWriter(packFolder.getPath() + File.separator + "profile.json");
                pw.write(profile.toJSONString());

                pw.flush();
                pw.close();
                manifest.delete();
                System.out.println(Ansi.ansi().fg(Ansi.Color.GREEN).a("Converted modpack " + slug + " into profile."));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void printHelp(String prefix) {
        System.out.println(prefix + "modpack: Commands for modpack.");
        System.out.println(prefix + "\tinstall: Install a modpack.");
        System.out.println(prefix + "\t\targ <ID|Slug>: The ID or slug of the modpack.");
        System.out.println(prefix + "\tdelete: Delete a modpack.");
        System.out.println(prefix + "\t\targ <ID|Slug>: The ID or slug of the modpack.");
        System.out.println(prefix + "\tupdate: Update a modpack.");
        System.out.println(prefix + "\t\targ <ID|Slug>: The ID or slug of the modpack.");
        System.out.println(prefix + "\trepair: Repair a modpack.");
        System.out.println(prefix + "\t\targ <ID|Slug>: The ID or slug of the modpack.");
        System.out.println(prefix + "\tlist: List all installed modpacks.");
        System.out.println(prefix + "\tconvert: Convert a modpack into a profile.");
        System.out.println(prefix + "\t\targ <ID|Slug>: The ID or slug of the modpack.");
    }
}
