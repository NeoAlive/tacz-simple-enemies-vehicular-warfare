package com.neoalive.tacz_sewv.client.radial;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.neoalive.tacz_sewv.command.quick.QuickCommandRegistry;
import com.neoalive.tacz_sewv.command.quick.QuickMinefieldPipeline;
import com.neoalive.tacz_sewv.command.quick.QuickPlaneRolePipeline;
import com.neoalive.tacz_sewv.entity.ai.support.mines.MinefieldPlanner;
import com.neoalive.tacz_sewv.item.PlaneRole;

/**
 * The values the two engineer-ish leaves carry. Deploy Equipment: Ctrl+scroll flips whether the unit
 * also crews what it sets down (off by default). Deploy Minefield: Ctrl+scroll steps the mine type
 * (Auto first, then the server's MINE pool), Alt+scroll the shape. Both ride the pipeline id.
 */
public final class FieldChoice {

    private static boolean manNow = false;
    private static List<String> minePool = List.of();
    /** 0 = Auto, otherwise {@code minePool[mineIndex - 1]}. */
    private static int mineIndex = 0;
    private static MinefieldPlanner.ShapeChoice shape = MinefieldPlanner.ShapeChoice.AUTO;
    private static PlaneRole planeRole = PlaneRole.AUTO;

    private FieldChoice() {}

    public static void setMinePool(List<String> ids) {
        minePool = List.copyOf(ids);
        if (mineIndex > minePool.size()) mineIndex = 0;
    }

    public static boolean isChoiceLeaf(String pipelineId) {
        return QuickCommandRegistry.ID_QUICK_DEPLOY_EQUIPMENT.equals(pipelineId)
                || QuickCommandRegistry.ID_QUICK_MINEFIELD.equals(pipelineId)
                || QuickCommandRegistry.ID_PLANE_ROLE.equals(pipelineId);
    }

    public static boolean isPlaneRoleLeaf(String pipelineId) {
        return QuickCommandRegistry.ID_PLANE_ROLE.equals(pipelineId);
    }

    public static boolean isMinefieldLeaf(String pipelineId) {
        return QuickCommandRegistry.ID_QUICK_MINEFIELD.equals(pipelineId);
    }

    /** Ctrl+scroll. Returns whether anything moved. */
    public static boolean step(String pipelineId, int dir) {
        if (QuickCommandRegistry.ID_QUICK_DEPLOY_EQUIPMENT.equals(pipelineId)) {
            manNow = !manNow;
            return true;
        }
        if (isPlaneRoleLeaf(pipelineId)) {
            planeRole = dir > 0 ? planeRole.next() : planeRole.previous();
            return true;
        }
        if (isMinefieldLeaf(pipelineId)) {
            int n = minePool.size() + 1;
            if (n < 2) return false;
            mineIndex = Math.floorMod(mineIndex + Integer.signum(dir), n);
            return true;
        }
        return false;
    }

    /** Alt+scroll on the minefield leaf. */
    public static void stepShape(int dir) {
        MinefieldPlanner.ShapeChoice[] all = MinefieldPlanner.ShapeChoice.values();
        shape = all[Math.floorMod(shape.ordinal() + Integer.signum(dir), all.length)];
    }

    public static String concreteId(String pipelineId) {
        if (QuickCommandRegistry.ID_QUICK_DEPLOY_EQUIPMENT.equals(pipelineId)) {
            return QuickCommandRegistry.deployEquipmentId(manNow);
        }
        if (isPlaneRoleLeaf(pipelineId)) return QuickCommandRegistry.planeRoleId(planeRole);
        if (isMinefieldLeaf(pipelineId)) {
            String item = mineIndex == 0 ? QuickMinefieldPipeline.AUTO : minePool.get(mineIndex - 1);
            return QuickMinefieldPipeline.id(shape.ordinal(), item);
        }
        return pipelineId;
    }

    public static String label(String pipelineId) {
        String key = QuickCommandRegistry.labelKey(pipelineId) + ".picked";
        if (QuickCommandRegistry.ID_QUICK_DEPLOY_EQUIPMENT.equals(pipelineId)) {
            return I18n.get(key, I18n.get(manValueKey()));
        }
        if (isPlaneRoleLeaf(pipelineId)) return I18n.get(key, planeRoleName());
        return I18n.get(key, mineName());
    }

    public static String icon(String pipelineId, String fallback) {
        if (QuickCommandRegistry.ID_QUICK_DEPLOY_EQUIPMENT.equals(pipelineId)) return manNow ? "⚒" : "⚓";
        if (isPlaneRoleLeaf(pipelineId)) {
            return switch (planeRole) {
                case AUTO -> fallback;
                case CAS -> "\u2316";
                case FIGHTER -> "\u2708";
            };
        }
        return fallback;
    }

    public static String manValueKey() {
        return manNow ? "gui.tacz_sewv.wheel.scale.deploy.man" : "gui.tacz_sewv.wheel.scale.deploy.place";
    }

    public static PlaneRole planeRole() {
        return planeRole;
    }

    public static String planeRoleName() {
        return planeRoleName(planeRole);
    }

    public static String planeRoleName(PlaneRole role) {
        return I18n.get(QuickPlaneRolePipeline.roleKey(role));
    }

    public static String widestPlaneRoleName() {
        String widest = "";
        for (PlaneRole role : PlaneRole.values()) {
            String n = planeRoleName(role);
            if (n.length() > widest.length()) widest = n;
        }
        return widest;
    }

    public static boolean manNow() {
        return manNow;
    }

    public static String mineName() {
        return nameAt(mineIndex);
    }

    private static String nameAt(int index) {
        if (index == 0) return I18n.get("gui.tacz_sewv.wheel.scale.mine.auto");
        ResourceLocation rl = ResourceLocation.tryParse(minePool.get(index - 1));
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return item == null ? String.valueOf(rl) : item.getDescription().getString();
    }

    public static String widestMineName() {
        String widest = nameAt(0);
        for (int i = 1; i <= minePool.size(); i++) {
            String n = nameAt(i);
            if (n.length() > widest.length()) widest = n;
        }
        return widest;
    }

    public static float mineFraction() {
        int n = minePool.size();
        return n == 0 ? 0.0f : mineIndex / (float) n;
    }

    public static String shapeName() {
        return I18n.get("gui.tacz_sewv.wheel.scale.mine.shape." + shape.name().toLowerCase(Locale.ROOT));
    }
}
