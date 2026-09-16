package com.mineify.client;

import com.mineify.MineifyClient;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches and caches YouTube thumbnail images as GPU textures, keyed by
 * videoId. Downloads happen off-thread; texture creation and registration
 * happen back on the client thread since GPU resources aren't safe to touch
 * from arbitrary threads. History has no size limit, so this cache evicts
 * (and releases the GPU memory for) its least-recently-used entries once it
 * gets too big, rather than growing forever.
 */
@Environment(EnvType.CLIENT)
public class ThumbnailManager {
    private static final ThumbnailManager INSTANCE = new ThumbnailManager();
    private static final int MAX_CACHED = 150;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Mineify-Thumbnails");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Identifier> textures = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
            if (size() > MAX_CACHED) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    // Once a videoId fails, stop retrying it - get() is called every frame,
    // so without this a persistently-failing URL (bad format, dead link)
    // would get re-fetched from YouTube dozens of times a second forever.
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public static ThumbnailManager getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the registered texture for this video's thumbnail if it's
     * already loaded. Otherwise kicks off an async fetch+decode (unless one
     * is already in flight for this videoId) and returns null for this and
     * every call until it completes - callers should draw a placeholder in
     * that case. Safe to call every frame.
     *
     * The URL is derived here from videoId rather than trusting whatever a
     * caller might have stored - it's a pure function of videoId, and
     * deriving it fresh means entries added before this thumbnail-URL
     * scheme existed (or during the brief window it pointed at the
     * WebP-serving i.ytimg.com URL) still get real art, with no migration
     * needed for old playlist/history data.
     */
    public Identifier get(String videoId) {
        if (videoId == null || videoId.isEmpty() || failed.contains(videoId)) {
            return null;
        }

        Identifier cached = textures.get(videoId);
        if (cached != null) {
            return cached;
        }

        if (inFlight.add(videoId)) {
            executor.submit(() -> load(videoId));
        }
        return null;
    }

    private void load(String videoId) {
        String url = "https://img.youtube.com/vi/" + videoId + "/mqdefault.jpg";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                MineifyClient.LOGGER.warn("Mineify: Thumbnail fetch for {} returned HTTP {}", videoId, response.statusCode());
                markFailed(videoId);
                return;
            }

            // NativeImage.read() demands a PNG signature before it'll even
            // try stb_image's actual decoder, even though stb_image itself
            // handles JPEG fine - YouTube serves JPEG, so it's decoded via
            // ImageIO (always available in the JDK) and re-encoded to PNG
            // in memory first, rather than fighting that gate directly.
            BufferedImage awtImage = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (awtImage == null) {
                MineifyClient.LOGGER.warn("Mineify: Could not decode thumbnail image for {}", videoId);
                markFailed(videoId);
                return;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(awtImage, "png", png);
            NativeImage image = NativeImage.read(png.toByteArray());

            Minecraft.getInstance().execute(() -> {
                DynamicTexture texture = new DynamicTexture(() -> "mineify-thumbnail-" + videoId, image);
                // Identifier paths must be lowercase; videoIds aren't. A
                // collision here would only ever mean two videoIds that are
                // identical except for case sharing a cached texture, which
                // doesn't happen in practice for YouTube's ID scheme.
                Identifier id = Identifier.fromNamespaceAndPath("mineify",
                        "thumbnail/" + videoId.toLowerCase(Locale.ROOT));
                Minecraft.getInstance().getTextureManager().register(id, texture);
                textures.put(videoId, id);
                inFlight.remove(videoId);
            });
        } catch (Exception e) {
            MineifyClient.LOGGER.warn("Mineify: Failed to load thumbnail for {}", videoId, e);
            markFailed(videoId);
        }
    }

    private void markFailed(String videoId) {
        failed.add(videoId);
        inFlight.remove(videoId);
    }
}
