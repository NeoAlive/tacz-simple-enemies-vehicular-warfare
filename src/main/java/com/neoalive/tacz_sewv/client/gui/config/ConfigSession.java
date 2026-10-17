package com.neoalive.tacz_sewv.client.gui.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.client.Minecraft;

import com.neoalive.tacz_sewv.config.ConfigApplier;
import com.neoalive.tacz_sewv.config.ConfigEntry;
import com.neoalive.tacz_sewv.config.ConfigRegistry;
import com.neoalive.tacz_sewv.config.ConfigScope;
import com.neoalive.tacz_sewv.config.ConfigValidator;
import com.neoalive.tacz_sewv.config.ConfigValueType;
import com.neoalive.tacz_sewv.network.NetworkHandler;
import com.neoalive.tacz_sewv.network.PacketSaveConfigUI;

/**
 * The drafts behind every config screen. One session spans the home screen and every page opened
 * from it, so an edit survives moving between pages; nothing is applied until {@link #save}.
 * Values are draft strings in {@link ConfigValidator}'s format, keyed by registry index.
 */
public final class ConfigSession {

    public final boolean canEditServer;
    private final Map<Integer, String> clientDraft;
    private final Map<Integer, String> serverDraft;
    private final Map<Integer, String> clientBaseline;
    private final Map<Integer, String> serverBaseline;

    public ConfigSession(boolean canEditServer, Map<Integer, String> clientDraft, Map<Integer, String> serverDraft) {
        this.canEditServer = canEditServer;
        this.clientDraft = new HashMap<>(clientDraft);
        this.serverDraft = new HashMap<>(serverDraft);
        this.clientBaseline = Map.copyOf(clientDraft);
        this.serverBaseline = Map.copyOf(serverDraft);
    }

    private Map<Integer, String> draft(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? this.clientDraft : this.serverDraft;
    }

    private Map<Integer, String> baseline(ConfigScope scope) {
        return scope == ConfigScope.CLIENT ? this.clientBaseline : this.serverBaseline;
    }

    public boolean editable(ConfigEntry e) {
        return e.scope == ConfigScope.CLIENT || this.canEditServer;
    }

    public String get(ConfigEntry e) {
        return draft(e.scope).computeIfAbsent(e.index, k -> e.draftString());
    }

    public void set(ConfigEntry e, String value) {
        draft(e.scope).put(e.index, value);
    }

    public void reset(ConfigEntry e) {
        set(e, e.defaultDraftString());
    }

    public boolean valid(ConfigEntry e) {
        return ConfigValidator.isValid(e, get(e));
    }

    /** Compared parsed, so "5" and "5.0" or a re-ordered case of an enum are not "changed". */
    public boolean isDefault(ConfigEntry e) {
        Object cur = ConfigValidator.parse(e, get(e));
        return cur != null && cur.equals(ConfigValidator.parse(e, e.defaultDraftString()));
    }

    /** Edited in this session and not saved yet. */
    public boolean unsaved(ConfigEntry e) {
        if (!editable(e) || !hasValue(e)) return false;
        String d = draft(e.scope).get(e.index);
        return d != null && !d.equals(baseline(e.scope).get(e.index));
    }

    public boolean bool(String key) {
        ConfigEntry e = ConfigRegistry.byKey(key);
        return e != null && Boolean.parseBoolean(get(e));
    }

    public boolean dirty() {
        return !changes(ConfigScope.CLIENT).isEmpty()
                || (this.canEditServer && !changes(ConfigScope.SERVER).isEmpty());
    }

    /** Every changed draft parses; unchanged ones are whatever the config already holds. */
    public boolean valid() {
        for (ConfigScope scope : ConfigScope.values()) {
            for (Map.Entry<Integer, String> c : changes(scope).entrySet()) {
                ConfigEntry e = ConfigRegistry.byIndex(c.getKey());
                if (e != null && !ConfigValidator.isValid(e, c.getValue())) return false;
            }
        }
        return true;
    }

    public void save(Minecraft mc) {
        if (!valid()) return;
        Map<Integer, String> client = changes(ConfigScope.CLIENT);
        if (!client.isEmpty()) ConfigApplier.applyClient(client);

        if (!this.canEditServer) return;
        Map<Integer, String> server = changes(ConfigScope.SERVER);
        if (server.isEmpty()) return;
        var integrated = mc.getSingleplayerServer();
        if (integrated != null && mc.player != null) {
            var sp = integrated.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp != null) ConfigApplier.applyServer(sp, server);
        } else {
            NetworkHandler.CHANNEL.sendToServer(new PacketSaveConfigUI(server));
        }
    }

    private Map<Integer, String> changes(ConfigScope scope) {
        Map<Integer, String> draft = draft(scope);
        Map<Integer, String> baseline = baseline(scope);
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<Integer, String> d : draft.entrySet()) {
            if (!Objects.equals(d.getValue(), baseline.get(d.getKey()))) out.put(d.getKey(), d.getValue());
        }
        return out;
    }

    private static boolean hasValue(ConfigEntry e) {
        return e.type != ConfigValueType.SHORTCUT && e.type != ConfigValueType.SECTION_HEADER;
    }
}
