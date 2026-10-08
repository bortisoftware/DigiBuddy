package es.digimap.thor;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Loads bounded metadata bundled with the app. Contains no game assets. */
final class ProfileLoader {
  static List<GameData.Profile> load(AssetManager assets) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (InputStream in = assets.open("profiles.json")) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (bytes.size() + count > 65536) throw new Exception("Perfiles demasiado grandes");
        bytes.write(buffer, 0, count);
      }
    }
    List<GameData.Profile> profiles = new ArrayList<>();
    JSONArray array = new JSONArray(bytes.toString("UTF-8"));
    for (int i = 0; i < array.length(); i++) {
      JSONObject object = array.getJSONObject(i);
      GameData.Profile profile = new GameData.Profile();
      profile.id = object.getString("id");
      profile.label = object.getString("label");
      profile.addresses = new HashMap<>();
      JSONObject addresses = object.getJSONObject("addresses");
      Iterator<String> keys = addresses.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        int address = addresses.getInt(key);
        if (address < 0 || address >= 2097152)
          throw new Exception("Dirección del perfil no válida");
        profile.addresses.put(key, address);
      }
      JSONArray signatures = object.getJSONArray("signatures");
      for (int j = 0; j < signatures.length(); j++) {
        JSONObject signature = signatures.getJSONObject(j);
        int offset = signature.getInt("offset"), length = signature.getInt("length");
        String hash = signature.getString("sha256");
        if (offset < 0
            || length < 1
            || length > 4096
            || offset > 2097152 - length
            || !hash.matches("[0-9a-f]{64}")) throw new Exception("Huella del perfil no válida");
        profile.signatureOffsets.add(offset);
        profile.signatureLengths.add(length);
        profile.signatureHashes.add(hash);
      }
      profiles.add(profile);
    }
    return profiles;
  }
}
