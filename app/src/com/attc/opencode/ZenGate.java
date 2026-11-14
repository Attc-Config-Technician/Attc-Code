package com.attc.opencode;

import android.util.Base64;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * The free opencode Zen tier only serves requests that carry the opencode
 * agent system prompt together with its tool schema set. These constants hold
 * that gate prefix so the in-app gateway can wrap Claude Code's own system
 * prompt and tools around it.
 */
final class ZenGate {

    private static final String SYSTEM_GZ =
        "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";

    private static final String TOOLS_GZ =
        "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"
        + "RXaN8OKv0Q7bT/I3ed/21sq7b9Ve0Zo/uFYfPmKPiT43kyl/hLSzKvlK0019ErHrtx+Fvdd/udrljLeSd13PHOlOysF+fpDd"
        + "yKVzf4bus+zWDQ36Bfbcb8ys8k79WFRX+Xweiq79Oyv+SK5VhAt/ag3LlyUyyLN7ZuUyFdoztRYg9b3j23cu7Xe9rqmXHpJK"
        + "XujpK0lX74wLue+Zr61JKiJdOxGXy4qPqSStj48PuYcJBmCBMuf0Xml0xMfxAglPcElw4kunxwRc69LLbK0nbkh/a1oQowBR"
        + "glgZF8W5Xox+7la6g/RJ5CwYzmGVRiwZ97esfW4Ex04xbsgEvou+iq91tfRw8zD0KesD/5nq90nOfuyaQbbeD4Ne9Uk1P/id"
        + "fVPT9TXpyyD9V32uj/iar/TL5px7yEruCy7ocI3nkbQqT/J54PzFBfXt8fHrpqzAR05cdktceqFDuHLKEvSUhXlIKAc9d4ZA"
        + "T1OWi0OvkjRIzNla1mWpYFmlYGedhQF3Ox+WPLWmcu1K+HNAnMza1kuNE3xuZNLIWKWVlPRTbv5zSkv/niQ7Qej0Jylxencq"
        + "nX2ktpNAIa1OuFWQ5ibO2H/LGEjkowYulaGBLolfTZpWtxydOVzztVq8cSpM51T5A0YdhSW7sbMk/kSmX4c0WqSN82O903LC"
        + "6crXo7cOZWOdDgsIEvK5FZ5hDUxOeDBM5DM70nuJe9L4mlPbfH02LOJJ+6Pd+AQquHrCuSlANeCx3Wu+0DrldDnuqubqN/yn"
        + "Vnkd46/4BXR5+vqHkwGujXDlZPB2i6vZt3Ggrs9T25g4FaCf7jF3lI9LuAZOks6XF6ME3x1vfyRoarggsVVAT4PvQ1V1JbfA"
        + "DoQ5beeDwVLSg1250j5HT9yfp7F83GfRfZ83w41xQLhtc05eDbFgoJt8BIuYSBqTsU73j0gpJQl8evuuSn3fdtrsHTO7fS7F"
        + "2/8DWoZLLdWGAAA=";

    private static String system;
    private static JSONArray tools;

    private ZenGate() {
    }

    static synchronized String system() {
        if (system == null) {
            system = inflate(SYSTEM_GZ);
        }
        return system;
    }

    static synchronized JSONArray tools() {
        if (tools == null) {
            try {
                tools = new JSONArray(inflate(TOOLS_GZ));
            } catch (Exception e) {
                tools = new JSONArray();
            }
        }
        return tools;
    }

    private static String inflate(String encoded) {
        try {
            byte[] gz = Base64.decode(encoded, Base64.DEFAULT);
            InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz));
            ByteArrayOutputStream out = new ByteArrayOutputStream(65536);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
