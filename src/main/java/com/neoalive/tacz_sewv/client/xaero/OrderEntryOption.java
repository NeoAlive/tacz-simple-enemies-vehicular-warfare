package com.neoalive.tacz_sewv.client.xaero;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** A single order row straight in Xaero's menu, for the wheel's root-level Cancel. */
public class OrderEntryOption extends RightClickOption {

    private final MapOrderCatalog.Entry entry;
    private final UnitOrderOption.MapClick click;

    public OrderEntryOption(int index, IRightClickableElement target, MapOrderCatalog.Entry entry,
                            UnitOrderOption.MapClick click) {
        super(entry.label(), Style.EMPTY.withColor(ChatFormatting.RED), index, target);
        this.entry = entry;
        this.click = click;
        setActive(entry.enabled());
    }

    @Override
    public void onAction(Screen screen) {
        this.entry.run().run(this.click);
    }
}
