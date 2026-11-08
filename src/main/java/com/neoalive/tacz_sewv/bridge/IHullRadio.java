package com.neoalive.tacz_sewv.bridge;

import com.neoalive.tacz_sewv.crew.RadioQueue;

/** The hull's radio queue, mixed onto SBW's {@code VehicleEntity} by {@code MixinVehicleRadioQueue}. Transient. */
public interface IHullRadio {

    /** The queue, created on first use. */
    RadioQueue sewv$radio();

    /** The queue if one exists, else null. */
    RadioQueue sewv$radioOrNull();
}
