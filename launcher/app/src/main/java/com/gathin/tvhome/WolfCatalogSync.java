package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WolfCatalogSync {
    static final class Entry {
        final String appId, name, cover;
        Entry(String appId, String name, String cover) {
            this.appId = appId;
            this.name = name;
            this.cover = cover;
        }
    }
    interface Source {
        String hostUuid();
        List<Entry> games() throws WolfControlClient.ControlException;
        byte[] cover(String appId) throws WolfControlClient.ControlException;
    }

    static boolean sync(Source source, File directory) throws IOException, WolfControlClient.ControlException {
        List<Entry> entries = source.games();
        File catalog = MoonlightCatalog.localFile(directory, "catalog.json");
        JSONObject old = null;
        if (catalog.isFile() && catalog.length() <= MoonlightCatalog.MAX_CATALOG_BYTES) {
            try (FileInputStream in = new FileInputStream(catalog); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > MoonlightCatalog.MAX_CATALOG_BYTES) throw new IOException("Catalog too large");
                    out.write(buffer, 0, count);
                }
                old = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception ignored) { old = null; }
        }
        try {
            String hostName = old == null ? "Wolf" : old.optString("host_name", "Wolf");
            MoonlightCatalog.label(hostName);
            JSONArray hosts = old == null ? null : old.optJSONArray("hosts");
            JSONArray games = new JSONArray();
            Set<String> names = new HashSet<>();
            Set<String> referenced = new HashSet<>();
            List<MoonlightCatalog.Game> validated = new ArrayList<>();
            if (entries == null || entries.size() > MoonlightCatalog.MAX_GAMES) throw new IOException("Invalid game count");
            for (Entry entry : entries) {
                String name = MoonlightCatalog.label(entry.name);
                WolfSession.validateId(entry.appId);
                if (!names.add(name)) continue;
                String image = null;
                if (entry.cover != null) {
                    if (!entry.cover.equals(entry.appId + ".png") && !entry.cover.equals(entry.appId + ".jpg")) throw new IOException("Invalid cover");
                    String filename = "wolf-" + entry.cover;
                    File file = MoonlightCatalog.localFile(directory, filename);
                    if (!file.isFile() || file.length() == 0) {
                        try {
                            byte[] bytes = source.cover(entry.appId);
                            if (validImage(bytes, entry.cover.endsWith(".png"))) writeAtomic(file, bytes);
                        } catch (Exception ignored) { /* A missing cover does not block the catalog. */ }
                    }
                    if (file.isFile() && file.length() > 0) {
                        image = filename;
                        referenced.add(filename);
                    }
                }
                validated.add(new MoonlightCatalog.Game(name, entry.appId, image));
                JSONObject game = new JSONObject().put("name", name).put("app_id", entry.appId);
                if (image != null) game.put("image", image);
                games.put(game);
            }
            new MoonlightCatalog(hostName, source.hostUuid(), validated);
            JSONObject result = new JSONObject().put("schema_version", 1).put("host_name", hostName)
                    .put("host_uuid", source.hostUuid());
            if (hosts != null) result.put("hosts", hosts);
            result.put("games", games);
            if (old != null && old.optInt("schema_version") == 1
                    && result.getString("host_uuid").equals(old.optString("host_uuid", ""))
                    && hostName.equals(old.optString("host_name", ""))
                    && same(hosts, old.optJSONArray("hosts")) && same(games, old.optJSONArray("games"))) return false;
            writeAtomic(catalog, result.toString().getBytes(StandardCharsets.UTF_8));
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (file.getName().matches("wolf-.*\\.(png|jpg)") && !referenced.contains(file.getName())) file.delete();
            }
            return true;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid catalog", error);
        }
    }
    private static boolean same(JSONArray a, JSONArray b) {
        return a == null ? b == null : b != null && a.toString().equals(b.toString());
    }
    private static boolean validImage(byte[] bytes, boolean png) {
        byte[] magic = png ? new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
                : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        if (bytes == null || bytes.length < magic.length || bytes.length > 4 * 1024 * 1024) return false;
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i]) return false;
        return true;
    }
    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File temp = File.createTempFile(".wolf-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); }
            if (!temp.renameTo(target)) throw new IOException("Cannot replace file");
        } finally { temp.delete(); }
    }
}
