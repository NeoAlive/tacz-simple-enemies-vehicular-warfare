package com.neoalive.tacz_sewv.skin;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import com.neoalive.tacz_sewv.TaczSewv;
import com.neoalive.tacz_sewv.config.SewvConfig;
import com.neoalive.tacz_sewv.crew.PmcIdentityPreference;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketWebLogo;
import com.neoalive.tacz_sewv.platform.PacketDistributor;

/**
 * PMC logos taken from a web link. The server downloads each link once, re-encodes it as a small
 * PNG and keeps it in memory until the server stops; clients only ever receive those bytes.
 *
 * <p>A web logo is the virtual pool {@link #POOL} with icon id {@code w<hash of the link>}, so the
 * helmet, dogtag and terminal code read it through the same pool and icon lookups as a file logo.
 */
@EventBusSubscriber(modid = TaczSewv.MODID)
public final class WebLogoCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String POOL = "pmc_web";
    public static final int MAX_URL = 256;
    /** Side of the stored square; the dogtag samples 16, the helmet 24x14. */
    public static final int SIZE = 64;
    private static final int MAX_DOWNLOAD = 4 * 1024 * 1024;
    private static final int MAX_SOURCE_DIM = 4096;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_ENTRIES = 128;
    private static final Pattern ID = Pattern.compile("^w[0-9a-f]{16}$");

    public static final String ERR_DISABLED = "disabled";
    public static final String ERR_BAD_URL = "bad_url";
    public static final String ERR_BLOCKED = "blocked";
    public static final String ERR_HTTP = "http";
    public static final String ERR_TOO_BIG = "too_big";
    public static final String ERR_FORMAT = "format";
    public static final String ERR_TIMEOUT = "timeout";
    public static final String ERR_BUSY = "busy";

    private record Entry(String url, byte[] png) {
    }

    private static final Map<String, Entry> BY_ID = new ConcurrentHashMap<>();
    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();
    /** Bumped on server stop, so a download that finishes after it is dropped. */
    private static volatile int generation;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sewv-web-logo");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private WebLogoCache() {
    }

    public static boolean enabled() {
        try {
            return SewvConfig.WEB_LOGOS_ENABLED.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static String idFor(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return "w" + HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) {
            return "w" + String.format("%016x", (long) url.hashCode());
        }
    }

    public static boolean has(String id) {
        return BY_ID.containsKey(id);
    }

    @Nullable
    public static InputStream open(String id) {
        Entry e = BY_ID.get(id);
        return e == null ? null : new ByteArrayInputStream(e.png());
    }

    // --- Requests ------------------------------------------------------------

    /** A player pressed Load. The answer goes back to that player only, until they apply it. */
    public static void request(ServerPlayer player, String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.strip();
        if (!enabled()) {
            reply(player, url, ERR_DISABLED);
            return;
        }
        String id = idFor(url);
        Entry cached = BY_ID.get(id);
        if (cached != null) {
            send(PacketDistributor.PLAYER.with(() -> player), cached, id);
            return;
        }
        String bad = checkSyntax(url);
        if (bad != null) {
            reply(player, url, bad);
            return;
        }
        if (!BUSY.add(player.getUUID())) {
            reply(player, url, ERR_BUSY);
            return;
        }
        MinecraftServer server = player.server;
        UUID who = player.getUUID();
        fetch(url).whenComplete((result, err) -> server.execute(() -> {
            BUSY.remove(who);
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p == null) return;
            if (result instanceof Entry e) {
                send(PacketDistributor.PLAYER.with(() -> p), e, idFor(e.url()));
            } else {
                reply(p, url, result instanceof String reason ? reason : ERR_HTTP);
            }
        }));
    }

    /** After an Apply that picked a web logo: every player may now see it on a helmet or hull. */
    public static void publish(String id) {
        Entry e = BY_ID.get(id);
        if (e != null) send(PacketDistributor.ALL.noArg(), e, id);
    }

    private static void reply(ServerPlayer player, String url, String reason) {
        NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                PacketWebLogo.error(url, reason));
    }

    private static void send(PacketDistributor.PacketTarget target, Entry e, String id) {
        NetworkHandler.CHANNEL.send(target, PacketWebLogo.ok(e.url(), id, e.png()));
    }

    // --- Lifecycle -----------------------------------------------------------

    /** Download every link already in use, once, so nothing is fetched while playing. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!enabled()) return;
        MinecraftServer server = event.getServer();
        Map<UUID, PmcIdentityPreference.PmcIdentity> all = PmcIdentityPreference.all(server);
        Set<String> urls = new HashSet<>();
        for (PmcIdentityPreference.PmcIdentity identity : all.values()) {
            if (POOL.equals(identity.logoPool()) && !identity.logoUrl().isEmpty()) urls.add(identity.logoUrl());
        }
        for (String url : urls) {
            if (checkSyntax(url) != null) continue;
            fetch(url).whenComplete((result, err) -> server.execute(() -> {
                if (!(result instanceof Entry e)) {
                    LOGGER.warn("[sewv-web-logo] could not load {}: {}", url, result != null ? result : err);
                    return;
                }
                String id = idFor(e.url());
                PmcLogoEncoder.invalidateCache();
                publish(id);
                for (Map.Entry<UUID, PmcIdentityPreference.PmcIdentity> owner : all.entrySet()) {
                    if (!id.equals(owner.getValue().logoId())) continue;
                    ServerPlayer p = server.getPlayerList().getPlayer(owner.getKey());
                    if (p == null) continue;
                    HelmetLogoSupport.restampOwned(p);
                    PmcVehicleLogoSupport.restampOwned(p);
                }
            }));
        }
        if (!urls.isEmpty()) LOGGER.info("[sewv-web-logo] loading {} web logo(s)", urls.size());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        generation++;
        BY_ID.clear();
        BUSY.clear();
    }

    /** A joining player gets every logo someone has applied. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Set<String> used = new HashSet<>();
        for (PmcIdentityPreference.PmcIdentity identity : PmcIdentityPreference.all(sp.server).values()) {
            if (POOL.equals(identity.logoPool())) used.add(identity.logoId());
        }
        for (String id : used) {
            Entry e = BY_ID.get(id);
            if (e != null) send(PacketDistributor.PLAYER.with(() -> sp), e, id);
        }
    }

    // --- Download ------------------------------------------------------------

    /** Completes with an {@link Entry} on success or an error reason string. Never exceptionally. */
    private static CompletableFuture<Object> fetch(String url) {
        int gen = generation;
        return CompletableFuture.supplyAsync(() -> {
            Object result = download(url);
            if (result instanceof Entry e && gen == generation) {
                if (BY_ID.size() >= MAX_ENTRIES) evictOne();
                BY_ID.put(idFor(url), e);
            }
            return result;
        }, EXEC);
    }

    private static void evictOne() {
        Iterator<String> it = BY_ID.keySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    @Nullable
    private static String checkSyntax(String url) {
        if (url.isEmpty() || url.length() > MAX_URL) return ERR_BAD_URL;
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                return ERR_BAD_URL;
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) return ERR_BAD_URL;
            return null;
        } catch (Exception e) {
            return ERR_BAD_URL;
        }
    }

    private static Object download(String url) {
        try {
            URI uri = new URI(url);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                String bad = checkSyntax(uri.toString());
                if (bad != null) return bad;
                if (!publicHost(uri.getHost())) return ERR_BLOCKED;
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(8))
                        .header("User-Agent", "Minecraft-mod-logo-fetch")
                        .header("Accept", "image/png,image/jpeg,image/gif,image/bmp,image/*;q=0.8")
                        .GET()
                        .build();
                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code >= 300 && code < 400) {
                    resp.body().close();
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc == null) return ERR_HTTP;
                    uri = uri.resolve(loc);
                    continue;
                }
                if (code != 200) {
                    resp.body().close();
                    return ERR_HTTP;
                }
                byte[] body;
                try (InputStream in = resp.body()) {
                    body = in.readNBytes(MAX_DOWNLOAD + 1);
                }
                if (body.length > MAX_DOWNLOAD) return ERR_TOO_BIG;
                byte[] png = reencode(body);
                return png == null ? ERR_FORMAT : new Entry(url, png);
            }
            return ERR_HTTP;
        } catch (HttpTimeoutException e) {
            return ERR_TIMEOUT;
        } catch (TooBig e) {
            return ERR_TOO_BIG;
        } catch (Exception e) {
            LOGGER.debug("[sewv-web-logo] {} failed: {}", url, e.toString());
            return ERR_HTTP;
        }
    }

    /** Refuses loopback, private, link local and other non public addresses. */
    private static boolean publicHost(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) {
                    return false;
                }
                byte[] b = a.getAddress();
                if (a instanceof Inet4Address) {
                    int b0 = b[0] & 0xFF;
                    int b1 = b[1] & 0xFF;
                    if (b0 == 0 || (b0 == 100 && b1 >= 64 && b1 < 128) || (b0 == 198 && (b1 == 18 || b1 == 19))
                            || b0 >= 224) {
                        return false;
                    }
                } else if (a instanceof Inet6Address && (b[0] & 0xFE) == 0xFC) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class TooBig extends Exception {
    }

    /** Decodes any ImageIO format and fits it into a transparent {@link #SIZE} square PNG. */
    @Nullable
    private static byte[] reencode(byte[] data) throws Exception {
        BufferedImage src;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) return null;
                if (w > MAX_SOURCE_DIM || h > MAX_SOURCE_DIM) throw new TooBig();
                src = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (src == null) return null;

        int w = src.getWidth();
        int h = src.getHeight();
        float scale = Math.min((float) SIZE / w, (float) SIZE / h);
        boolean pixelArt = w <= SIZE && h <= SIZE;
        if (pixelArt) scale = Math.max(1, (int) scale);
        // Halve big sources first; one bilinear step from 1000 px to 64 aliases badly.
        BufferedImage cur = toArgb(src);
        while (!pixelArt && cur.getWidth() * scale < cur.getWidth() / 2f && cur.getHeight() * scale < cur.getHeight() / 2f) {
            int nw = Math.max(1, cur.getWidth() / 2);
            int nh = Math.max(1, cur.getHeight() / 2);
            scale *= 2;
            cur = draw(cur, nw, nh, 0, 0, nw, nh, false);
        }
        int dw = Math.max(1, Math.round(cur.getWidth() * scale));
        int dh = Math.max(1, Math.round(cur.getHeight() * scale));
        BufferedImage out = draw(cur, SIZE, SIZE, (SIZE - dw) / 2, (SIZE - dh) / 2, dw, dh, pixelArt);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(out, "png", bytes)) return null;
        return bytes.toByteArray();
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        return draw(src, src.getWidth(), src.getHeight(), 0, 0, src.getWidth(), src.getHeight(), true);
    }

    private static BufferedImage draw(BufferedImage src, int cw, int ch, int x, int y, int w, int h, boolean nearest) {
        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, nearest
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, x, y, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
