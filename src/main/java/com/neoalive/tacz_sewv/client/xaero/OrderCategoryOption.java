package com.neoalive.tacz_sewv.client.xaero;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** One category row in Xaero's right-click menu. Hover opens {@link OrderFlyout}; a click pins it. */
public class OrderCategoryOption extends RightClickOption {

    private final MapOrderCatalog.Category category;

    public OrderCategoryOption(int index, IRightClickableElement target, MapOrderCatalog.Category category) {
        super(category.glyph() + " " + category.label() + "  ▸", Style.EMPTY, index, target);
        this.category = category;
    }

    @Override
    public void onAction(Screen screen) {
        OrderFlyout.pin(this.category);
    }
}
