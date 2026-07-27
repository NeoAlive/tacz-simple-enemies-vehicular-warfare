package com.neoalive.tacz_sewv.entity.ai.command;

/**
 * Per-play role carve — one {@link Assignment} per group member (driver).
 */
public final class Roles {

    public final Assignment[] assignments;

    public Roles(Assignment[] assignments) {
        this.assignments = assignments;
    }

    public int size() {
        return this.assignments.length;
    }

    public int count(Assignment.Role role) {
        int n = 0;
        for (Assignment a : this.assignments) {
            if (a.role == role) n++;
        }
        return n;
    }
}
