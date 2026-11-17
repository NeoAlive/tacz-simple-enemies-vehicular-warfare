package com.neoalive.tacz_sewv.client.perf;

import net.minecraft.world.phys.Vec3;

/** Pins the parked and far tests behind client vehicle simulation thinning. */
public final class VehicleClientLodSelfCheck {

    public static void main(String[] args) {
        assert VehicleClientLod.atRest(true, 0.0, 0.0, 0.0, 0.0, 0.0) : "parked hull is at rest";
        assert !VehicleClientLod.atRest(false, 0.0, 0.0, 0.0, 0.0, 0.0) : "airborne hull is never at rest";
        assert !VehicleClientLod.atRest(true, 0.01, 0.0, 0.0, 0.0, 0.0) : "creeping hull is moving";
        assert !VehicleClientLod.atRest(true, 0.0, 0.0, 0.0, 0.0, 0.01) : "lerping hull is moving";
        assert VehicleClientLod.atRest(true, 0.0005, 0.0, 0.0, -0.0005, 0.0) : "jitter under epsilon is rest";

        Vec3 camera = new Vec3(0.0, 64.0, 0.0);
        assert VehicleClientLod.beyond(camera, 49.0, 64.0, 0.0, 48) : "49 blocks is beyond 48";
        assert !VehicleClientLod.beyond(camera, 48.0, 64.0, 0.0, 48) : "48 blocks is not beyond 48";
        assert !VehicleClientLod.beyond(camera, 30.0, 64.0, 30.0, 48) : "42 blocks diagonal is near";
        assert !VehicleClientLod.beyond(null, 500.0, 64.0, 0.0, 48) : "no camera never throttles";

        System.out.println("VehicleClientLodSelfCheck OK");
    }

    private VehicleClientLodSelfCheck() {}
}
