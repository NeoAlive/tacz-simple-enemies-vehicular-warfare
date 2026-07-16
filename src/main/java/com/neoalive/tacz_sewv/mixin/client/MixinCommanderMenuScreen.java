package com.neoalive.tacz_sewv.mixin.client;

import com.neoalive.tacz_sewv.client.FormationAxisSelection;
import net.minecraft.client.gui.screens.Screen;
import net.nekoyuni.SimpleEnemyMod.client.gui.screens.CommanderMenuScreen;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Set;

/**
 * Diverts SEM's wedge/column rows to the vehicle formation flow when the selection is mostly
 * mounted, and leaves them completely alone when it is not.
 *
 * <p>The gate hooks issueOrderToSelected rather than handleMainAction: it receives the OrderType
 * directly instead of coupling us to the menu's magic row indices, and it is the single choke
 * point both formation rows funnel through. Declining is a plain return, so an infantry order
 * runs SEM's code untouched — including its own "no units selected" message.
 *
 * <p>Handing off mirrors SEM's own deferred-click rows ("Move To..." / "Attack that..."): arm the
 * mode, close the screen, and let the click that follows finish the order.
 */
@Mixin(CommanderMenuScreen.class)
public abstract class MixinCommanderMenuScreen {

    @Shadow(remap = false)
    @Final
    private Set<Integer> selectedEntityIds;

    @Inject(method = "issueOrderToSelected", at = @At("HEAD"), cancellable = true, remap = false)
    private void tacz_sewv$gateVehicleFormation(OrderType order, int targetEntityId, CallbackInfo ci) {
        if (order != OrderType.FORM_WEDGE && order != OrderType.FORM_COLUMN) return;

        List<PmcUnitEntity> units = FormationAxisSelection.resolveOwned(this.selectedEntityIds);
        if (units.isEmpty()) return; // let SEM say so
        if (!FormationAxisSelection.mostlyMounted(units)) return; // infantry order — SEM's path

        FormationAxisSelection.begin(order, units);
        ((Screen) (Object) this).onClose();
        ci.cancel();
    }
}
